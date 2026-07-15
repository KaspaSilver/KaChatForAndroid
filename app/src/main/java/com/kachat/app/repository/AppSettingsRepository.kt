package com.kachat.app.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.kachat.app.models.PendingKnsCommit
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
        const val DEFAULT_INDEXER_URL    = "https://indexer.kasia.fyi"
        const val DEFAULT_KNS_API_URL    = "https://api.knsdomains.org/mainnet/api/v1"
        const val DEFAULT_KASPA_REST_URL = "https://api.kaspa.org"

        // Wallet (just a flag — actual keys live in Keystore)
        val KEY_HAS_WALLET       = booleanPreferencesKey("has_wallet")
        val KEY_ACTIVE_ADDRESS   = stringPreferencesKey("active_address")
        
        val KEY_ESTIMATE_FEES    = booleanPreferencesKey("estimate_fees")
        val KEY_HIDE_AUTO_PAYMENT_CHATS = booleanPreferencesKey("hide_auto_payment_chats")
        val KEY_SHOW_CONTACT_BALANCE = booleanPreferencesKey("show_contact_balance")
        // How hard chat photos get compressed before sending — mirrors iOS's
        // `chatPhotoQualityPreset` setting. Only affects photos sent, not received.
        val KEY_CHAT_PHOTO_QUALITY_PRESET = stringPreferencesKey("chat_photo_quality_preset")
        // Whether photos from contacts you haven't added/messaged yet stay hidden behind a
        // "Show Photo" tap by default — mirrors iOS's `requirePhotoApprovalForNewContacts`.
        // Default true, unlike most toggles here, since this is opt-out protection.
        val KEY_REQUIRE_PHOTO_APPROVAL = booleanPreferencesKey("require_photo_approval_new_contacts")
        // Flat, chain-wide set of txIds the user has manually revealed a hidden photo for —
        // mirrors iOS's `PhotoRevealStore`. Not per-wallet: txIds are unique on-chain already.
        val KEY_REVEALED_PHOTO_TX_IDS = stringSetPreferencesKey("revealed_photo_tx_ids")

        // Notifications — mirrors iOS's notificationMode/sound/vibration settings, minus
        // the remote-push mode (there's no FCM/APNs-equivalent registration wired up yet,
        // see NotificationHelper — only local notifications while the app process is alive).
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIFICATION_SOUND    = booleanPreferencesKey("notification_sound_enabled")
        val KEY_NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration_enabled")

        // System contacts sync — matches iOS's "Sync system contacts"/"Autocreate system contacts".
        val KEY_SYNC_SYSTEM_CONTACTS = booleanPreferencesKey("sync_system_contacts")
        val KEY_AUTOCREATE_SYSTEM_CONTACTS = booleanPreferencesKey("autocreate_system_contacts")

        // A single in-flight KNS commit awaiting its reveal — see PendingKnsCommit.
        val KEY_PENDING_KNS_COMMIT = stringPreferencesKey("pending_kns_commit")

        // Google Drive chat-history backup — off by default, unlike iOS's iCloud sync.
        val KEY_GOOGLE_BACKUP_ENABLED = booleanPreferencesKey("google_backup_enabled")
        val KEY_BACKUP_RETENTION = stringPreferencesKey("backup_retention")

        // Broadcasts — whether the "Popular" tab (curated rooms, see FeaturedBroadcastChannels)
        // shows at all, toggled from the gear icon next to Broadcasts' join button.
        val KEY_BROADCAST_POPULAR_ENABLED = booleanPreferencesKey("broadcast_popular_enabled")
        // Whether senders' KNS profile pictures render in broadcast rooms and are looked up at
        // all, or every sender just shows fallback initials instead (and no lookup happens) —
        // toggled from the gear icon next to Broadcasts' join button. A KNS profile's avatarUrl
        // is an arbitrary attacker-controlled string written via a permissionless on-chain
        // inscription (see updateKnsProfileField), and since the fetch fires just from a message
        // rendering on screen with no tap or other user action, an attacker could otherwise use
        // it as a tracking pixel to learn a viewer's IP/timing/fingerprint just from them opening
        // a channel — this toggle is what gates that.
        val KEY_BROADCAST_SHOW_KNS_AVATARS = booleanPreferencesKey("broadcast_show_kns_avatars")
        // User's custom bottom-tab order (press-and-hold to drag/reorder), comma-joined route
        // strings e.g. "settings,portfolio,chats,swap,profile" — a stringSetPreferencesKey can't
        // be used here since Set has no defined iteration order, and order is the entire point.
        val KEY_TAB_ORDER = stringPreferencesKey("tab_order")
        // Kept in sync with KaChatApp.kt's bottomNavItems default order.
        val DEFAULT_TAB_ORDER = listOf("settings", "portfolio", "chats", "swap", "profile")
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

    val hideAutoCreatedPaymentChats: Flow<Boolean> = dataStore.data.map {
        it[KEY_HIDE_AUTO_PAYMENT_CHATS] ?: false
    }

    val showContactBalance: Flow<Boolean> = dataStore.data.map {
        it[KEY_SHOW_CONTACT_BALANCE] ?: true
    }

    val chatPhotoQualityPreset: Flow<com.kachat.app.models.ChatPhotoQualityPreset> = dataStore.data.map {
        com.kachat.app.models.ChatPhotoQualityPreset.fromName(it[KEY_CHAT_PHOTO_QUALITY_PRESET])
    }

    val requirePhotoApprovalForNewContacts: Flow<Boolean> = dataStore.data.map {
        it[KEY_REQUIRE_PHOTO_APPROVAL] ?: true
    }

    val revealedPhotoTxIds: Flow<Set<String>> = dataStore.data.map {
        it[KEY_REVEALED_PHOTO_TX_IDS] ?: emptySet()
    }

    val broadcastPopularEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_BROADCAST_POPULAR_ENABLED] ?: true
    }

    val broadcastShowKnsAvatars: Flow<Boolean> = dataStore.data.map {
        it[KEY_BROADCAST_SHOW_KNS_AVATARS] ?: true
    }

    /** Route strings, in display order — see KEY_TAB_ORDER. Falls back to the app's default tab order (as defined in KaChatApp.kt's bottomNavItems) until the user first drags a tab. */
    val tabOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_TAB_ORDER]?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TAB_ORDER
    }

    val notificationsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATIONS_ENABLED] ?: true
    }

    val notificationSoundEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATION_SOUND] ?: true
    }

    val notificationVibrationEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_NOTIFICATION_VIBRATION] ?: true
    }

    val syncSystemContactsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_SYNC_SYSTEM_CONTACTS] ?: false
    }

    val autoCreateSystemContactsEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_AUTOCREATE_SYSTEM_CONTACTS] ?: false
    }

    /** Off by default — the user must explicitly turn this on, unlike iOS's iCloud sync. */
    val googleBackupEnabled: Flow<Boolean> = dataStore.data.map {
        it[KEY_GOOGLE_BACKUP_ENABLED] ?: false
    }

    val backupRetention: Flow<com.kachat.app.models.BackupRetention> = dataStore.data.map {
        com.kachat.app.models.BackupRetention.fromName(it[KEY_BACKUP_RETENTION])
    }

    val pendingKnsCommit: Flow<PendingKnsCommit?> = dataStore.data.map { prefs ->
        prefs[KEY_PENDING_KNS_COMMIT]?.let { json ->
            try {
                Gson().fromJson(json, PendingKnsCommit::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * The first moment payment-sync ever ran for this wallet address, so historical
     * payments from before the user started using KaChat never turn into auto-created
     * chats — only payments received after that moment do. Keyed per-address since a
     * device can hold multiple saved accounts, each with its own real payment history.
     */
    fun paymentSyncBaseline(address: String): Flow<Long?> = dataStore.data.map {
        it[paymentSyncBaselineKey(address)]
    }

    /**
     * How far into the `handshakes/by-receiver` stream this wallet has already synced — the
     * indexer's `block_time` cursor, so a sync only asks for what's genuinely new since last time
     * instead of re-fetching the same recent window every cycle. Keyed per-address like
     * [paymentSyncBaseline]. Unlike contextual messages (per-contact-per-alias, so tracked in Room
     * — see [com.kachat.app.models.MessageSyncCursorEntity]), handshakes-by-receiver is a single
     * stream for the whole wallet, so a DataStore value is enough.
     */
    fun handshakeSyncCursor(address: String): Flow<Long?> = dataStore.data.map {
        it[handshakeSyncCursorKey(address)]
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
    suspend fun setHideAutoCreatedPaymentChats(value: Boolean) = dataStore.edit { it[KEY_HIDE_AUTO_PAYMENT_CHATS] = value }
    suspend fun setShowContactBalance(value: Boolean) = dataStore.edit { it[KEY_SHOW_CONTACT_BALANCE] = value }
    suspend fun setChatPhotoQualityPreset(value: com.kachat.app.models.ChatPhotoQualityPreset) = dataStore.edit { it[KEY_CHAT_PHOTO_QUALITY_PRESET] = value.name }
    suspend fun setRequirePhotoApprovalForNewContacts(value: Boolean) = dataStore.edit { it[KEY_REQUIRE_PHOTO_APPROVAL] = value }
    suspend fun revealPhoto(txId: String) = dataStore.edit {
        it[KEY_REVEALED_PHOTO_TX_IDS] = (it[KEY_REVEALED_PHOTO_TX_IDS] ?: emptySet()) + txId
    }
    suspend fun setBroadcastPopularEnabled(value: Boolean) = dataStore.edit { it[KEY_BROADCAST_POPULAR_ENABLED] = value }
    suspend fun setBroadcastShowKnsAvatars(value: Boolean) = dataStore.edit { it[KEY_BROADCAST_SHOW_KNS_AVATARS] = value }
    suspend fun setTabOrder(routes: List<String>) = dataStore.edit { it[KEY_TAB_ORDER] = routes.joinToString(",") }
    suspend fun setNotificationsEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = value }
    suspend fun setNotificationSoundEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATION_SOUND] = value }
    suspend fun setNotificationVibrationEnabled(value: Boolean) = dataStore.edit { it[KEY_NOTIFICATION_VIBRATION] = value }
    suspend fun setSyncSystemContactsEnabled(value: Boolean) = dataStore.edit { it[KEY_SYNC_SYSTEM_CONTACTS] = value }
    suspend fun setGoogleBackupEnabled(value: Boolean) = dataStore.edit { it[KEY_GOOGLE_BACKUP_ENABLED] = value }
    suspend fun setBackupRetention(value: com.kachat.app.models.BackupRetention) = dataStore.edit { it[KEY_BACKUP_RETENTION] = value.name }
    suspend fun setAutoCreateSystemContactsEnabled(value: Boolean) = dataStore.edit { it[KEY_AUTOCREATE_SYSTEM_CONTACTS] = value }
    suspend fun setPendingKnsCommit(commit: PendingKnsCommit) = dataStore.edit { it[KEY_PENDING_KNS_COMMIT] = Gson().toJson(commit) }
    suspend fun clearPendingKnsCommit() = dataStore.edit { it.remove(KEY_PENDING_KNS_COMMIT) }
    suspend fun setPaymentSyncBaseline(address: String, value: Long) = dataStore.edit { it[paymentSyncBaselineKey(address)] = value }
    suspend fun setHandshakeSyncCursor(address: String, value: Long) = dataStore.edit { it[handshakeSyncCursorKey(address)] = value }

    private fun paymentSyncBaselineKey(address: String) = longPreferencesKey("payment_sync_baseline_$address")
    private fun handshakeSyncCursorKey(address: String) = longPreferencesKey("handshake_sync_cursor_$address")
}
