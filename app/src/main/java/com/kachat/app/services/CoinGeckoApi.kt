package com.kachat.app.services

import retrofit2.http.GET
import retrofit2.http.Query

// -------------------------------------------------------------------------
// Data transfer objects — CoinGecko's free public API, no key required.
// Same endpoints Kaspium's wallet uses for its own KAS price display
// (kaspium_wallet/lib/coingecko/coingecko_repository.dart).
// -------------------------------------------------------------------------

/** `/api/v3/simple/price?ids=kaspa&vs_currencies=usd` response shape: `{"kaspa":{"usd":0.123}}`. */
data class SimplePriceResponse(
    val kaspa: Map<String, Double>
)

/** `/api/v3/coins/kaspa/market_chart` response — `prices` is a list of `[timestampMillis, priceUsd]` pairs. */
data class MarketChartResponse(
    val prices: List<List<Double>>
)

// -------------------------------------------------------------------------
// Retrofit interface
// -------------------------------------------------------------------------

interface CoinGeckoApi {

    @GET("api/v3/simple/price")
    suspend fun getSimplePrice(
        @Query("ids") ids: String = "kaspa",
        @Query("vs_currencies") vsCurrencies: String = "usd"
    ): SimplePriceResponse

    @GET("api/v3/coins/kaspa/market_chart")
    suspend fun getMarketChart(
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("days") days: Int = 30
    ): MarketChartResponse
}
