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
    }

    data class Account(
        val name: String,
        val address: String,
        val mnemonic: String
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

    private fun deriveAddress(mnemonic: List<String>): String {
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)
        
        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccount0h = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(0, true))
        val keyChain0 = HDKeyDerivation.deriveChildKey(keyAccount0h, ChildNumber(0, false))
        val finalKey = HDKeyDerivation.deriveChildKey(keyChain0, ChildNumber(0, false))

        val pubKey = finalKey.pubKey
        val xOnlyPubKey = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey

        return KaspaAddress.encode("kaspa", 0x00, xOnlyPubKey)
    }

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
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)
        
        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccount0h = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(0, true))
        val keyChain0 = HDKeyDerivation.deriveChildKey(keyAccount0h, ChildNumber(0, false))
        val finalKey = HDKeyDerivation.deriveChildKey(keyChain0, ChildNumber(0, false))

        return finalKey.privKeyBytes
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
