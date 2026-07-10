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
    private val googleDriveBackupService: GoogleDriveBackupService
) : ViewModel() {

    /** Suppresses a notification for whichever contact's thread is currently open. */
    fun setActiveContact(contactId: String?) {
        notificationHelper.setActiveContact(contactId)
    }

    val hideAutoCreatedPaymentChats: StateFlow<Boolean> = settings.hideAutoCreatedPaymentChats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val showContactBalance: StateFlow<Boolean> = settings.showContactBalance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun updateShowContactBalance(enabled: Boolean) {
        viewModelScope.launch { settings.setShowContactBalance(enabled) }
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
                _wipeIncomingState.value = DangerZoneOpState(status = DangerZoneOpStatus.SUCCESS, message = "Incoming messages wiped — re-syncing from the blockchain.")
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

    val conversations: StateFlow<List<Conversation>> = combine(
        chatRepository.getContacts(),
        chatRepository.getLatestMessages(),
        chatRepository.getPaymentOnlyContactIds(),
        hideAutoCreatedPaymentChats,
        chatRepository.getUnreadCounts()
    ) { contacts, latestMessages, paymentOnlyIds, hidePaymentChats, unreadCounts ->
        val latestByContact = latestMessages.associateBy { it.contactId }
        val unreadByContact = unreadCounts.associateBy({ it.contactId }, { it.count })
        contacts
            .filter { !(hidePaymentChats && paymentOnlyIds.contains(it.id)) }
            .map { contact ->
                Conversation(contact, latestByContact[contact.id], unreadByContact[contact.id] ?: 0)
            }.sortedByDescending { it.lastMessage?.blockTimestamp ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    fun markAsRead(contactId: String) {
        viewModelScope.launch { chatRepository.markAsRead(contactId) }
    }

    /** Permanently deletes the chat (contact + all local messages) — see ChatRepository.deleteChat. */
    fun deleteChat(contactId: String) {
        viewModelScope.launch { chatRepository.deleteChat(contactId) }
    }

    fun updateHideAutoCreatedPaymentChats(enabled: Boolean) {
        viewModelScope.launch { settings.setHideAutoCreatedPaymentChats(enabled) }
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

    fun updateContactName(contactId: String, newName: String) {
        viewModelScope.launch {
            val existing = chatRepository.getContact(contactId) ?: return@launch
            val updated = existing.copy(alias = if (newName.isBlank()) null else newName)
            chatRepository.addContact(updated)
        }
    }

    private val _contactBalances = MutableStateFlow<Map<String, String>>(emptyMap())
    val contactBalances: StateFlow<Map<String, String>> = _contactBalances.asStateFlow()

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

    val estimateFeesEnabled: StateFlow<Boolean> = settings.estimateFees
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    private val _currentUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>>(emptyList())
    val currentUtxos: StateFlow<List<com.kachat.app.services.UtxoEntry>> = _currentUtxos.asStateFlow()

    private val _paymentAmount = MutableStateFlow("")
    val paymentAmount: StateFlow<String> = _paymentAmount.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _networkFeeRate = MutableStateFlow(com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()) // sompi per mass-gram
    val networkFeeRate: StateFlow<Double> = _networkFeeRate.asStateFlow()

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

    /**
     * The payload byte count to price the live fee preview off of: the real typed-text length
     * while composing, a rough elapsed-time-based estimate of the final encoded/encrypted size
     * while recording a voice message, or a rough estimate of the final wire size for a staged
     * photo — same shape as [VoiceMessage.estimatedWirePayloadSize]: the real send always measures
     * the actual encoded bytes exactly, this is only ever used for the live preview.
     */
    private val previewPayloadSize: Flow<Int> = combine(_messageText, voiceRecordingState, pendingPhotoUri) { text, recording, photoUri ->
        if (recording.status == VoiceRecordingStatus.RECORDING) {
            VoiceMessage.estimatedWirePayloadSize(recording.elapsedMs)
        } else if (photoUri != null) {
            // Compressed image bytes -> inner base64 (+33%) -> JSON envelope overhead -> encryption
            // + outer base64 (+33%) -- rough multiplier, calibrated the same way VoiceMessage's
            // estimate is: never used for the real fee, only this live preview.
            (ImagePrep.DEFAULT_CHAT_TARGET_BYTES * 1.33 * 1.33).toInt() + 150
        } else {
            text.toByteArray().size
        }
    }

    val estimatedFeeSompi: StateFlow<Long?> = combine(paymentAmount, previewPayloadSize, _currentUtxos, estimateFeesEnabled, _networkFeeRate) { amount, textPayloadSize, utxos, enabled, rate ->
        if (!enabled || (amount.isEmpty() && textPayloadSize == 0)) return@combine null

        val isPayment = amount.isNotEmpty()
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

    fun setMessageText(text: String) {
        _messageText.value = text
    }

    fun setPaymentAmount(amount: String) {
        _paymentAmount.value = amount
    }

    fun updateEstimateFees(enabled: Boolean) {
        viewModelScope.launch { settings.setEstimateFees(enabled) }
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

    /** Fetches this contact's owned KNS domains + the active one's profile (avatar/bio/socials). */
    fun refreshKnsProfile(contactId: String) {
        viewModelScope.launch {
            val contact = chatRepository.getContact(contactId) ?: return@launch
            val ownedAssets = knsService.getOwnedDomains(contact.id)
            val ownedNames = ownedAssets.mapNotNull { it.asset }

            if (ownedNames.isEmpty()) {
                _knsProfiles.update { it + (contactId to KnsProfileUiState()) }
                return@launch
            }

            val primary = knsService.reverseResolve(contact.id)
            val activeName = KnsService.pickActiveDomain(ownedNames, contact.knsName, primary)
            val activeAsset = ownedAssets.firstOrNull { it.asset == activeName }
            val profile = activeAsset?.assetId?.let { knsService.getProfile(it) }

            _knsProfiles.update { it + (contactId to KnsProfileUiState(ownedNames, activeName, profile)) }

            // Keep the chat list's cached avatar current.
            if (profile?.avatarUrl != contact.knsAvatarUrl) {
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
            val contact = chatRepository.getContact(contactId) ?: return@launch
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
                val result = walletService.sendKasiaMessage(contactId, payload)

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

    /** Compresses and sends the currently staged [pendingPhotoUri] — clears the staged photo either way, matching the picker-cancel UX (a failed compression just drops back to the empty input bar, same as [sendVoiceMessage] logging and moving on rather than surfacing a dedicated error). */
    fun sendPendingPhoto(contactId: String) {
        val uri = _pendingPhotoUri.value ?: return
        _pendingPhotoUri.value = null
        viewModelScope.launch {
            try {
                val bytes = ImagePrep.prepareForChatMessage(appContext, uri)
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val json = ImageMessage.encode(fileName = "photo.jpg", sizeBytes = bytes.size.toLong(), base64Image = base64)
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

                val txId = walletService.payInKaspa(toAddress = contactId, amountSompi = sompi)

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

    fun refreshContactBalance(address: String) {
        if (!com.kachat.app.util.KaspaAddress.isValid(address)) return

        viewModelScope.launch {
            try {
                chatRepository.syncMessages()

                val api = networkService.kaspaRestApi.value ?: return@launch
                val response = api.getBalance(address)
                val kasAmount = response.balance.toDouble() / 100_000_000.0
                val balanceStr = String.format(Locale.US, "%.8f", kasAmount)
                _contactBalances.value = _contactBalances.value + (address to balanceStr)
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    companion object {
        /** KNS domain shown as "Donate" in Settings -> About — see [startDonationChat]. */
        const val DONATION_KNS_DOMAIN = "kachat.kas"

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
