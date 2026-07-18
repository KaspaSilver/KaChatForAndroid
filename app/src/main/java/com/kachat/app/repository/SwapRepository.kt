package com.kachat.app.repository

import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransactionEntity
import com.kachat.app.services.ChangeNowApi
import com.kachat.app.services.ChangeNowCreateTransactionRequest
import com.kachat.app.services.ChangeNowEstimateResponse
import com.kachat.app.services.ChangeNowRangeResponse
import com.kachat.app.services.ChangeNowTransactionResponse
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChangeNOW-powered coin swaps — quotes/creates/tracks exchanges via [ChangeNowApi], and for the
 * KAS-is-the-"from"-side case, sends the KAS itself via [WalletService] rather than making the
 * user do that manually in a separate app. The "to" side of a swap is never something this
 * wallet holds, so there's no equivalent auto-send for a KAS-is-the-"to"-side exchange — that
 * direction only ever gets as far as showing the deposit address for the user to pay into from
 * wherever they're holding that other coin.
 */
@Singleton
class SwapRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val changeNowApi: ChangeNowApi,
    private val walletService: WalletService
) {
    fun getSwapHistory(): Flow<List<SwapTransactionEntity>> = database.swapDao().getSwaps()

    /** Pulls ChangeNOW's actual error body out of a failed call — its 4xx responses carry the real reason in JSON, and a bare "HTTP 400" tells us nothing. */
    private fun describeError(e: Exception): String {
        if (e is retrofit2.HttpException) {
            val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
            return "HTTP ${e.code()}: ${body?.take(300) ?: e.message()}"
        }
        return e.message ?: e.javaClass.simpleName
    }

    suspend fun getEstimate(from: SwapCoin, to: SwapCoin, fromAmount: String): Result<ChangeNowEstimateResponse> {
        return try {
            Result.success(
                changeNowApi.getEstimatedAmount(
                    fromCurrency = from.ticker,
                    fromNetwork = from.network,
                    toCurrency = to.ticker,
                    toNetwork = to.network,
                    fromAmount = fromAmount
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception(describeError(e)))
        }
    }

    suspend fun getRange(from: SwapCoin, to: SwapCoin): Result<ChangeNowRangeResponse> {
        return try {
            Result.success(
                changeNowApi.getRange(
                    fromCurrency = from.ticker,
                    fromNetwork = from.network,
                    toCurrency = to.ticker,
                    toNetwork = to.network
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Opens the exchange with ChangeNOW, saves it to local history, and — only when [from] is KAS
     * — immediately sends [fromAmount] from this wallet's spending balance to the returned
     * `payinAddress`. [payoutAddress] is where ChangeNOW sends the "to" coin: this wallet's own
     * identity/spending address when [to] is KAS, otherwise wherever the user wants the other
     * coin delivered.
     */
    suspend fun createSwap(
        from: SwapCoin,
        to: SwapCoin,
        fromAmount: String,
        payoutAddress: String,
        fromSpendingIndex: Int? = null,
        feeRateOverride: Long? = null
    ): Result<ChangeNowTransactionResponse> {
        return try {
            val response = changeNowApi.createTransaction(
                ChangeNowCreateTransactionRequest(
                    fromCurrency = from.ticker,
                    fromNetwork = from.network,
                    toCurrency = to.ticker,
                    toNetwork = to.network,
                    fromAmount = fromAmount,
                    address = payoutAddress
                )
            )
            val payinAddress = response.payinAddress
            if (payinAddress.isNullOrBlank()) {
                return Result.failure(IllegalStateException("ChangeNOW didn't return a deposit address"))
            }

            var kasSendTxId: String? = null
            if (from.ticker == "kas") {
                val amountSompi = Math.round(fromAmount.toDouble() * 100_000_000.0)
                kasSendTxId = if (fromSpendingIndex != null) {
                    walletService.sendFromSpendingAddress(fromSpendingIndex, payinAddress, amountSompi, feeRateOverride)
                } else {
                    walletService.sendFromCurrentSpendingAddress(payinAddress, amountSompi, feeRateOverride)
                }
            }

            database.swapDao().insert(
                SwapTransactionEntity(
                    id = response.id,
                    fromTicker = from.ticker,
                    fromNetwork = from.network,
                    toTicker = to.ticker,
                    toNetwork = to.network,
                    fromAmount = fromAmount,
                    toAmount = response.toAmount?.toString() ?: "",
                    payinAddress = payinAddress,
                    payoutAddress = payoutAddress,
                    status = response.status ?: "new",
                    createdAtMillis = System.currentTimeMillis(),
                    kasSendTxId = kasSendTxId
                )
            )

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(Exception(describeError(e)))
        }
    }

    /** Set once the user actually saves the prefilled portfolio transaction for this swap (see [SwapTransactionEntity.addedToPortfolio]) — gates the "Add to Portfolio" UI action so it can't fire twice for the same swap. */
    suspend fun markSwapAddedToPortfolio(id: String) = database.swapDao().markAddedToPortfolio(id)

    /** Removes a swap from local history only — ChangeNOW's own record of the exchange is untouched. */
    suspend fun deleteSwap(id: String) = database.swapDao().delete(id)

    /** Re-checks one swap's status with ChangeNOW and updates the cached local copy. */
    suspend fun refreshStatus(id: String): Result<ChangeNowTransactionResponse> {
        return try {
            val response = changeNowApi.getTransactionStatus(id)
            response.status?.let { database.swapDao().updateStatus(id, it) }
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
