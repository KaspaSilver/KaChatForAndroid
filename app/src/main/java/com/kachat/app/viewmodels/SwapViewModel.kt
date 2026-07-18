package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.CURATED_SWAP_COINS
import com.kachat.app.models.KAS_SWAP_COIN
import com.kachat.app.models.SwapCoin
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.SwapRepository
import com.kachat.app.services.ChangeNowTransactionResponse
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SwapViewModel @Inject constructor(
    private val repository: SwapRepository,
    private val walletService: WalletService,
    private val walletManager: WalletManager,
    private val settings: AppSettingsRepository
) : ViewModel() {

    val swapHistory = repository.getSwapHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One-time ChangeNOW terms/liability disclaimer, shown the first time Swap is opened. */
    val swapDisclaimerAgreed: StateFlow<Boolean> = settings.swapDisclaimerAgreed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun agreeToSwapDisclaimer() {
        viewModelScope.launch { settings.setSwapDisclaimerAgreed(true) }
    }

    data class SelectedFromAddress(val index: Int, val address: String, val balanceSompi: Long)

    /** Set when the user picked a specific non-primary spending address (via Manage Addresses) to swap KAS from instead of the active one. */
    private val _selectedFromAddress = MutableStateFlow<SelectedFromAddress?>(null)
    val selectedFromAddress: StateFlow<SelectedFromAddress?> = _selectedFromAddress.asStateFlow()

    fun selectFromSpendingAddress(index: Int, balanceSompi: Long) {
        _selectedFromAddress.value = SelectedFromAddress(index, walletManager.deriveSpendingAddress(index), balanceSompi)
    }

    fun clearSelectedFromSpendingAddress() {
        _selectedFromAddress.value = null
    }

    /** KAS available to swap away — the picked address's balance if one was chosen, otherwise the same "Pay in Kaspa" spending balance shown elsewhere in the app. */
    val spendingBalanceSompi: StateFlow<Long> = combine(walletService.spendingBalance, _selectedFromAddress) { activeBalance, selected ->
        selected?.balanceSompi ?: activeBalance
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // Where swap-received KAS lands. Defaults to a preview of the next never-used address (same
    // index math as WalletManager.generateNextSpendingAddress, just not reserved/persisted until
    // executeSwap actually calls it) — set to a specific index to override that and reuse an
    // existing address instead, e.g. one already received into from a prior swap.
    private val _toAddressOverrideIndex = MutableStateFlow<Int?>(null)
    val toAddressOverrideIndex: StateFlow<Int?> = _toAddressOverrideIndex.asStateFlow()
    private val _toAddressPreviewTick = MutableStateFlow(0)

    val toAddress: StateFlow<String> = combine(_toAddressOverrideIndex, _toAddressPreviewTick) { overrideIndex, _ ->
        walletManager.deriveSpendingAddress(overrideIndex ?: nextFreshSpendingIndex())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private fun nextFreshSpendingIndex(): Int {
        val account = walletManager.getActiveAccount() ?: return 0
        return maxOf(account.maxSpendingAddressIndex, account.spendingAddressIndex) + 1
    }

    fun selectToSpendingAddress(index: Int) {
        _toAddressOverrideIndex.value = index
    }

    fun clearToSpendingAddressOverride() {
        _toAddressOverrideIndex.value = null
    }

    // Sompi-per-mass-gram rate to use instead of the live network estimate when KAS is the "from"
    // side — same manual fee-bump escape hatch as the Manage Addresses withdraw dialog, for a busy
    // fee market. Null means "let the send pipeline use its own live estimate."
    private val _feeRateOverrideSompi = MutableStateFlow<Long?>(null)
    val feeRateOverrideSompi: StateFlow<Long?> = _feeRateOverrideSompi.asStateFlow()

    fun setFeeRateOverride(sompiPerGram: Long?) {
        _feeRateOverrideSompi.value = sompiPerGram
    }

    // True = KAS is what you're sending (the curated coin is what you receive); false = the
    // reverse. Matches the original static shell's kasIsSendSide flip-button model — KAS is
    // always one side of the pair, only which side flips.
    private val _kasIsSendSide = MutableStateFlow(true)
    val kasIsSendSide: StateFlow<Boolean> = _kasIsSendSide.asStateFlow()

    private val _otherCoin = MutableStateFlow(CURATED_SWAP_COINS.first())
    val otherCoin: StateFlow<SwapCoin> = _otherCoin.asStateFlow()

    private val _amountText = MutableStateFlow("")
    val amountText: StateFlow<String> = _amountText.asStateFlow()

    /** Where ChangeNOW should deliver the "to" coin — only asked for when that coin isn't KAS (KAS always comes back to this wallet automatically). */
    private val _payoutAddressText = MutableStateFlow("")
    val payoutAddressText: StateFlow<String> = _payoutAddressText.asStateFlow()

    enum class EstimateStatus { IDLE, LOADING, SUCCESS, FAILED }
    data class EstimateUiState(
        val status: EstimateStatus = EstimateStatus.IDLE,
        val toAmount: Double? = null,
        val errorMessage: String? = null
    )

    private val _estimateState = MutableStateFlow(EstimateUiState())
    val estimateState: StateFlow<EstimateUiState> = _estimateState.asStateFlow()

    enum class CreateSwapStatus { IDLE, SENDING_KAS, CREATING, SUCCESS, FAILED }
    data class CreateSwapUiState(
        val status: CreateSwapStatus = CreateSwapStatus.IDLE,
        val result: ChangeNowTransactionResponse? = null,
        val errorMessage: String? = null
    )

    private val _createSwapState = MutableStateFlow(CreateSwapUiState())
    val createSwapState: StateFlow<CreateSwapUiState> = _createSwapState.asStateFlow()

    private var estimateJob: Job? = null

    fun flipDirection() {
        _kasIsSendSide.value = !_kasIsSendSide.value
        rescheduleEstimate()
    }

    fun setOtherCoin(coin: SwapCoin) {
        _otherCoin.value = coin
        rescheduleEstimate()
    }

    fun setAmountText(text: String) {
        _amountText.value = text
        rescheduleEstimate()
    }

    fun setPayoutAddressText(text: String) {
        _payoutAddressText.value = text
    }

    private fun fromCoin() = if (_kasIsSendSide.value) KAS_SWAP_COIN else _otherCoin.value
    private fun toCoin() = if (_kasIsSendSide.value) _otherCoin.value else KAS_SWAP_COIN

    /** Debounced live quote — re-fires on every relevant field change rather than needing an explicit "Get Rate" tap. */
    private fun rescheduleEstimate() {
        estimateJob?.cancel()
        val amount = _amountText.value
        if (amount.toDoubleOrNull() == null || amount.toDoubleOrNull() == 0.0) {
            _estimateState.value = EstimateUiState()
            return
        }
        estimateJob = viewModelScope.launch {
            delay(500)
            _estimateState.value = EstimateUiState(status = EstimateStatus.LOADING)
            repository.getEstimate(fromCoin(), toCoin(), amount).fold(
                onSuccess = { _estimateState.value = EstimateUiState(status = EstimateStatus.SUCCESS, toAmount = it.toAmount) },
                onFailure = { e -> _estimateState.value = EstimateUiState(status = EstimateStatus.FAILED, errorMessage = e.message) }
            )
        }
    }

    fun executeSwap() {
        val amount = _amountText.value
        if (amount.toDoubleOrNull() == null) return
        val from = fromCoin()
        val to = toCoin()

        viewModelScope.launch {
            _createSwapState.value = CreateSwapUiState(
                status = if (from.ticker == "kas") CreateSwapStatus.SENDING_KAS else CreateSwapStatus.CREATING
            )

            // Swapping into KAS lands in a fresh, never-used spending address by default (rather
            // than the active one, so exchange-received coins can't be chain-linked to everyday
            // spending out of this wallet) unless the user explicitly picked a different address
            // to reuse via selectToSpendingAddress. Swapping out of KAS needs somewhere else to
            // send the other coin, since this wallet doesn't hold it.
            val payoutAddress = if (to.ticker == "kas") {
                val overrideIndex = _toAddressOverrideIndex.value
                if (overrideIndex != null) {
                    walletManager.deriveSpendingAddress(overrideIndex)
                } else {
                    val freshIndex = walletService.generateNextSpendingAddress()
                    _toAddressPreviewTick.value++
                    walletManager.deriveSpendingAddress(freshIndex)
                }
            } else {
                _payoutAddressText.value.trim()
            }
            if (payoutAddress.isBlank()) {
                _createSwapState.value = CreateSwapUiState(
                    status = CreateSwapStatus.FAILED,
                    errorMessage = "Enter an address to receive the ${to.displayName}"
                )
                return@launch
            }

            val fromSpendingIndex = if (from.ticker == "kas") _selectedFromAddress.value?.index else null
            val feeRateOverride = if (from.ticker == "kas") _feeRateOverrideSompi.value else null
            repository.createSwap(from, to, amount, payoutAddress, fromSpendingIndex, feeRateOverride).fold(
                onSuccess = { response ->
                    _createSwapState.value = CreateSwapUiState(status = CreateSwapStatus.SUCCESS, result = response)
                    _amountText.value = ""
                    _estimateState.value = EstimateUiState()
                    _selectedFromAddress.value = null
                    _toAddressOverrideIndex.value = null
                    _feeRateOverrideSompi.value = null
                },
                onFailure = { e ->
                    _createSwapState.value = CreateSwapUiState(
                        status = CreateSwapStatus.FAILED,
                        errorMessage = e.message ?: "Something went wrong"
                    )
                }
            )
        }
    }

    fun resetCreateSwapState() {
        _createSwapState.value = CreateSwapUiState()
    }

    fun refreshSwapStatus(id: String) {
        viewModelScope.launch { repository.refreshStatus(id) }
    }

    fun markSwapAddedToPortfolio(id: String) {
        viewModelScope.launch { repository.markSwapAddedToPortfolio(id) }
    }

    fun deleteSwap(id: String) {
        viewModelScope.launch { repository.deleteSwap(id) }
    }
}
