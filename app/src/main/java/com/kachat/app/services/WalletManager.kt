package com.kachat.app.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.MnemonicCode
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WalletManager — secure key lifecycle management.
 */
@Singleton
class WalletManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "kachat_wallet_key"
        private const val SECURE_PREFS_NAME = "kachat_secure_prefs"
        private const val PREF_ACCOUNTS = "accounts"
        private const val PREF_ACTIVE_ADDRESS = "active_address"

        // bitcoinj's own `MnemonicCode.INSTANCE` static initializer loads its wordlist via
        // `Class.getResourceAsStream`, which is documented by bitcoinj itself as "Won't work on
        // Android" — it fails silently there, leaving INSTANCE null and crashing every wallet
        // create/import with an NPE. Bundle the identical wordlist bitcoinj ships internally as
        // an Android asset and initialize INSTANCE from it manually instead.
        private const val WORDLIST_ASSET_NAME = "bip39-wordlist-english.txt"
        private const val WORDLIST_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db"
    }

    init {
        if (MnemonicCode.INSTANCE == null) {
            MnemonicCode.INSTANCE = context.assets.open(WORDLIST_ASSET_NAME).use {
                MnemonicCode(it, WORDLIST_SHA256)
            }
        }
    }

    data class Account(
        val name: String,
        val address: String,
        val mnemonic: String,
        // Gson deserializes old-shape stored JSON (from before this field existed) with this
        // defaulted to 0 — verified in WalletManagerTest's Gson round-trip test, since every
        // existing on-device account depends on that being true. See deriveSpendingAddress.
        val spendingAddressIndex: Int = 0,
        // Highest spending-chain index the Manage Addresses screen has ever generated/shown —
        // distinct from [spendingAddressIndex] (which is the address "Pay in Kaspa" currently
        // sources from). Generating a new address raises this without changing which one is
        // active; same Gson zero-default behavior as spendingAddressIndex above for old JSON.
        val maxSpendingAddressIndex: Int = 0
    )

    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Returns true if at least one wallet has been created/imported.
     */
    fun hasWallet(): Boolean {
        return getAccounts().isNotEmpty()
    }

    private fun getAccounts(): List<Account> {
        val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<Account>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAccounts(accounts: List<Account>) {
        val json = gson.toJson(accounts)
        sharedPrefs.edit().putString(PREF_ACCOUNTS, json).apply()
    }

    /**
     * The active wallet's address, reactive to every account switch/create/import/delete —
     * ChatRepository re-scopes its contact/message queries off this so switching accounts
     * doesn't require recreating any ViewModel, and (critically) so chat data from one
     * account never leaks into another's view just because the underlying Flow was built
     * once and never re-subscribed.
     */
    private val _activeAddress = MutableStateFlow(computeActiveAddress())
    val activeAddressFlow: StateFlow<String?> = _activeAddress.asStateFlow()

    private fun computeActiveAddress(): String? = getActiveAccount()?.address

    private fun refreshActiveAddressFlow() {
        _activeAddress.value = computeActiveAddress()
    }

    fun setActiveAccount(address: String) {
        sharedPrefs.edit().putString(PREF_ACTIVE_ADDRESS, address).apply()
        refreshActiveAddressFlow()
    }

    fun getActiveAccount(): Account? {
        val address = sharedPrefs.getString(PREF_ACTIVE_ADDRESS, null) ?: return getAccounts().firstOrNull()
        return getAccounts().find { it.address == address }
    }

    fun getAllAccounts(): List<Account> = getAccounts()

    /**
     * Generate a new BIP39 mnemonic and store it securely.
     */
    fun createWallet(name: String, wordCount: Int = 12): List<String> {
        val entropySize = if (wordCount == 24) 32 else 16
        val entropy = ByteArray(entropySize)
        SecureRandom().nextBytes(entropy)
        val mnemonicWords = MnemonicCode.INSTANCE.toMnemonic(entropy)
        
        val address = deriveAddress(mnemonicWords)
        val accounts = getAccounts().toMutableList()
        accounts.add(Account(name, address, mnemonicWords.joinToString(" ")))
        saveAccounts(accounts)
        setActiveAccount(address)
        
        return mnemonicWords
    }

    /**
     * Import an existing wallet from a BIP39 mnemonic phrase. Throws [org.bitcoinj.crypto.MnemonicException]
     * if the phrase's checksum/wordlist is invalid — the caller is expected to catch this and show
     * the user an error, not let it crash silently.
     *
     * Re-importing a mnemonic that's already saved overwrites that entry's name and moves it to
     * the top rather than creating a duplicate, matching iOS's `updateSavedAccounts` behavior
     * (`WalletManager.swift:501-509`: remove any existing entry with the same address, then
     * re-insert at index 0).
     */
    fun importWallet(mnemonic: List<String>, name: String) {
        MnemonicCode.INSTANCE.check(mnemonic)
        val address = deriveAddress(mnemonic)
        val accounts = getAccounts().filter { it.address != address }.toMutableList()
        accounts.add(0, Account(name, address, mnemonic.joinToString(" ")))
        saveAccounts(accounts)
        setActiveAccount(address)
    }

    /**
     * Wipes all wallets from the device.
     */
    fun wipe() {
        sharedPrefs.edit().clear().apply()
        refreshActiveAddressFlow()
    }

    /**
     * Deletes a specific account.
     */
    fun deleteAccount(address: String) {
        val accounts = getAccounts().filter { it.address != address }
        saveAccounts(accounts)
        if (sharedPrefs.getString(PREF_ACTIVE_ADDRESS, null) == address) {
            sharedPrefs.edit().remove(PREF_ACTIVE_ADDRESS).apply()
        }
        refreshActiveAddressFlow()
    }

    /**
     * Shared HD path walk — `m/44'/111111'/{accountIndex}'/0/{addressIndex}`. The identity
     * address/key (used everywhere except spending) is `accountIndex=0, addressIndex=0`, always.
     * The spending-address chain lives at `accountIndex=1` — a distinct hardened branch, so it
     * can never collide with the identity path no matter how far its own addressIndex advances.
     */
    private fun deriveKey(mnemonic: List<String>, accountIndex: Int = 0, addressIndex: Int = 0): DeterministicKey {
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)

        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccountH = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(accountIndex, true))
        val keyChain0 = HDKeyDerivation.deriveChildKey(keyAccountH, ChildNumber(0, false))
        return HDKeyDerivation.deriveChildKey(keyChain0, ChildNumber(addressIndex, false))
    }

    private fun addressFromKey(key: DeterministicKey): String {
        val pubKey = key.pubKey
        val xOnlyPubKey = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey
        return KaspaAddress.encode("kaspa", 0x00, xOnlyPubKey)
    }

    private fun deriveAddress(mnemonic: List<String>): String = addressFromKey(deriveKey(mnemonic))

    /**
     * Returns the primary Kaspa address for the active wallet.
     */
    fun getAddress(): String {
        return getActiveAccount()?.address ?: throw IllegalStateException("No active account")
    }

    fun getAccountName(): String {
        return getActiveAccount()?.name ?: "My Account"
    }

    /** Renames a saved account in place — used from the Profile screen's editable account name. */
    fun renameAccount(address: String, newName: String) {
        val accounts = getAccounts().map { if (it.address == address) it.copy(name = newName) else it }
        saveAccounts(accounts)
    }

    fun getActiveMnemonic(): String? {
        return getActiveAccount()?.mnemonic
    }

    fun getPrivateKeyHex(): String {
        return getPrivateKeyBytes().joinToString("") { "%02x".format(it) }
    }

    fun getPrivateKeyBytes(): ByteArray {
        val mnemonic = getActiveMnemonic()?.split(" ") ?: throw IllegalStateException("No active account")
        return deriveKey(mnemonic).privKeyBytes
    }

    // --- Spending address (accountIndex=1 branch) ---------------------------------------
    // Separate from the identity address above for payment privacy: "Pay in Kaspa" sends
    // sweep this address's entire balance and route change to a freshly derived next index
    // (see KaspaWalletEngine.sendSpendingPayment), so KAS never sits in more than one
    // spending-chain address at a time. Messaging (handshakes/chat messages) is untouched —
    // still identity-address-sourced via getAddress()/getPrivateKeyBytes() above.

    private fun activeMnemonicWords(): List<String> =
        getActiveMnemonic()?.split(" ") ?: throw IllegalStateException("No active account")

    fun deriveSpendingAddress(index: Int): String =
        addressFromKey(deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index))

    fun getSpendingPrivateKeyBytes(index: Int): ByteArray =
        deriveKey(activeMnemonicWords(), accountIndex = 1, addressIndex = index).privKeyBytes

    /** The spending address a "Pay in Kaspa" send should currently source funds from/top up. */
    fun currentSpendingAddress(): String {
        val index = getActiveAccount()?.spendingAddressIndex ?: throw IllegalStateException("No active account")
        return deriveSpendingAddress(index)
    }

    /** Called only after a spending-address send is actually accepted by the network. */
    fun advanceSpendingAddressIndex(address: String) {
        val accounts = getAccounts().map {
            if (it.address == address) {
                val next = it.spendingAddressIndex + 1
                it.copy(spendingAddressIndex = next, maxSpendingAddressIndex = maxOf(it.maxSpendingAddressIndex, next))
            } else it
        }
        saveAccounts(accounts)
    }

    /** Sets an explicit index as the one "Pay in Kaspa" sources from — used by the wallet-import gap-limit scan, and by manually activating an address from the Manage Addresses screen. */
    fun setSpendingAddressIndex(address: String, index: Int) {
        val accounts = getAccounts().map {
            if (it.address == address) {
                it.copy(spendingAddressIndex = index, maxSpendingAddressIndex = maxOf(it.maxSpendingAddressIndex, index))
            } else it
        }
        saveAccounts(accounts)
    }

    /** Derives one more spending-chain address for the Manage Addresses screen to show, without changing which one is currently active. Returns the new highest index. */
    fun generateNextSpendingAddress(address: String): Int {
        var newMax = 0
        val accounts = getAccounts().map {
            if (it.address == address) {
                newMax = maxOf(it.maxSpendingAddressIndex, it.spendingAddressIndex) + 1
                it.copy(maxSpendingAddressIndex = newMax)
            } else it
        }
        saveAccounts(accounts)
        return newMax
    }

    /**
     * Raises [Account.maxSpendingAddressIndex] to at least [minIndex] without touching which
     * address is currently active — used after a "Discover Addresses" scan turns up on-chain
     * history past what the Manage Addresses screen currently shows (e.g. KAS sent directly to
     * an address before it was ever generated locally).
     */
    fun ensureMaxSpendingAddressIndexAtLeast(address: String, minIndex: Int) {
        val accounts = getAccounts().map {
            if (it.address == address && minIndex > it.maxSpendingAddressIndex) {
                it.copy(maxSpendingAddressIndex = minIndex)
            } else it
        }
        saveAccounts(accounts)
    }

    /**
     * Signs a Kaspa transaction payload with the wallet private key.
     */
    fun sign(payload: ByteArray): ByteArray {
        // TODO: secp256k1 signing
        return ByteArray(0)
    }

    /**
     * Derives the shared symmetric key (ECDH + HKDF-SHA256) with a contact's
     * x-only secp256k1 public key. See [com.kachat.app.util.KasiaCipher].
     */
    fun deriveSharedSecret(contactPublicKeyHex: String): ByteArray {
        val peerPubKey = contactPublicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return com.kachat.app.util.KasiaCipher.deriveSymmetricKey(getPrivateKeyBytes(), peerPubKey)
    }

    /** The alias I watch for incoming deterministic-alias messages from this contact. */
    fun myDeterministicAlias(contactAddress: String): String {
        val theirPubKey = KaspaAddress.decode(contactAddress).second
        val myPubKey = KaspaAddress.decode(getAddress()).second
        return com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(getPrivateKeyBytes(), theirPubKey, contextXOnlyPubKey = myPubKey)
    }

    /** The alias I tag outgoing deterministic-alias messages to this contact with. */
    fun theirDeterministicAlias(contactAddress: String): String {
        val theirPubKey = KaspaAddress.decode(contactAddress).second
        return com.kachat.app.util.KasiaCipher.deriveDeterministicAlias(getPrivateKeyBytes(), theirPubKey, contextXOnlyPubKey = theirPubKey)
    }
}
