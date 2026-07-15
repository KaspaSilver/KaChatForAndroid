package com.kachat.app.viewmodels

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.repository.PortfolioRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * All-time P&L, not per-lot realized/unrealized — money still held (valued at the current
 * price) plus money already taken out via sells, minus money originally put in. Correct
 * regardless of buy/sell ordering, and doesn't need FIFO/average-cost lot tracking, which is
 * more machinery than a personal manual tracker needs.
 */
data class PortfolioSummary(
    val holdingsKas: Double,
    val totalInvested: Double,
    val totalProceeds: Double,
    val currentValue: Double,
    val totalPL: Double,
    val totalPLPercent: Double
)

@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val repository: PortfolioRepository
) : ViewModel() {

    val transactions = repository.getTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentPriceUsd = MutableStateFlow<Double?>(null)
    val currentPriceUsd: StateFlow<Double?> = _currentPriceUsd.asStateFlow()

    private val _priceHistory = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val priceHistory: StateFlow<List<Pair<Long, Double>>> = _priceHistory.asStateFlow()

    /** Backs the tappable "Price (Xd)" range switcher — 1, 7, or 30 days. */
    private val _priceRangeDays = MutableStateFlow(30)
    val priceRangeDays: StateFlow<Int> = _priceRangeDays.asStateFlow()

    val summary: StateFlow<PortfolioSummary> = combine(transactions, currentPriceUsd) { txs, price ->
        computeSummary(txs, price ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), computeSummary(emptyList(), 0.0))

    /** Holdings' USD value at each price-history point — not the price itself, see [computeValueHistory]. */
    val valueHistory: StateFlow<List<Pair<Long, Double>>> = combine(transactions, priceHistory) { txs, prices ->
        computeValueHistory(txs, prices)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshPrice()
    }

    fun refreshPrice() {
        viewModelScope.launch {
            _currentPriceUsd.value = repository.getCurrentPriceUsd()
            _priceHistory.value = repository.getPriceHistory(_priceRangeDays.value)
        }
    }

    /** Switches the price chart's window (1/7/30 days) and refetches history for it. */
    fun setPriceRangeDays(days: Int) {
        if (_priceRangeDays.value == days) return
        _priceRangeDays.value = days
        viewModelScope.launch {
            _priceHistory.value = repository.getPriceHistory(days)
        }
    }

    fun addTransaction(type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long = System.currentTimeMillis(), notes: String? = null) {
        viewModelScope.launch {
            repository.addTransaction(type, (amountKas * 100_000_000).toLong(), fiatValue, timestampMillis, notes)
        }
    }

    fun updateTransaction(id: String, type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long, notes: String? = null) {
        viewModelScope.launch {
            repository.updateTransaction(id, type, (amountKas * 100_000_000).toLong(), fiatValue, timestampMillis, notes)
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }

    /** No network/DB work involved (transactions are already in memory), so no loading-state UI is needed. */
    fun exportCsv(onReady: (Uri) -> Unit) {
        try {
            onReady(repository.exportCsv(transactions.value))
        } catch (e: Exception) {
            Log.w("PortfolioViewModel", "CSV export failed", e)
        }
    }

    fun importCsv(uri: Uri, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(Result.success(repository.importCsv(uri)))
            } catch (e: Exception) {
                Log.w("PortfolioViewModel", "CSV import failed", e)
                onResult(Result.failure(e))
            }
        }
    }

    companion object {
        internal fun computeSummary(transactions: List<PortfolioTransactionEntity>, currentPriceUsd: Double): PortfolioSummary {
            var holdingsSompi = 0L
            var totalInvested = 0.0
            var totalProceeds = 0.0
            for (tx in transactions) {
                when (tx.type) {
                    "buy" -> {
                        holdingsSompi += tx.amountSompi
                        totalInvested += tx.fiatValue
                    }
                    "sell" -> {
                        holdingsSompi -= tx.amountSompi
                        totalProceeds += tx.fiatValue
                    }
                }
            }
            val holdingsKas = holdingsSompi / 100_000_000.0
            val currentValue = holdingsKas * currentPriceUsd
            val totalPL = (currentValue + totalProceeds) - totalInvested
            val totalPLPercent = if (totalInvested > 0) (totalPL / totalInvested) * 100.0 else 0.0
            return PortfolioSummary(
                holdingsKas = holdingsKas,
                totalInvested = totalInvested,
                totalProceeds = totalProceeds,
                currentValue = currentValue,
                totalPL = totalPL,
                totalPLPercent = totalPLPercent
            )
        }

        /**
         * Replays the transaction ledger against each price-history point to get holdings *as of
         * that moment* (not current holdings) — a buy/sell made partway through the window changes
         * the value curve's shape from that point on, not retroactively.
         */
        internal fun computeValueHistory(
            transactions: List<PortfolioTransactionEntity>,
            priceHistory: List<Pair<Long, Double>>
        ): List<Pair<Long, Double>> {
            if (priceHistory.isEmpty()) return emptyList()
            val sortedTx = transactions.sortedBy { it.timestampMillis }
            return priceHistory.map { (timestamp, price) ->
                var holdingsSompi = 0L
                for (tx in sortedTx) {
                    if (tx.timestampMillis > timestamp) break
                    when (tx.type) {
                        "buy" -> holdingsSompi += tx.amountSompi
                        "sell" -> holdingsSompi -= tx.amountSompi
                    }
                }
                val holdingsKas = holdingsSompi / 100_000_000.0
                timestamp to (holdingsKas * price)
            }
        }
    }
}
