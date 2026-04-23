package com.kachat.app.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed wrapper around DataStore<Preferences>.
 * Equivalent to AppSettings in the iOS app.
 *
 * Provides reactive Flows for all settings so the UI updates automatically.
 */
@Singleton
class AppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        // Network
        val KEY_NETWORK          = stringPreferencesKey("network")           // "mainnet" | "testnet"
        val KEY_INDEXER_URL      = stringPreferencesKey("indexer_url")
        val KEY_KNS_API_URL      = stringPreferencesKey("kns_api_url")
        val KEY_KASPA_REST_URL   = stringPreferencesKey("kaspa_rest_url")

        // Defaults matching the iOS app
        const val DEFAULT_NETWORK        = "mainnet"
        const val DEFAULT_INDEXER_URL    = "https://api.kasia.io"
        const val DEFAULT_KNS_API_URL    = "https://api.kns.kaspa.org"
        const val DEFAULT_KASPA_REST_URL = "https://api.kaspa.org"

        // Wallet (just a flag — actual keys live in Keystore)
        val KEY_HAS_WALLET       = booleanPreferencesKey("has_wallet")
        val KEY_ACTIVE_ADDRESS   = stringPreferencesKey("active_address")
        
        val KEY_ESTIMATE_FEES    = booleanPreferencesKey("estimate_fees")
    }

    // -------------------------------------------------------------------------
    // Reactive flows (collect in ViewModel with .stateIn)
    // -------------------------------------------------------------------------

    val network: Flow<String> = dataStore.data.map {
        it[KEY_NETWORK] ?: DEFAULT_NETWORK
    }

    val indexerUrl: Flow<String> = dataStore.data.map {
        it[KEY_INDEXER_URL] ?: DEFAULT_INDEXER_URL
    }

    val knsApiUrl: Flow<String> = dataStore.data.map {
        it[KEY_KNS_API_URL] ?: DEFAULT_KNS_API_URL
    }

    val kaspaRestUrl: Flow<String> = dataStore.data.map {
        it[KEY_KASPA_REST_URL] ?: DEFAULT_KASPA_REST_URL
    }

    val hasWallet: Flow<Boolean> = dataStore.data.map {
        it[KEY_HAS_WALLET] ?: false
    }

    val activeAddress: Flow<String?> = dataStore.data.map {
        it[KEY_ACTIVE_ADDRESS]
    }

    val estimateFees: Flow<Boolean> = dataStore.data.map {
        it[KEY_ESTIMATE_FEES] ?: true
    }

    // -------------------------------------------------------------------------
    // Write helpers (suspend — call from coroutine / ViewModel)
    // -------------------------------------------------------------------------

    suspend fun setNetwork(value: String) = dataStore.edit { it[KEY_NETWORK] = value }
    suspend fun setIndexerUrl(value: String) = dataStore.edit { it[KEY_INDEXER_URL] = value }
    suspend fun setKnsApiUrl(value: String) = dataStore.edit { it[KEY_KNS_API_URL] = value }
    suspend fun setKaspaRestUrl(value: String) = dataStore.edit { it[KEY_KASPA_REST_URL] = value }
    suspend fun setHasWallet(value: Boolean) = dataStore.edit { it[KEY_HAS_WALLET] = value }
    suspend fun setActiveAddress(value: String) = dataStore.edit { it[KEY_ACTIVE_ADDRESS] = value }
    suspend fun setEstimateFees(value: Boolean) = dataStore.edit { it[KEY_ESTIMATE_FEES] = value }
}
