package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.util.KaspaExtendedPublicKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists imported KasSigner "kpub" watch-only accounts — deliberately in its own
 * `EncryptedSharedPreferences` file, entirely separate from [WalletManager]'s. Cold Storage is a
 * completely different trust domain from KaChat's identity/spending wallet: no mnemonic or
 * private key material is ever stored here, only public keys, and a future "wipe wallet" action
 * on the main wallet must never be able to touch (or be confused with) this store, or vice versa.
 */
@Singleton
class ColdStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val SECURE_PREFS_NAME = "cold_storage_secure_prefs"
        private const val PREF_ACCOUNTS = "cold_accounts"
    }

    data class ColdAccount(
        val id: String,
        val name: String,
        val kpub: String,
        // Highest external-chain (chain 0) index ever derived/shown for this account — bounds
        // the Manage-style address list the same way WalletManager.Account.maxSpendingAddressIndex
        // bounds the spending-chain list.
        val maxDerivedIndex: Int = 0
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

    fun getAccounts(): List<ColdAccount> {
        val json = sharedPrefs.getString(PREF_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<ColdAccount>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveAccounts(accounts: List<ColdAccount>) {
        val json = gson.toJson(accounts)
        sharedPrefs.edit().putString(PREF_ACCOUNTS, json).apply()
    }

    /** Re-importing the same kpub updates the existing entry's name rather than creating a duplicate. */
    fun saveAccount(name: String, kpub: String): Result<ColdAccount> {
        if (!KaspaExtendedPublicKey.isValidKpub(kpub)) {
            return Result.failure(IllegalArgumentException("Not a valid kpub"))
        }
        val existing = getAccounts().find { it.kpub == kpub }
        val account = existing?.copy(name = name) ?: ColdAccount(id = UUID.randomUUID().toString(), name = name, kpub = kpub)
        val accounts = getAccounts().filter { it.kpub != kpub } + account
        saveAccounts(accounts)
        return Result.success(account)
    }

    fun renameAccount(id: String, newName: String) {
        saveAccounts(getAccounts().map { if (it.id == id) it.copy(name = newName) else it })
    }

    fun deleteAccount(id: String) {
        saveAccounts(getAccounts().filter { it.id != id })
    }

    fun ensureMaxDerivedIndexAtLeast(id: String, minIndex: Int) {
        saveAccounts(
            getAccounts().map {
                if (it.id == id && minIndex > it.maxDerivedIndex) it.copy(maxDerivedIndex = minIndex) else it
            }
        )
    }
}
