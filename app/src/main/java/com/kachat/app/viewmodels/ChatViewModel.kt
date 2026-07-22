package com.kachat.app.viewmodels

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.BackupRetention
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.services.ChatHistoryExportImportService
import com.kachat.app.services.GoogleDriveBackupService
import com.kachat.app.services.KnsProfileFields
import com.kachat.app.services.KnsService
import com.kachat.app.services.SystemContactsSyncService
import com.kachat.app.services.VoiceRecorderService
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.ImagePrep
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val networkService: com.kachat.app.services.NetworkService,
    private val walletManager: com.kachat.app.services.WalletManager,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
    private val walletService: com.kachat.app.services.WalletService,
    private val notificationHelper: com.kachat.app.services.NotificationHelper,
    private val knsService: KnsService,
    private val systemContactsSyncService: SystemContactsSyncService,
    private val chatHistoryExportImportService: ChatHistoryExportImportService,
    private val diagnosticsExportService: com.kachat.app.services.DiagnosticsExportService,
    private val voiceRecorderService: VoiceRecorderService,
    private val googleDriveBackupService: GoogleDriveBackupService,
    private val groupRepository: com.kachat.app.repository.GroupRepository
) : ViewModel() {

    /** Suppresses a notification for whichever contact's thread is currently open. */
    fun setActiveContact(contactId: String?) {
        notificationHelper.setActiveContact(contactId)
    }

    val chatPhotoQualityPreset: StateFlow<com.kachat.app.models.ChatPhotoQualityPreset> = settings.chatPhotoQualityPreset
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), com.kachat.app.models.ChatPhotoQualityPreset.default)

    fun updateChatPhotoQualityPreset(preset: com.kachat.app.models.ChatPhotoQualityPreset) {
        viewModelScope.launch { settings.setChatPhotoQualityPreset(preset) }
    }

    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), com.kachat.app.models.KaspaExplorer.default)

    fun updateKaspaExplorer(explorer: com.kachat.app.models.KaspaExplorer) {
        viewModelScope.launch { settings.setKaspaExplorer(explorer) }
    }

    val revealedPhotoTxIds: StateFlow<Set<String>> = settings.revealedPhotoTxIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptySet())

    /** Permanently reveals a photo bubble that was hidden behind "Show Photo", by txId. */
    fun revealPhoto(txId: String) {
        viewModelScope.launch { settings.revealPhoto(txId) }
    }

    /** Per-contact override for the "Photos" picker in Chat Info — null clears back to Automatic. */
    fun updateContactPhotoOverride(contactId: String, override: com.kachat.app.models.PhotoAutoDisplayMode?) {
        viewModelScope.launch {
            val existing = getOrCreateContact(contactId)
            chatRepository.addContact(existing.copy(photoAutoDisplayOverride = override?.name))
        }
    }

    /** Per-contact override for the "Incoming Notifications" picker in Chat Info — null clears back to Default. */
    fun updateContactNotificationOverride(contactId: String, override: com.kachat.app.models.ContactNotificationMode?) {
        viewModelScope.launch {
            val existing = getOrCreateContact(contactId)
            chatRepository.addContact(existing.copy(notificationOverride = override?.name))
        }
    }

    // -------------------------------------------------------------------------
    // Chat history export/import — matches iOS's plaintext JSON archive, scoped to whichever
    // account is active. Export hands a file off to the share sheet; import always merges,
    // never wipes existing local data.
    // -------------------------------------------------------------------------

    enum class ChatHistoryOpStatus { IDLE, IN_PROGRESS, SUCCESS, FAILED }
    data class ChatHistoryOpState(val status: ChatHistoryOpStatus = ChatHistoryOpStatus.IDLE, val message: String? = null)

    private val _exportState = MutableStateFlow(ChatHistoryOpState())
    val exportState: StateFlow<ChatHistoryOpState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow(ChatHistoryOpState())
    val importState: StateFlow<ChatHistoryOpState> = _importState.asStateFlow()

    /** Builds the export file, then hands its content:// URI to [onReady] (the caller launches the share sheet — that's a UI concern, not this ViewModel's). */
    fun exportChatHistory(onReady: (Uri) -> Unit) {
        if (_exportState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _exportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                val uri = chatHistoryExportImportService.exportChatHistory()
                _exportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.SUCCESS)
                onReady(uri)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Chat history export failed", e)
                _exportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = e.message ?: "Export failed")
            }
        }
    }

    private val _diagnosticsExportState = MutableStateFlow(ChatHistoryOpState())
    val diagnosticsExportState: StateFlow<ChatHistoryOpState> = _diagnosticsExportState.asStateFlow()

    /** Builds the diagnostics zip, then hands its content:// URI to [onReady] — see DiagnosticsExportService. */
    fun exportDiagnostics(onReady: (Uri) -> Unit) {
        if (_diagnosticsExportState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _diagnosticsExportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                val uri = diagnosticsExportService.exportDiagnostics()
                _diagnosticsExportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.SUCCESS)
                onReady(uri)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Diagnostics export failed", e)
                _diagnosticsExportState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = e.message ?: "Export failed")
            }
        }
    }

    fun importChatHistory(uri: Uri) {
        if (_importState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _importState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                val result = chatHistoryExportImportService.importChatHistory(uri)
                _importState.value = ChatHistoryOpState(
                    status = ChatHistoryOpStatus.SUCCESS,
                    message = "Imported ${result.importedMessageCount} messages from ${result.conversationCount} chats."
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Chat history import failed", e)
                _importState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = e.message ?: "Import failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Google Drive backup — reuses the same JSON archive + merge logic as local
    // export/import, just with Drive's appDataFolder as the transport. Off by default;
    // restore is always manual (never triggered automatically on sign-in/foreground).
    // -------------------------------------------------------------------------

    enum class GoogleBackupOpStatus { IDLE, IN_PROGRESS, SUCCESS, FAILED }
    data class GoogleBackupUiState(
        val enabled: Boolean = false,
        val signedInEmail: String? = null,
        val status: GoogleBackupOpStatus = GoogleBackupOpStatus.IDLE,
        val message: String? = null
    )

    val googleBackupEnabled: StateFlow<Boolean> = settings.googleBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val backupRetention: StateFlow<BackupRetention> = settings.backupRetention
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), BackupRetention.FOREVER)

    private val _googleBackupOpState = MutableStateFlow(GoogleBackupUiState())
    val googleBackupOpState: StateFlow<GoogleBackupUiState> = _googleBackupOpState.asStateFlow()

    private val _restoreState = MutableStateFlow(ChatHistoryOpState())
    val restoreState: StateFlow<ChatHistoryOpState> = _restoreState.asStateFlow()

    /** One-shot: the UI observes this and launches the intent via `ActivityResultContracts.StartIntentSenderForResult()` when non-null, then calls [consentIntentLaunched] and [completeGoogleDriveAuthorization]. */
    private val _pendingConsentIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingConsentIntent: StateFlow<PendingIntent?> = _pendingConsentIntent.asStateFlow()

    fun consentIntentLaunched() {
        _pendingConsentIntent.value = null
    }

    /** Sign-in + Drive authorization. May pause at [pendingConsentIntent] for first-time consent — see [completeGoogleDriveAuthorization]. */
    fun enableGoogleDriveBackup(activity: Activity) {
        if (_googleBackupOpState.value.status == GoogleBackupOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _googleBackupOpState.value = GoogleBackupUiState(status = GoogleBackupOpStatus.IN_PROGRESS)
            val signedIn = try {
                googleDriveBackupService.signIn(activity)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Google sign-in failed", e)
                false
            }
            if (!signedIn) {
                _googleBackupOpState.value = GoogleBackupUiState(status = GoogleBackupOpStatus.FAILED, message = "Sign-in failed or was cancelled")
                return@launch
            }
            when (val outcome = googleDriveBackupService.requestAuthorization(activity)) {
                is GoogleDriveBackupService.AuthOutcome.Success -> finishEnablingBackup()
                is GoogleDriveBackupService.AuthOutcome.NeedsConsent -> _pendingConsentIntent.value = outcome.pendingIntent
                is GoogleDriveBackupService.AuthOutcome.Failed ->
                    _googleBackupOpState.value = GoogleBackupUiState(status = GoogleBackupOpStatus.FAILED, message = outcome.message)
            }
        }
    }

    /** Call after the UI launches [pendingConsentIntent] and gets a result back. */
    fun completeGoogleDriveAuthorization(intent: Intent) {
        viewModelScope.launch {
            if (googleDriveBackupService.completeAuthorization(intent)) {
                finishEnablingBackup()
            } else {
                _googleBackupOpState.value = GoogleBackupUiState(status = GoogleBackupOpStatus.FAILED, message = "Drive authorization was cancelled or denied")
            }
        }
    }

    private suspend fun finishEnablingBackup() {
        settings.setGoogleBackupEnabled(true)
        _googleBackupOpState.value = GoogleBackupUiState(
            enabled = true,
            signedInEmail = googleDriveBackupService.signedInAccountEmail,
            status = GoogleBackupOpStatus.SUCCESS
        )
    }

    /** Turns off automatic backup — does not delete the existing Drive file, just stops future uploads. */
    fun disableGoogleDriveBackup() {
        viewModelScope.launch {
            settings.setGoogleBackupEnabled(false)
            googleDriveBackupService.signOut()
            _googleBackupOpState.value = GoogleBackupUiState()
        }
    }

    fun setBackupRetention(retention: BackupRetention) {
        viewModelScope.launch { settings.setBackupRetention(retention) }
    }

    fun backupNow() {
        if (_googleBackupOpState.value.status == GoogleBackupOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _googleBackupOpState.value = _googleBackupOpState.value.copy(status = GoogleBackupOpStatus.IN_PROGRESS)
            try {
                val json = chatHistoryExportImportService.buildArchiveJson()
                val success = googleDriveBackupService.uploadBackup(walletManager.getAddress(), json)
                _googleBackupOpState.value = _googleBackupOpState.value.copy(
                    status = if (success) GoogleBackupOpStatus.SUCCESS else GoogleBackupOpStatus.FAILED,
                    message = if (success) "Backed up just now" else "Backup failed"
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Google Drive backup failed", e)
                _googleBackupOpState.value = _googleBackupOpState.value.copy(status = GoogleBackupOpStatus.FAILED, message = e.message ?: "Backup failed")
            }
        }
    }

    /** Manual only — never triggered automatically. Merges into local data via the same logic as local file import. */
    fun restoreFromGoogleDrive() {
        if (_restoreState.value.status == ChatHistoryOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _restoreState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.IN_PROGRESS)
            try {
                val json = googleDriveBackupService.downloadBackup(walletManager.getAddress())
                    ?: throw IllegalStateException("No Google Drive backup found")
                val result = chatHistoryExportImportService.importChatHistory(json)
                _restoreState.value = ChatHistoryOpState(
                    status = ChatHistoryOpStatus.SUCCESS,
                    message = "Imported ${result.importedMessageCount} messages from ${result.conversationCount} chats."
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Google Drive restore failed", e)
                _restoreState.value = ChatHistoryOpState(status = ChatHistoryOpStatus.FAILED, message = e.message ?: "Restore failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Storage — mirrors iOS's "Local storage used" / "iCloud storage used" split in Settings.
    // Android has no live per-record cloud sync (Google Drive backup is one flat JSON file per
    // account instead of iOS's continuous CloudKit mirroring), so "cloud" here just means that
    // one backup file's current size in Drive, not a per-message tally.
    // -------------------------------------------------------------------------

    private val _localStorageSizeBytes = MutableStateFlow<Long?>(null)
    val localStorageSizeBytes: StateFlow<Long?> = _localStorageSizeBytes.asStateFlow()

    /** Room's `kachat.db` file (+ `-wal`/`-shm` sidecars in WAL mode) on local disk — free, no network. */
    fun refreshLocalStorageSize() {
        val dbFile = appContext.getDatabasePath("kachat.db")
        var total = dbFile.length()
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = java.io.File(dbFile.parentFile, dbFile.name + suffix)
            if (sidecar.exists()) total += sidecar.length()
        }
        _localStorageSizeBytes.value = total
    }

    enum class DriveSizeStatus { IDLE, LOADING, LOADED, FAILED }
    data class DriveSizeState(val status: DriveSizeStatus = DriveSizeStatus.IDLE, val bytes: Long? = null)

    private val _driveBackupSizeState = MutableStateFlow(DriveSizeState())
    val driveBackupSizeState: StateFlow<DriveSizeState> = _driveBackupSizeState.asStateFlow()

    /** Live Drive API call - unlike [refreshLocalStorageSize], not run automatically since it costs a network request. */
    fun refreshDriveBackupSize() {
        if (_driveBackupSizeState.value.status == DriveSizeStatus.LOADING) return
        viewModelScope.launch {
            _driveBackupSizeState.value = DriveSizeState(status = DriveSizeStatus.LOADING)
            val bytes = try {
                googleDriveBackupService.currentBackupSizeBytes(walletManager.getAddress())
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to check Google Drive backup size", e)
                null
            }
            _driveBackupSizeState.value = if (bytes != null) {
                DriveSizeState(status = DriveSizeStatus.LOADED, bytes = bytes)
            } else {
                DriveSizeState(status = DriveSizeStatus.FAILED)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Danger Zone — matches iOS's three destructive settings actions exactly in scope
    // (SettingsView.swift:216-295): all three act on the currently active account only, never
    // other saved accounts on the device.
    // -------------------------------------------------------------------------

    enum class DangerZoneOpStatus { IDLE, IN_PROGRESS, SUCCESS, FAILED }
    data class DangerZoneOpState(val status: DangerZoneOpStatus = DangerZoneOpStatus.IDLE, val message: String? = null)

    private val _wipeIncomingState = MutableStateFlow(DangerZoneOpState())
    val wipeIncomingState: StateFlow<DangerZoneOpState> = _wipeIncomingState.asStateFlow()

    /** Deletes only incoming messages for the active account, then re-syncs full history from the blockchain — sent messages, contacts, and the wallet's keys are untouched. */
    fun wipeIncomingMessages() {
        if (_wipeIncomingState.value.status == DangerZoneOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _wipeIncomingState.value = DangerZoneOpState(status = DangerZoneOpStatus.IN_PROGRESS)
            try {
                chatRepository.wipeIncomingMessagesAndResync()
                _wipeIncomingState.value = DangerZoneOpState(status = DangerZoneOpStatus.SUCCESS, message = "Incoming messages wiped. Re-syncing from the blockchain.")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Wipe incoming messages failed", e)
                _wipeIncomingState.value = DangerZoneOpState(status = DangerZoneOpStatus.FAILED, message = e.message ?: "Failed")
            }
        }
    }

    fun resetWipeIncomingState() {
        _wipeIncomingState.value = DangerZoneOpState()
    }

    private val _wipeAccountState = MutableStateFlow(DangerZoneOpState())
    val wipeAccountState: StateFlow<DangerZoneOpState> = _wipeAccountState.asStateFlow()

    /**
     * Wipes all local chat data (messages + contacts) for [address], and if [alsoDeleteCloud] is
     * true, also deletes the Google Drive backup file and signs out of Drive backup entirely.
     * Does NOT delete the wallet's keys — that's a separate, synchronous step
     * ([WalletManager.deleteAccount] via [WalletViewModel.deleteWallet]) the caller must run via
     * [onLocalWipeComplete] once this finishes, since key deletion and the resulting
     * logged-in/logged-out state transition live in WalletViewModel, not here.
     */
    fun wipeAccountAndMessages(address: String, alsoDeleteCloud: Boolean, onLocalWipeComplete: () -> Unit) {
        if (_wipeAccountState.value.status == DangerZoneOpStatus.IN_PROGRESS) return
        viewModelScope.launch {
            _wipeAccountState.value = DangerZoneOpState(status = DangerZoneOpStatus.IN_PROGRESS)
            try {
                chatRepository.wipeAllLocalDataForAddress(address)
                groupRepository.clearAllLocalData(address)
                if (alsoDeleteCloud) {
                    googleDriveBackupService.deleteBackup(address)
                    settings.setGoogleBackupEnabled(false)
                    googleDriveBackupService.signOut()
                    _googleBackupOpState.value = GoogleBackupUiState()
                }
                _wipeAccountState.value = DangerZoneOpState(status = DangerZoneOpStatus.SUCCESS)
                onLocalWipeComplete()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Wipe account failed", e)
                _wipeAccountState.value = DangerZoneOpState(status = DangerZoneOpStatus.FAILED, message = e.message ?: "Failed")
            }
        }
    }

    fun resetWipeAccountState() {
        _wipeAccountState.value = DangerZoneOpState()
    }

    // Auto-created payment chats are always shown — no toggle to hide them.
    val conversations: StateFlow<List<Conversation>> = combine(
        chatRepository.getContacts(),
        chatRepository.getLatestMessages(),
        chatRepository.getUnreadCounts()
    ) { contacts, latestMessages, unreadCounts ->
        val latestByContact = latestMessages.associateBy { it.contactId }
        val unreadByContact = unreadCounts.associateBy({ it.contactId }, { it.count })
        contacts
            .map { contact ->
                Conversation(contact, latestByContact[contact.id], unreadByContact[contact.id] ?: 0)
            }.sortedByDescending { it.lastMessage?.blockTimestamp ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * address -> cached KNS avatar URL, for group chat's per-sender avatars - group members are
     * always saved contacts (created automatically when added to a group), so this reuses the
     * same [com.kachat.app.models.ContactEntity.knsAvatarUrl] caching 1:1 chat avatars already
     * rely on, rather than broadcast's separate anonymous-sender KNS lookup path.
     */
    val contactAvatarsByAddress: StateFlow<Map<String, String?>> = chatRepository.getContacts()
        .map { contacts -> contacts.associateBy({ it.id }, { it.knsAvatarUrl }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** address -> live contact alias (KNS-resolved name or custom nickname), for group chat's sender labels - see [contactAvatarsByAddress]. */
    val contactAliasesByAddress: StateFlow<Map<String, String>> = chatRepository.getContacts()
        .map { contacts -> contacts.mapNotNull { c -> c.alias?.takeIf { it.isNotBlank() }?.let { c.id to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Fetches KNS name + avatar for every member of a group - group rosters cache a `displayName`
     * snapshot taken at add/join time (`GroupMember.displayName`), which never reflects a KNS name
     * resolved afterward, so the thread/info screens call this on appear the same way
     * [refreshKnsNamesForAllContacts] runs for the chat list. `refreshKnsProfile` works for any
     * address (creates no contact row itself), so this is safe even for members added by address/QR
     * with no KNS domain.
     */
    fun refreshKnsProfilesForGroupMembers(addresses: List<String>) {
        refreshKnsNamesForAllContacts()
        for (address in addresses) {
            refreshKnsProfile(address)
        }
    }

    fun markAsRead(contactId: String) {
        viewModelScope.launch { chatRepository.markAsRead(contactId) }
    }

    fun markAsUnread(contactId: String) {
        viewModelScope.launch { chatRepository.markAsUnread(contactId) }
    }

    /** Chat-list multi-select bulk actions. */
    fun markContactsAsRead(contactIds: Collection<String>) {
        viewModelScope.launch { contactIds.forEach { chatRepository.markAsRead(it) } }
    }

    fun markContactsAsUnread(contactIds: Collection<String>) {
        viewModelScope.launch { contactIds.forEach { chatRepository.markAsUnread(it) } }
    }

    /** Permanently deletes the chat (contact + all local messages) — see ChatRepository.deleteChat. */
    fun deleteChat(contactId: String) {
        viewModelScope.launch { chatRepository.deleteChat(contactId) }
    }

    /** Sends a real reciprocal handshake and activates the conversation. */
    fun acceptHandshake(contactId: String) {
        viewModelScope.launch {
            try {
                walletService.acceptHandshake(contactId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error accepting handshake", e)
            }
        }
    }

    /** Manually starts a conversation by sending an initial handshake — the hand icon in a fresh chat. */
    fun sendHandshake(contactId: String) {
        viewModelScope.launch {
            try {
                walletService.sendHandshakeToNewContact(contactId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending handshake", e)
            }
        }
    }

    /** Purely local — no transaction sent. Declining fully deletes the contact (see [deleteChat]) — the same as any other delete, they'd need a fresh handshake to reach you again. */
    fun declineHandshake(contactId: String) {
        viewModelScope.launch { chatRepository.deleteChat(contactId) }
    }

    /**
     * A broadcast sender viewed via "User Info" isn't necessarily a saved 1:1 contact yet — unlike
     * [addContact] (the real "add/import" flow, with its primary-domain auto-fill side effect),
     * this is just a bare local row so per-contact fields (name/photo/notification overrides,
     * chosen KNS domain) have somewhere to persist to the first time one of them is edited.
     */
    private suspend fun getOrCreateContact(contactId: String): ContactEntity {
        return chatRepository.getContact(contactId) ?: ContactEntity(
            id = contactId,
            walletAddress = walletManager.getAddress(),
            alias = null,
            knsName = null,
            publicKeyHex = null
        )
    }

    fun updateContactName(contactId: String, newName: String) {
        viewModelScope.launch {
            val existing = getOrCreateContact(contactId)
            val updated = existing.copy(alias = if (newName.isBlank()) null else newName)
            chatRepository.addContact(updated)
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshChats() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                chatRepository.syncMessages()
                walletService.refreshBalance()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error refreshing chats", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // Messages/photos/voice notes are identity-address self-stashes; "Pay in Kaspa" sources
    // from the spending address instead (see WalletManager's spending-address doc comment) —
    // kept as two separate UTXO sets so the live fee preview below prices each correctly rather
    // than one silently reusing the other's (possibly empty, possibly wrong-balance) UTXOs.
    private val _currentUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>>(emptyList())
    val currentUtxos: StateFlow<List<com.kachat.app.services.UtxoEntry>> = _currentUtxos.asStateFlow()

    private val _spendingUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>>(emptyList())

    private val _paymentAmount = MutableStateFlow("")
    val paymentAmount: StateFlow<String> = _paymentAmount.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _networkFeeRate = MutableStateFlow(com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()) // sompi per mass-gram
    val networkFeeRate: StateFlow<Double> = _networkFeeRate.asStateFlow()

    // User-adjustable override for a busy fee market — set via the composer's clickable fee pill.
    // Applies to whatever's sent next (message/photo/voice/payment) and clears itself afterward so
    // a stale manual bump doesn't silently carry over to an unrelated later send.
    private val _feeRateOverride = MutableStateFlow<Long?>(null)
    val feeRateOverride: StateFlow<Long?> = _feeRateOverride.asStateFlow()

    fun setFeeRateOverride(rate: Long?) {
        _feeRateOverride.value = rate
    }

    enum class VoiceRecordingStatus { IDLE, RECORDING }
    data class VoiceRecordingState(val status: VoiceRecordingStatus = VoiceRecordingStatus.IDLE, val elapsedMs: Long = 0L)

    private val _voiceRecordingState = MutableStateFlow(VoiceRecordingState())
    val voiceRecordingState: StateFlow<VoiceRecordingState> = _voiceRecordingState.asStateFlow()

    private val _pendingPhotoUri = MutableStateFlow<Uri?>(null)
    /** A picked-but-not-yet-sent chat photo, staged for preview (thumbnail + fee) before the user confirms send. */
    val pendingPhotoUri: StateFlow<Uri?> = _pendingPhotoUri.asStateFlow()

    fun setPendingPhoto(uri: Uri?) {
        _pendingPhotoUri.value = uri
    }

    fun cancelPendingPhoto() {
        _pendingPhotoUri.value = null
    }

    // Group chat's own photo-staging/voice-recording state - kept separate from the 1:1 fields
    // above (rather than reused) since both screens share this same ViewModel instance and a
    // photo staged on one screen bleeding into the other on a quick navigation would be a real bug.
    private val _groupVoiceRecordingState = MutableStateFlow(VoiceRecordingState())
    val groupVoiceRecordingState: StateFlow<VoiceRecordingState> = _groupVoiceRecordingState.asStateFlow()

    private val _groupPendingPhotoUri = MutableStateFlow<Uri?>(null)
    val groupPendingPhotoUri: StateFlow<Uri?> = _groupPendingPhotoUri.asStateFlow()

    fun setGroupPendingPhoto(uri: Uri?) {
        _groupPendingPhotoUri.value = uri
    }

    fun cancelGroupPendingPhoto() {
        _groupPendingPhotoUri.value = null
    }

    /**
     * The payload byte count to price the live fee preview off of: the real typed-text length
     * while composing, a rough elapsed-time-based estimate of the final encoded/encrypted size
     * while recording a voice message, or a rough estimate of the final wire size for a staged
     * photo — same shape as [VoiceMessage.estimatedWirePayloadSize]: the real send always measures
     * the actual encoded bytes exactly, this is only ever used for the live preview.
     */
    private val previewPayloadSize: Flow<Int> = combine(_messageText, voiceRecordingState, pendingPhotoUri, chatPhotoQualityPreset) { text, recording, photoUri, photoQuality ->
        if (recording.status == VoiceRecordingStatus.RECORDING) {
            VoiceMessage.estimatedWirePayloadSize(recording.elapsedMs)
        } else if (photoUri != null) {
            // Compressed image bytes -> inner base64 (+33%) -> JSON envelope overhead -> encryption
            // + outer base64 (+33%) -- rough multiplier, calibrated the same way VoiceMessage's
            // estimate is: never used for the real fee, only this live preview.
            (photoQuality.targetBytes * 1.33 * 1.33).toInt() + 150
        } else {
            text.toByteArray().size
        }
    }

    private val utxosForFeeEstimate: Flow<Pair<List<com.kachat.app.services.UtxoEntry>, List<com.kachat.app.services.UtxoEntry>>> =
        combine(_currentUtxos, _spendingUtxos) { identity, spending -> identity to spending }

    val estimatedFeeSompi: StateFlow<Long?> = combine(paymentAmount, previewPayloadSize, utxosForFeeEstimate, _networkFeeRate, _feeRateOverride) { amount, textPayloadSize, utxosPair, networkRate, overrideRate ->
        val rate = overrideRate?.toDouble() ?: networkRate
        if (amount.isEmpty() && textPayloadSize == 0) return@combine null

        val isPayment = amount.isNotEmpty()
        val utxos = if (isPayment) utxosPair.second else utxosPair.first
        val sompiNeeded = if (isPayment) {
            (amount.toDoubleOrNull() ?: 0.0) * 100_000_000
        } else {
            0.0
        }.toLong()
        
        var total = 0L
        var count = 0
        for (utxo in utxos) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= sompiNeeded + 1000) break // Buffer for fee
        }
        
        if (total < sompiNeeded && isPayment) return@combine null
        
        val payloadSize = if (isPayment) "Sent $amount KAS".toByteArray().size else textPayloadSize

        // Preview only — a payment gets 2 standard 34-byte P2PK outputs (recipient + change).
        // A message (isPayment=false) is a zero-amount self-stash send, and KaspaWalletEngine
        // skips the zero-value recipient output for those (a 0-value output is non-standard and
        // gets rejected) — matches iOS's estimateContextualMessageFee, which also prices a
        // message off a single output. Assuming 2 outputs here previously overpriced every
        // message/voice/photo preview by a phantom output (~412 mass, ~0.0004 KAS at min rate).
        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = count.coerceAtLeast(1),
            outputScriptLens = if (isPayment) listOf(34, 34) else listOf(34),
            payloadSize = payloadSize
        )
        com.kachat.app.util.KaspaMass.calculateFee(mass, rate.toLong())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    // -------------------------------------------------------------------------
    // Group chat's own live fee preview - same KaspaMass calc as [estimatedFeeSompi] above, but
    // sized for gcomm's wire format (see estimatedGroupWirePayloadSize) instead of 1:1's plain
    // ECIES envelope. The composer's text field is local Compose state (unlike 1:1's ViewModel-
    // owned messageText), so setGroupMessageText bridges it in via LaunchedEffect.
    // -------------------------------------------------------------------------

    private val _groupMessageText = MutableStateFlow("")

    fun setGroupMessageText(text: String) {
        _groupMessageText.value = text
    }

    /**
     * Raw bytes -> gcomm wire size: for media (photo/audio), raw bytes -> base64 (+33%) in the
     * JSON envelope (+150 bytes overhead) first; either way, that content is then ChaCha20-Poly1305
     * encrypted (+16 byte tag) and the whole ciphertext is hex-encoded (2x) for the wire, plus a
     * fixed ~370 bytes of hex-encoded overhead (blinded_group_id/sender_id/sender_pub/msg_id/
     * signature) that 1:1's plain-ECIES envelope doesn't carry. Matches iOS's
     * GroupChatService.estimatedGroupWirePayloadSize. Preview only - the real send measures exactly.
     */
    private fun estimatedGroupWirePayloadSize(rawBytes: Int, isMediaEnvelope: Boolean): Int {
        val innerBytes = if (isMediaEnvelope) (rawBytes * 1.33).toInt() + 150 else rawBytes
        val ciphertextHexBytes = (innerBytes + 16) * 2
        return ciphertextHexBytes + 370
    }

    /** Raw encoded-Opus-bytes/sec for [VoiceRecorderService]'s fixed 6kbps/48kHz config (bitrate/8 + WebM container overhead) - same heuristic as iOS's matching estimate for the same encoder settings. */
    private fun estimatedGroupAudioRawBytes(elapsedMs: Long): Int {
        val elapsedSeconds = elapsedMs / 1000.0
        return (elapsedSeconds * 1_150.0).toInt()
    }

    private val groupPreviewPayloadSize: Flow<Int> = combine(_groupMessageText, groupVoiceRecordingState, groupPendingPhotoUri) { text, recording, photoUri ->
        when {
            recording.status == VoiceRecordingStatus.RECORDING -> estimatedGroupWirePayloadSize(estimatedGroupAudioRawBytes(recording.elapsedMs), isMediaEnvelope = true)
            photoUri != null -> estimatedGroupWirePayloadSize(GROUP_PHOTO_TARGET_BYTES, isMediaEnvelope = true)
            else -> estimatedGroupWirePayloadSize(text.toByteArray().size, isMediaEnvelope = false)
        }
    }

    val groupEstimatedFeeSompi: StateFlow<Long?> = combine(groupPreviewPayloadSize, _currentUtxos, _networkFeeRate, _feeRateOverride) { payloadSize, utxos, networkRate, overrideRate ->
        val rate = overrideRate?.toDouble() ?: networkRate
        if (payloadSize <= 372) return@combine null // 370 fixed overhead + 2 for an empty ciphertext -> nothing actually staged/typed yet

        var total = 0L
        var count = 0
        for (utxo in utxos) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= 1000) break
        }

        val mass = com.kachat.app.util.KaspaMass.calculateMass(
            numInputs = count.coerceAtLeast(1),
            outputScriptLens = listOf(34),
            payloadSize = payloadSize
        )
        com.kachat.app.util.KaspaMass.calculateFee(mass, rate.toLong())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun setMessageText(text: String) {
        _messageText.value = text
    }

    fun setPaymentAmount(amount: String) {
        _paymentAmount.value = amount
    }

    fun refreshUtxos() {
        viewModelScope.launch {
            try {
                val address = walletManager.getAddress()
                val api = networkService.kaspaRestApi.value ?: return@launch
                
                // Refresh fee rate from network
                try {
                    val feeInfo = api.getFeeEstimate()
                    _networkFeeRate.value = feeInfo.normalBuckets.firstOrNull()?.feerate
                        ?: com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Could not fetch fee estimate, using network minimum")
                }

                _currentUtxos.value = api.getUtxos(address)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Same as [refreshUtxos] but for the spending address — "Pay in Kaspa"'s live fee preview needs its UTXOs, not the identity address's. */
    fun refreshSpendingUtxos() {
        viewModelScope.launch {
            try {
                val address = walletManager.currentSpendingAddress()
                val api = networkService.kaspaRestApi.value ?: return@launch

                try {
                    val feeInfo = api.getFeeEstimate()
                    _networkFeeRate.value = feeInfo.normalBuckets.firstOrNull()?.feerate
                        ?: com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Could not fetch fee estimate, using network minimum")
                }

                _spendingUtxos.value = api.getUtxos(address)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * @param address Must always be a real kaspa: address — never a raw KNS domain
     * string, since it's used as the contact's primary key everywhere (sends,
     * encryption, etc.). Resolve a domain to its owning address first (see
     * [onCreateChatAddressChanged]) and pass the result here.
     * @param knsName The domain name to display, if this contact was added via KNS.
     */
    fun addContact(address: String, name: String?, knsName: String? = null) {
        viewModelScope.launch {
            val existing = chatRepository.getContact(address)
            if (existing != null) {
                // If contact exists, update name if provided
                val updated = existing.copy(
                    alias = if (name.isNullOrBlank()) existing.alias else name,
                    knsName = knsName ?: existing.knsName
                )
                chatRepository.addContact(updated)
            } else {
                val newContact = ContactEntity(
                    id = address,
                    walletAddress = walletManager.getAddress(),
                    alias = if (name.isNullOrBlank()) null else name,
                    knsName = knsName,
                    publicKeyHex = null
                )
                chatRepository.addContact(newContact)
            }

            // Added by raw address, no domain typed and no explicit name given — try to
            // auto-detect their primary KNS domain and use it as the display name,
            // matching iOS ContactsManager's auto-fill-alias-from-primary-domain behavior.
            if (knsName == null && name.isNullOrBlank()) {
                val primary = knsService.reverseResolve(address)
                if (primary != null) {
                    chatRepository.getContact(address)?.let { current ->
                        chatRepository.addContact(current.copy(alias = primary, knsName = primary))
                    }
                }
            }
            refreshKnsProfile(address)
        }
    }

    // -------------------------------------------------------------------------
    // Group chats
    // -------------------------------------------------------------------------

    val groups = groupRepository.getGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * One-shot KNS resolve for a single group-member address row - unlike
     * [onCreateChatAddressChanged]/[knsResolvedAddress] (single shared StateFlow, fine for the
     * one-address Create Chat flow), the group member list can have up to 10 rows resolving
     * concurrently, so each row owns its own debounce/resolving state locally in Compose and
     * just calls this directly.
     */
    suspend fun resolveKnsDomain(domain: String): String? = knsService.resolve(domain)

    private val _isCreatingGroup = MutableStateFlow(false)
    val isCreatingGroup: StateFlow<Boolean> = _isCreatingGroup.asStateFlow()

    private val _createGroupError = MutableStateFlow<String?>(null)
    val createGroupError: StateFlow<String?> = _createGroupError.asStateFlow()

    fun clearCreateGroupError() {
        _createGroupError.value = null
    }

    /** Any address not already a contact is auto-added, matching [addContact]'s own-or-create behavior. */
    fun createGroupChat(name: String, addresses: List<String>, onCreated: (String) -> Unit) {
        val trimmedName = name.trim()
        val trimmedAddresses = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedName.isEmpty()) {
            _createGroupError.value = "Enter a group name."
            return
        }
        if (trimmedAddresses.isEmpty()) {
            _createGroupError.value = "Add at least one address."
            return
        }
        val invalid = trimmedAddresses.firstOrNull { !com.kachat.app.util.KaspaAddress.isValid(it) }
        if (invalid != null) {
            _createGroupError.value = "Invalid address: $invalid"
            return
        }

        _isCreatingGroup.value = true
        _createGroupError.value = null
        viewModelScope.launch {
            try {
                val contacts = trimmedAddresses.map { address ->
                    chatRepository.getContact(address) ?: ContactEntity(
                        id = address,
                        walletAddress = walletManager.getAddress(),
                        alias = null,
                        knsName = null,
                        publicKeyHex = null
                    ).also { chatRepository.addContact(it) }
                }
                val group = groupRepository.createGroup(trimmedName, contacts)
                _isCreatingGroup.value = false
                onCreated(group.groupId)
            } catch (e: Exception) {
                _isCreatingGroup.value = false
                _createGroupError.value = e.message ?: "Failed to create group"
            }
        }
    }

    fun getGroupMessages(groupId: String) = groupRepository.getMessages(groupId)

    fun sendGroupMessage(text: String, groupId: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                groupRepository.sendGroupMessage(text, groupId)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to send")
            }
        }
    }

    fun addGroupMember(contact: ContactEntity, groupId: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                groupRepository.addMember(contact, groupId)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    fun removeGroupMember(member: com.kachat.app.models.GroupMember, groupId: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                groupRepository.removeMember(member, groupId)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    fun deleteGroupChat(groupId: String) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId)
        }
    }

    /**
     * Group messages don't have an in-place "retry" record the way 1:1 does - resends the same
     * content (works uniformly for text/photo/audio, since all three are just a content string)
     * as a fresh message rather than mutating the failed one, which stays in history marked failed.
     */
    fun retryGroupMessage(groupId: String, content: String) {
        viewModelScope.launch {
            try {
                groupRepository.sendGroupMessage(content, groupId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error retrying group message", e)
            }
        }
    }

    /** "Donate" from Settings -> About: resolves the app's donation KNS domain and hands back its address so the caller can navigate straight into a chat, pre-armed to send a payment. */
    fun startDonationChat(onResolved: (String) -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            val address = knsService.resolve(DONATION_KNS_DOMAIN)
            if (address == null) {
                onError()
                return@launch
            }
            addContact(address = address, name = null, knsName = DONATION_KNS_DOMAIN)
            onResolved(address)
        }
    }

    data class KnsProfileUiState(
        val ownedDomains: List<String> = emptyList(),
        val selectedDomain: String? = null,
        val profile: KnsProfileFields? = null
    )

    private val _knsProfiles = MutableStateFlow<Map<String, KnsProfileUiState>>(emptyMap())
    val knsProfiles: StateFlow<Map<String, KnsProfileUiState>> = _knsProfiles.asStateFlow()

    /**
     * Fetches this address's owned KNS domains + the active one's profile (avatar/bio/socials).
     * Works for any address, not just a saved 1:1 contact — a broadcast sender viewed via "User
     * Info" may never have a [ContactEntity] row at all, and their KNS profile should still show.
     */
    fun refreshKnsProfile(contactId: String) {
        viewModelScope.launch {
            val contact = chatRepository.getContact(contactId)
            val ownedAssets = knsService.getOwnedDomains(contactId)
            val ownedNames = ownedAssets.mapNotNull { it.asset }

            if (ownedNames.isEmpty()) {
                _knsProfiles.update { it + (contactId to KnsProfileUiState()) }
                return@launch
            }

            val primary = knsService.reverseResolve(contactId)
            val activeName = KnsService.pickActiveDomain(ownedNames, contact?.knsName, primary)
            val activeAsset = ownedAssets.firstOrNull { it.asset == activeName }
            val profile = activeAsset?.assetId?.let { knsService.getProfile(it) }

            _knsProfiles.update { it + (contactId to KnsProfileUiState(ownedNames, activeName, profile)) }

            // Keep the chat list's cached avatar current — only meaningful for an actual saved contact.
            if (contact != null && profile?.avatarUrl != contact.knsAvatarUrl) {
                chatRepository.addContact(contact.copy(knsAvatarUrl = profile?.avatarUrl))
            }
        }
    }

    /**
     * User picked a different owned domain in Chat Info to represent this contact — an explicit
     * choice, so it always updates the displayed name too (unlike the passive KNS auto-rename in
     * [refreshKnsNamesForAllContacts], which backs off from a real custom nickname).
     */
    fun selectKnsDomain(contactId: String, domain: String) {
        viewModelScope.launch {
            val contact = getOrCreateContact(contactId)
            chatRepository.addContact(contact.copy(knsName = domain, alias = domain))
            refreshKnsProfile(contactId)
        }
    }

    /**
     * Auto-updates each contact's display name to their primary KNS domain, if they
     * have one — matches iOS's fetchKNSDomainsForAllContacts, run whenever the chat
     * list appears. Never overwrites a real custom nickname the user typed in, never
     * overwrites a contact linked to a system (phone) contact, and never overwrites a
     * domain the user explicitly picked in Chat Info (tracked via `knsName` — once set,
     * that choice is pinned until the user changes it themselves, even if the contact's
     * on-chain primary is or becomes something else).
     */
    fun refreshKnsNamesForAllContacts() {
        viewModelScope.launch {
            val contacts = chatRepository.getContacts().first()
            for (contact in contacts) {
                if (!canAutoUpdateAliasToDomain(contact.alias, contact.systemContactId, contact.knsName)) continue
                val primary = knsService.reverseResolve(contact.id) ?: continue
                if (primary != contact.alias) {
                    chatRepository.addContact(contact.copy(alias = primary, knsName = primary))
                }
            }
        }
    }

    /** Links a chat to a phone contact picked via ActivityResultContracts.PickContact() — that name always wins over KNS auto-rename. */
    fun linkSystemContact(contactId: String, lookupKey: String, displayName: String) {
        viewModelScope.launch { chatRepository.linkSystemContact(contactId, lookupKey, displayName, source = "manual") }
    }

    fun unlinkSystemContact(contactId: String) {
        viewModelScope.launch { chatRepository.unlinkSystemContact(contactId) }
    }

    val syncSystemContactsEnabled: StateFlow<Boolean> = settings.syncSystemContactsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val autoCreateSystemContactsEnabled: StateFlow<Boolean> = settings.autoCreateSystemContactsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun setSyncSystemContactsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setSyncSystemContactsEnabled(enabled) }
    }

    /** Disabling Autocreate deletes only the shadow contacts this app created — any manually-linked real contact is untouched. */
    fun setAutoCreateSystemContactsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoCreateSystemContactsEnabled(enabled)
            if (!enabled) {
                val contacts = chatRepository.getContacts().first()
                for (contact in contacts.filter { it.systemContactLinkSource == "autoCreated" }) {
                    contact.systemContactId?.let { systemContactsSyncService.deleteShadowContact(it) }
                    chatRepository.unlinkSystemContact(contact.id)
                }
            }
        }
    }

    /**
     * Automatic system-contacts sync — matches iOS's SystemContactsService: scan for an
     * embedded Kaspa address on any unlinked chat's phone contacts, link on exact match, and
     * (if Autocreate is on) create a shadow phone contact for anything still unmatched. Run
     * whenever the chat list appears, alongside the KNS name refresh.
     */
    fun syncSystemContacts() {
        viewModelScope.launch {
            if (!settings.syncSystemContactsEnabled.first()) return@launch
            if (!systemContactsSyncService.hasReadPermission()) return@launch

            val unlinked = chatRepository.getContacts().first().filter { it.systemContactId == null }
            if (unlinked.isEmpty()) return@launch

            val matches = systemContactsSyncService.findMatches(unlinked.map { it.id }.toSet())
            for (contact in unlinked) {
                val match = matches[contact.id] ?: continue
                chatRepository.linkSystemContact(contact.id, match.lookupKey, match.displayName, source = "manual")
            }

            if (settings.autoCreateSystemContactsEnabled.first() && systemContactsSyncService.hasWritePermission()) {
                for (contact in unlinked) {
                    if (contact.id in matches) continue // just linked above
                    val alias = contact.alias ?: contact.id.takeLast(8)
                    val lookupKey = systemContactsSyncService.createShadowContact(contact.id, alias) ?: continue
                    chatRepository.linkSystemContact(contact.id, lookupKey, alias, source = "autoCreated")
                }
            }
        }
    }

    private val _knsResolvedAddress = MutableStateFlow<String?>(null)
    val knsResolvedAddress: StateFlow<String?> = _knsResolvedAddress.asStateFlow()

    private val _isResolvingKns = MutableStateFlow(false)
    val isResolvingKns: StateFlow<Boolean> = _isResolvingKns.asStateFlow()

    private val _knsError = MutableStateFlow<String?>(null)
    val knsError: StateFlow<String?> = _knsError.asStateFlow()

    private var knsResolveJob: Job? = null

    /** Call on every keystroke in the Create Chat address field — debounces and resolves if the input looks like a KNS domain. */
    fun onCreateChatAddressChanged(input: String) {
        knsResolveJob?.cancel()
        _knsResolvedAddress.value = null
        _knsError.value = null

        if (!KnsService.looksLikeDomain(input)) {
            _isResolvingKns.value = false
            return
        }

        _isResolvingKns.value = true
        knsResolveJob = viewModelScope.launch {
            delay(500)
            val resolved = knsService.resolve(input)
            _isResolvingKns.value = false
            if (resolved != null) {
                _knsResolvedAddress.value = resolved
            } else {
                _knsError.value = "KNS domain not found"
            }
        }
    }

    /**
     * Sends an on-chain message via WalletService. Inserts a "pending" placeholder
     * immediately (before the network call) so it shows up in the thread right away;
     * on success that placeholder is swapped for the real message (real tx id), on
     * failure it flips to "failed" in place and stays visible with a Retry option —
     * matches iOS ChatService+Conversations' optimistic send flow.
     */
    // The message currently being replied to (double-tap on its bubble to set this), shown as a
    // banner above the compose field — cleared automatically once the reply actually sends.
    private val _replyingTo = MutableStateFlow<MessageEntity?>(null)
    val replyingTo: StateFlow<MessageEntity?> = _replyingTo.asStateFlow()

    fun startReplyTo(message: MessageEntity) {
        _replyingTo.value = message
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    fun sendMessage(contactId: String, text: String) {
        if (text.isEmpty()) return
        val reply = _replyingTo.value
        val feeRate = _feeRateOverride.value
        _feeRateOverride.value = null
        viewModelScope.launch {
            val pendingId = "pending_${java.util.UUID.randomUUID()}"
            try {
                val myAddress = walletManager.getAddress()
                val payload = if (reply != null) {
                    val preview = VoiceMessage.parseOrNull(reply.plaintextBody)?.let { "🎤 Audio message" }
                        ?: ImageMessage.parseOrNull(reply.plaintextBody)?.let { "📷 Photo" }
                        // Replying to a message that's itself a reply — unwrap to its actual text
                        // rather than showing the inner reply's raw JSON as the preview.
                        ?: MessageReply.parseOrNull(reply.plaintextBody)?.text
                        ?: (reply.plaintextBody ?: "")
                    val replyToSender = if (reply.direction == "sent") myAddress else contactId
                    MessageReply.encode(replyToId = reply.id, replyToSender = replyToSender, replyToPreview = preview, text = text)
                } else {
                    text
                }
                chatRepository.insertMessage(
                    MessageEntity(
                        id = pendingId,
                        contactId = contactId,
                        walletAddress = myAddress,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = payload,
                        encryptedPayload = "",
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "pending"
                    )
                )

                // Encrypt + send handshake (if needed) + encrypted message
                val result = walletService.sendKasiaMessage(contactId, payload, feeRateOverride = feeRate)

                chatRepository.deleteMessage(pendingId)
                chatRepository.insertMessage(
                    MessageEntity(
                        id = result.txId,
                        contactId = contactId,
                        walletAddress = myAddress,
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = payload,
                        encryptedPayload = result.payloadHex,
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "sent"
                    )
                )
                _replyingTo.value = null
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
                chatRepository.updateMessageStatus(pendingId, "failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Voice messages — recorded as Opus-in-WebM, then sent through the exact same
    // sendMessage() pipeline as text: the entire encoded audio is embedded as base64 in the
    // message content JSON, encrypted, and put on-chain like any other message. No separate
    // upload/transport, matching iOS.
    // -------------------------------------------------------------------------

    private var recordingTickerJob: Job? = null

    /** Recording (not playback) needs Android 10+ — the mic button should be disabled below that. */
    val voiceRecordingSupported: Boolean get() = voiceRecorderService.isSupported

    fun startVoiceRecording(contactId: String) {
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) return
        try {
            voiceRecorderService.startRecording()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Could not start voice recording", e)
            return
        }
        _voiceRecordingState.value = VoiceRecordingState(status = VoiceRecordingStatus.RECORDING)
        val startedAt = System.currentTimeMillis()
        recordingTickerJob = viewModelScope.launch {
            while (isActive && _voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
                val elapsed = System.currentTimeMillis() - startedAt
                _voiceRecordingState.value = _voiceRecordingState.value.copy(elapsedMs = elapsed)
                if (elapsed >= VoiceRecorderService.MAX_RECORDING_DURATION_MS) {
                    stopAndSendVoiceRecording(contactId)
                    break
                }
                delay(200)
            }
        }
    }

    /** Stops recording and sends it — unless it was too short to be a real message (a stray tap), in which case it's discarded silently, same as a cancel. */
    fun stopAndSendVoiceRecording(contactId: String) {
        if (_voiceRecordingState.value.status != VoiceRecordingStatus.RECORDING) return
        val elapsed = _voiceRecordingState.value.elapsedMs
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()

        val file = voiceRecorderService.stopRecording()
        if (file == null || elapsed < VoiceRecorderService.MIN_RECORDING_DURATION_MS) {
            file?.delete()
            return
        }
        sendVoiceMessage(contactId, file)
    }

    fun cancelVoiceRecording() {
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        _voiceRecordingState.value = VoiceRecordingState()
        voiceRecorderService.cancelRecording()
    }

    override fun onCleared() {
        super.onCleared()
        // Avoid leaking a live MediaRecorder if the screen/ViewModel is torn down mid-recording.
        if (_voiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
            voiceRecorderService.cancelRecording()
        }
    }

    // -------------------------------------------------------------------------
    // Group chat photo/voice - same recording/compression pipeline as 1:1 above, routed through
    // GroupRepository's sendGroupImage/sendGroupAudio (gcomm payload) instead of sendMessage.
    // -------------------------------------------------------------------------

    private var groupRecordingTickerJob: Job? = null

    fun startGroupVoiceRecording(groupId: String) {
        if (_groupVoiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) return
        try {
            voiceRecorderService.startRecording()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Could not start group voice recording", e)
            return
        }
        _groupVoiceRecordingState.value = VoiceRecordingState(status = VoiceRecordingStatus.RECORDING)
        val startedAt = System.currentTimeMillis()
        groupRecordingTickerJob = viewModelScope.launch {
            while (isActive && _groupVoiceRecordingState.value.status == VoiceRecordingStatus.RECORDING) {
                val elapsed = System.currentTimeMillis() - startedAt
                _groupVoiceRecordingState.value = _groupVoiceRecordingState.value.copy(elapsedMs = elapsed)
                if (elapsed >= VoiceRecorderService.MAX_RECORDING_DURATION_MS) {
                    stopAndSendGroupVoiceRecording(groupId)
                    break
                }
                delay(200)
            }
        }
    }

    fun stopAndSendGroupVoiceRecording(groupId: String) {
        if (_groupVoiceRecordingState.value.status != VoiceRecordingStatus.RECORDING) return
        val elapsed = _groupVoiceRecordingState.value.elapsedMs
        groupRecordingTickerJob?.cancel()
        groupRecordingTickerJob = null
        _groupVoiceRecordingState.value = VoiceRecordingState()

        val file = voiceRecorderService.stopRecording()
        if (file == null || elapsed < VoiceRecorderService.MIN_RECORDING_DURATION_MS) {
            file?.delete()
            return
        }
        viewModelScope.launch {
            try {
                val bytes = file.readBytes()
                groupRepository.sendGroupAudio(bytes, groupId, fileName = file.name)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending group voice message", e)
            } finally {
                file.delete()
            }
        }
    }

    fun cancelGroupVoiceRecording() {
        groupRecordingTickerJob?.cancel()
        groupRecordingTickerJob = null
        _groupVoiceRecordingState.value = VoiceRecordingState()
        voiceRecorderService.cancelRecording()
    }

    /** Mirrors [sendPendingPhoto] for the group's own staged photo - smaller default target than 1:1's preset since group's `gcomm` payload hex-encodes the ciphertext (vs. 1:1's base64) plus extra fixed per-message fields, so the same raw photo lands as a noticeably larger on-chain payload. */
    fun sendPendingGroupPhoto(groupId: String) {
        val uri = _groupPendingPhotoUri.value ?: return
        _groupPendingPhotoUri.value = null
        viewModelScope.launch {
            try {
                val prepared = ImagePrep.prepareForChatMessage(appContext, uri, GROUP_PHOTO_TARGET_BYTES)
                groupRepository.sendGroupImage(prepared.bytes, groupId, fileName = prepared.fileName, mimeType = prepared.mimeType)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing group photo message", e)
            }
        }
    }

    /** Compresses and sends the currently staged [pendingPhotoUri] — clears the staged photo either way, matching the picker-cancel UX (a failed compression just drops back to the empty input bar, same as [sendVoiceMessage] logging and moving on rather than surfacing a dedicated error). */
    fun sendPendingPhoto(contactId: String) {
        val uri = _pendingPhotoUri.value ?: return
        _pendingPhotoUri.value = null
        viewModelScope.launch {
            try {
                val prepared = ImagePrep.prepareForChatMessage(appContext, uri, chatPhotoQualityPreset.value.targetBytes)
                val base64 = android.util.Base64.encodeToString(prepared.bytes, android.util.Base64.NO_WRAP)
                val json = ImageMessage.encode(fileName = prepared.fileName, sizeBytes = prepared.bytes.size.toLong(), base64Image = base64, mimeType = prepared.mimeType)
                sendMessage(contactId, json)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing photo message", e)
            }
        }
    }

    private fun sendVoiceMessage(contactId: String, file: java.io.File) {
        viewModelScope.launch {
            try {
                val bytes = file.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val json = VoiceMessage.encode(fileName = file.name, sizeBytes = bytes.size.toLong(), base64Audio = base64)
                sendMessage(contactId, json)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing voice message", e)
            } finally {
                file.delete()
            }
        }
    }

    /** Re-attempts a failed message, reusing its same id/content — the same placeholder resurrected, not a new message. */
    fun retrySendMessage(message: MessageEntity) {
        val text = message.plaintextBody ?: return
        viewModelScope.launch {
            try {
                chatRepository.updateMessageStatus(message.id, "pending")
                val result = walletService.sendKasiaMessage(message.contactId, text)
                chatRepository.deleteMessage(message.id)
                chatRepository.insertMessage(
                    MessageEntity(
                        id = result.txId,
                        contactId = message.contactId,
                        walletAddress = walletManager.getAddress(),
                        type = com.kachat.app.util.MessageProtocol.TYPE_COMM,
                        direction = "sent",
                        plaintextBody = text,
                        encryptedPayload = result.payloadHex,
                        amountSompi = 0,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "sent"
                    )
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error retrying message", e)
                chatRepository.updateMessageStatus(message.id, "failed")
            }
        }
    }

    /**
     * Sends a Kaspa payment via WalletService. Same optimistic pending/failed pattern
     * as [sendMessage], but with no retry entry point — matches iOS explicitly excluding
     * payment retry, since blindly re-sending a payment risks paying twice.
     */
    fun sendPayment(contactId: String, amount: String) {
        val amountKas = amount.toDoubleOrNull() ?: return
        val sompi = (amountKas * 100_000_000).toLong()
        val feeRate = _feeRateOverride.value
        _feeRateOverride.value = null
        viewModelScope.launch {
            val pendingId = "pending_${java.util.UUID.randomUUID()}"
            try {
                val myAddress = walletManager.getAddress()
                chatRepository.insertMessage(
                    MessageEntity(
                        id = pendingId,
                        contactId = contactId,
                        walletAddress = myAddress,
                        type = "pay",
                        direction = "sent",
                        plaintextBody = "Sent $amount KAS",
                        encryptedPayload = "",
                        amountSompi = sompi,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "pending"
                    )
                )

                val txId = walletService.payInKaspa(toAddress = contactId, amountSompi = sompi, feeRateOverride = feeRate)

                chatRepository.deleteMessage(pendingId)
                chatRepository.insertMessage(
                    MessageEntity(
                        id = txId,
                        contactId = contactId,
                        walletAddress = myAddress,
                        type = "pay",
                        direction = "sent",
                        plaintextBody = "Sent $amount KAS",
                        encryptedPayload = "",
                        amountSompi = sompi,
                        blockTimestamp = System.currentTimeMillis(),
                        deliveryStatus = "sent"
                    )
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending payment", e)
                chatRepository.updateMessageStatus(pendingId, "failed")
            }
        }
    }

    fun getConversation(contactId: String): Conversation? {
        return conversations.value.find { it.contact.id == contactId }
    }
    
    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return chatRepository.getMessages(contactId)
    }

    companion object {
        /** KNS domain shown as "Donate" in Settings -> About — see [startDonationChat]. */
        const val DONATION_KNS_DOMAIN = "kachat.kas"

        /** Target raw JPEG bytes for a group chat photo — see [sendPendingGroupPhoto]. */
        private const val GROUP_PHOTO_TARGET_BYTES = 10_000

        /**
         * We've reached out with no handshake (deterministic-alias messaging) and haven't
         * heard back yet — matches iOS's `shouldShowUnnotifiedWarning`: at least one sent
         * message, no handshake in either direction, no payment ever exchanged, and they
         * haven't sent anything back. Any of those happening clears the banner for good.
         */
        internal fun shouldShowUnnotifiedWarning(messages: List<MessageEntity>): Boolean {
            val hasOutgoing = messages.any { it.direction == "sent" }
            val hasIncomingHandshake = messages.any { it.type == com.kachat.app.util.MessageProtocol.TYPE_HANDSHAKE && it.direction == "received" }
            val hasOutgoingHandshake = messages.any { it.type == com.kachat.app.util.MessageProtocol.TYPE_HANDSHAKE && it.direction == "sent" }
            val hasAnyPayment = messages.any { it.type == com.kachat.app.util.MessageProtocol.TYPE_PAY }
            val hasAnyIncoming = messages.any { it.direction == "received" }

            return hasOutgoing && !hasIncomingHandshake && !hasOutgoingHandshake && !hasAnyPayment && !hasAnyIncoming
        }

        /** Matches iOS's `shouldShowRetry` — only failed outgoing non-payment messages can be retried. */
        internal fun shouldShowRetryOption(message: MessageEntity): Boolean =
            message.direction == "sent" && message.deliveryStatus == "failed" && message.type != com.kachat.app.util.MessageProtocol.TYPE_PAY

        /**
         * Safe to auto-overwrite a contact's alias with the detected primary KNS domain only if
         * it's unset — never clobber a real custom nickname the user typed in, matching iOS's
         * identical rule. A contact linked to a system (phone) contact is never eligible either
         * way, regardless of what its alias currently looks like — that link always takes
         * priority. Once any domain is associated with a contact (`knsName` set, whether by this
         * same auto-detection or by an explicit pick in Chat Info), that choice is pinned —
         * without this, an explicit non-primary domain selection would silently revert back to
         * primary the next time this runs, since the selected domain also "looks like" a domain
         * and would otherwise pass the old unset-or-domain-shaped check.
         */
        internal fun canAutoUpdateAliasToDomain(currentAlias: String?, systemContactId: String? = null, knsName: String? = null): Boolean =
            systemContactId == null && knsName == null && currentAlias == null
    }
}
