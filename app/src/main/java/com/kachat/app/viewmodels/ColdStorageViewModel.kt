package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.util.KaspaExtendedPublicKey
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
    private val addressDiscovery: ColdStorageAddressDiscovery
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

    data class AddressRow(val index: Int, val address: String, val balanceSompi: Long, val hasHistory: Boolean)

    private val _addresses = MutableStateFlow<List<AddressRow>>(emptyList())
    val addresses: StateFlow<List<AddressRow>> = _addresses.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    fun refreshAddresses(accountId: String) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        viewModelScope.launch {
            _isDiscovering.value = true
            try {
                val parsed = KaspaExtendedPublicKey.parse(account.kpub).getOrThrow()
                val rootKey = KaspaExtendedPublicKey.toDeterministicKey(parsed)
                val discovered = addressDiscovery.discoverAddresses(rootKey)
                if (discovered.isNotEmpty()) {
                    coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, discovered.last().index)
                }
                _addresses.value = discovered.map { AddressRow(it.index, it.address, it.balanceSompi, it.hasHistory) }
            } catch (e: Exception) {
                _addresses.value = emptyList()
            } finally {
                _isDiscovering.value = false
            }
        }
    }
}
