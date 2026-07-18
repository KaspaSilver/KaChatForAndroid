package com.kachat.app.models

import androidx.room.Entity

/** One side of a swap pair — ChangeNOW addresses currencies by (ticker, network) since some tickers exist on more than one chain. */
data class SwapCoin(val ticker: String, val network: String, val displayName: String)

/** Kaspa itself — always one side of every swap this app can do. Verified against ChangeNOW's live /v2/exchange/currencies list: ticker "kas", network "kas". */
val KAS_SWAP_COIN = SwapCoin("kas", "kas", "Kaspa")

/**
 * Scoped down to a single pair for now: KAS <-> USDC on Polygon. ChangeNOW's network code for
 * Polygon is "matic" (not "polygon") — confirmed against the live /v2/exchange/currencies list.
 */
val USDC_POLYGON_SWAP_COIN = SwapCoin("usdc", "matic", "USD Coin (Polygon)")

val CURATED_SWAP_COINS = listOf(USDC_POLYGON_SWAP_COIN)

/**
 * Local record of a swap this device initiated, kept for the "Swap History" list — ChangeNOW is
 * the source of truth for the exchange itself, this just remembers it happened and caches the
 * last status we saw so the list has something to show without a network round trip on open.
 */
@Entity(tableName = "swap_transactions", primaryKeys = ["id"])
data class SwapTransactionEntity(
    val id: String, // ChangeNOW exchange id — also the primary key
    val fromTicker: String,
    val fromNetwork: String,
    val toTicker: String,
    val toNetwork: String,
    val fromAmount: String,
    val toAmount: String,
    val payinAddress: String,
    val payoutAddress: String,
    val status: String,
    val createdAtMillis: Long,
    val kasSendTxId: String? = null, // set once this device auto-sent KAS to payinAddress, when KAS was the "from" side
    val addedToPortfolio: Boolean = false // set once the KAS leg of this swap has been recorded as a portfolio transaction
)
