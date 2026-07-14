package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.services.ColdStorageSendEngine
import com.kachat.app.util.KaspaExtendedPublicKey
import com.kachat.app.util.KsptCodec
import com.kachat.app.util.QrFrameChunker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cold Storage — a fully separate area of the app for interacting with a KasSigner air-gapped
 * device via QR exchange. Deliberately does not touch [WalletManager]/[WalletViewModel] at all:
 * this ViewModel only ever knows about watch-only kpub accounts, never a mnemonic or private key.
 */
@HiltViewModel
class ColdStorageViewModel @Inject constructor(
    private val coldStorageManager: ColdStorageManager,
    private val addressDiscovery: ColdStorageAddressDiscovery,
    private val sendEngine: ColdStorageSendEngine
) : ViewModel() {

    private val _accounts = MutableStateFlow(coldStorageManager.getAccounts())
    val accounts: StateFlow<List<ColdStorageManager.ColdAccount>> = _accounts.asStateFlow()

    enum class ImportStatus { IDLE, VALIDATING, SUCCESS, INVALID_KPUB }
    data class ImportUiState(val status: ImportStatus = ImportStatus.IDLE, val errorMessage: String? = null)

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** [scannedText] is whatever the QR scanner handed back — validated as a real kpub before saving. */
    fun importKpub(scannedText: String, name: String) {
        _importState.value = ImportUiState(status = ImportStatus.VALIDATING)
        coldStorageManager.saveAccount(name.ifBlank { "Cold Storage" }, scannedText.trim()).fold(
            onSuccess = {
                _accounts.value = coldStorageManager.getAccounts()
                _importState.value = ImportUiState(status = ImportStatus.SUCCESS)
            },
            onFailure = { e ->
                _importState.value = ImportUiState(
                    status = ImportStatus.INVALID_KPUB,
                    errorMessage = e.message ?: "Not a valid kpub"
                )
            }
        )
    }

    fun resetImportState() {
        _importState.value = ImportUiState()
    }

    fun renameAccount(id: String, newName: String) {
        coldStorageManager.renameAccount(id, newName)
        _accounts.value = coldStorageManager.getAccounts()
    }

    fun deleteAccount(id: String) {
        coldStorageManager.deleteAccount(id)
        _accounts.value = coldStorageManager.getAccounts()
    }

    // -------------------------------------------------------------------------
    // Detail screen — derived addresses + live balances for a single account
    // -------------------------------------------------------------------------

    data class AddressRow(
        val index: Int,
        val address: String,
        val balanceSompi: Long,
        val hasHistory: Boolean,
        val label: String? = null,
        val hidden: Boolean = false
    )

    private val _addresses = MutableStateFlow<List<AddressRow>>(emptyList())
    val addresses: StateFlow<List<AddressRow>> = _addresses.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Gap-limit scan for used/funded addresses, plus every index up to [ColdStorageManager.ColdAccount.maxDerivedIndex]
     * regardless of whether the scan itself reached that far — an index a user manually generated
     * via [generateMoreAddresses] sits past where a fresh unused-account scan would ever stop,
     * so it'd otherwise vanish again on the very next refresh.
     */
    fun refreshAddresses(accountId: String) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        viewModelScope.launch {
            _isDiscovering.value = true
            try {
                val parsed = KaspaExtendedPublicKey.parse(account.kpub).getOrThrow()
                val rootKey = KaspaExtendedPublicKey.toDeterministicKey(parsed)
                val byIndex = addressDiscovery.discoverAddresses(rootKey).associateBy { it.index }.toMutableMap()

                for (index in 0..account.maxDerivedIndex) {
                    if (index !in byIndex) {
                        addressDiscovery.checkAddress(rootKey, chain = 0, index = index)?.let { byIndex[index] = it }
                    }
                }

                val maxIndex = maxOf(account.maxDerivedIndex, byIndex.keys.maxOrNull() ?: 0)
                coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, maxIndex)

                val labels = coldStorageManager.getAddressLabels(accountId)
                val hiddenIndices = coldStorageManager.getHiddenIndices(accountId)
                // Newest (highest index) first — a just-generated address should be immediately
                // visible at the top, not require scrolling past every earlier one to find it.
                _addresses.value = byIndex.values.sortedByDescending { it.index }.map {
                    AddressRow(it.index, it.address, it.balanceSompi, it.hasHistory, labels[it.index], it.index in hiddenIndices)
                }
            } catch (e: Exception) {
                _addresses.value = emptyList()
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    /** Derives and shows one more address past whatever's currently listed — for pulling up a fresh unused address on demand, without waiting for it to gain history first. */
    fun generateMoreAddresses(accountId: String) {
        val nextIndex = (_addresses.value.maxOfOrNull { it.index } ?: -1) + 1
        coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, nextIndex)
        _accounts.value = coldStorageManager.getAccounts()
        refreshAddresses(accountId)
    }

    fun setAddressLabel(accountId: String, index: Int, label: String) {
        coldStorageManager.setAddressLabel(accountId, index, label)
        _addresses.value = _addresses.value.map {
            if (it.index == index) it.copy(label = label.trim().ifBlank { null }) else it
        }
    }

    /** Hiding is purely a display preference — the address, its balance, and its label are untouched, and it always shows back up under "Hidden Addresses" to be unhidden. */
    fun setAddressHidden(accountId: String, index: Int, hidden: Boolean) {
        coldStorageManager.setAddressHidden(accountId, index, hidden)
        _addresses.value = _addresses.value.map {
            if (it.index == index) it.copy(hidden = hidden) else it
        }
    }

    // -------------------------------------------------------------------------
    // Address transaction history
    // -------------------------------------------------------------------------

    private val _txHistory = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>>(emptyList())
    val txHistory: StateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>> = _txHistory.asStateFlow()

    private val _isLoadingTxHistory = MutableStateFlow(false)
    val isLoadingTxHistory: StateFlow<Boolean> = _isLoadingTxHistory.asStateFlow()

    fun loadTxHistory(address: String) {
        viewModelScope.launch {
            _isLoadingTxHistory.value = true
            try {
                _txHistory.value = addressDiscovery.getTransactionHistory(address)
            } finally {
                _isLoadingTxHistory.value = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send flow — build an unsigned tx from one address, show it as an animated KSPT QR, scan
    // the signed response back, and broadcast it. [ColdStorageSendEngine] does the actual tx
    // building/KSPT encoding/broadcast; this just sequences the UI-facing steps.
    // -------------------------------------------------------------------------

    enum class ColdSendStep { IDLE, BUILDING, SHOWING_QR, BROADCASTING, SUCCESS, FAILED }

    data class ColdSendUiState(
        val step: ColdSendStep = ColdSendStep.IDLE,
        val qrFrames: List<ByteArray> = emptyList(),
        val feeSompi: Long = 0L,
        val txId: String? = null,
        val errorMessage: String? = null
    )

    private val _sendState = MutableStateFlow(ColdSendUiState())
    val sendState: StateFlow<ColdSendUiState> = _sendState.asStateFlow()

    // Held between "show the unsigned QR" and "scan the signed one back" — broadcastSigned needs
    // the original tx to verify the signed response's outputs/inputs weren't tampered with.
    private var pendingUnsignedTx: ColdStorageSendEngine.UnsignedColdTx? = null

    fun startColdSend(fromAddress: String, toAddress: String, amountSompi: Long) {
        val step = _sendState.value.step
        if (step != ColdSendStep.IDLE && step != ColdSendStep.SUCCESS && step != ColdSendStep.FAILED) return

        _sendState.value = ColdSendUiState(step = ColdSendStep.BUILDING)
        viewModelScope.launch {
            sendEngine.buildUnsignedTransaction(fromAddress, toAddress, amountSompi).fold(
                onSuccess = { unsigned ->
                    pendingUnsignedTx = unsigned
                    val kspt = sendEngine.toKspt(unsigned)
                    _sendState.value = ColdSendUiState(
                        step = ColdSendStep.SHOWING_QR,
                        qrFrames = QrFrameChunker.chunk(kspt),
                        feeSompi = unsigned.feeSompi
                    )
                },
                onFailure = { e ->
                    _sendState.value = ColdSendUiState(step = ColdSendStep.FAILED, errorMessage = e.message ?: "Failed to build transaction")
                }
            )
        }
    }

    /** [scannedBytes] is the fully reassembled signed-KSPT payload from [com.kachat.app.ui.screens.MultiFrameQrScannerOverlay]. */
    fun onSignedKsptScanned(scannedBytes: ByteArray) {
        val unsigned = pendingUnsignedTx ?: return
        _sendState.value = _sendState.value.copy(step = ColdSendStep.BROADCASTING)
        viewModelScope.launch {
            val decoded = KsptCodec.decode(scannedBytes).getOrElse { e ->
                _sendState.value = _sendState.value.copy(
                    step = ColdSendStep.FAILED,
                    errorMessage = e.message ?: "Couldn't read the signed transaction"
                )
                return@launch
            }
            sendEngine.broadcastSigned(unsigned, decoded).fold(
                onSuccess = { txId ->
                    pendingUnsignedTx = null
                    _sendState.value = ColdSendUiState(step = ColdSendStep.SUCCESS, txId = txId)
                },
                onFailure = { e ->
                    _sendState.value = _sendState.value.copy(step = ColdSendStep.FAILED, errorMessage = e.message ?: "Broadcast failed")
                }
            )
        }
    }

    fun resetColdSendState() {
        pendingUnsignedTx = null
        _sendState.value = ColdSendUiState()
    }
}
