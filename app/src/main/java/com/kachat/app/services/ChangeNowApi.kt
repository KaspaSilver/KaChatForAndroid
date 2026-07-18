package com.kachat.app.services

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * ChangeNOW's v2 exchange API — lets KaChat swap KAS for another coin (and back) without leaving
 * the app. Auth is a per-request `x-changenow-api-key` header, added by an OkHttp interceptor at
 * the Retrofit client level (see AppModule.provideChangeNowApi) rather than as a parameter here.
 */
interface ChangeNowApi {
    /** A live quote for how much [ChangeNowEstimateResponse.toAmount] a given [fromAmount] converts to right now. */
    @GET("v2/exchange/estimated-amount")
    suspend fun getEstimatedAmount(
        @Query("fromCurrency") fromCurrency: String,
        @Query("fromNetwork") fromNetwork: String,
        @Query("toCurrency") toCurrency: String,
        @Query("toNetwork") toNetwork: String,
        @Query("fromAmount") fromAmount: String,
        @Query("flow") flow: String = "standard"
    ): ChangeNowEstimateResponse

    /** The minimum/maximum [fromAmount] ChangeNOW will accept for this pair. */
    @GET("v2/exchange/range")
    suspend fun getRange(
        @Query("fromCurrency") fromCurrency: String,
        @Query("fromNetwork") fromNetwork: String,
        @Query("toCurrency") toCurrency: String,
        @Query("toNetwork") toNetwork: String,
        @Query("flow") flow: String = "standard"
    ): ChangeNowRangeResponse

    /** Opens a new exchange — the response's `payinAddress` is where the "from" coin needs to arrive. */
    @POST("v2/exchange")
    suspend fun createTransaction(@Body request: ChangeNowCreateTransactionRequest): ChangeNowTransactionResponse

    /** Current status of a previously-created exchange, by its [id]. */
    @GET("v2/exchange/by-id")
    suspend fun getTransactionStatus(@Query("id") id: String): ChangeNowTransactionResponse
}

data class ChangeNowEstimateResponse(
    val fromAmount: Double,
    val toAmount: Double,
    @SerializedName("transactionSpeedForecast") val transactionSpeedForecast: String? = null,
    @SerializedName("warningMessage") val warningMessage: String? = null
)

data class ChangeNowRangeResponse(
    val minAmount: Double?,
    val maxAmount: Double?
)

data class ChangeNowCreateTransactionRequest(
    val fromCurrency: String,
    val fromNetwork: String,
    val toCurrency: String,
    val toNetwork: String,
    val fromAmount: String,
    val address: String,
    val flow: String = "standard"
)

/** Shape shared by "create exchange" and "check status" responses — ChangeNOW returns the same fields for both. */
data class ChangeNowTransactionResponse(
    val id: String,
    val status: String? = null, // "new" | "waiting" | "confirming" | "exchanging" | "sending" | "finished" | "failed" | "refunded" | "verifying"
    val payinAddress: String? = null,
    val payoutAddress: String? = null,
    val payinExtraId: String? = null,
    val fromAmount: Double? = null,
    val toAmount: Double? = null,
    val fromCurrency: String? = null,
    val toCurrency: String? = null,
    val payinHash: String? = null,
    val payoutHash: String? = null
)
