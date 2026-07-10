package com.kachat.app.models

import androidx.room.Entity

/**
 * A manually-entered buy or sell in the KAS portfolio tracker — not derived from on-chain
 * activity (deliberately: an address's transaction history can't distinguish a real purchase
 * from an ordinary incoming/outgoing KaChat payment, or from Kaspa protocol overhead like
 * message self-stashes, so auto-detection was ruled out in favor of a plain manual ledger, the
 * same model CoinMarketCap's own portfolio feature uses).
 *
 * Global, not scoped to a wallet address — this tracks the user's overall KAS investment
 * regardless of which of their accounts happens to hold the funds.
 */
@Entity(tableName = "portfolio_transactions", primaryKeys = ["id"])
data class PortfolioTransactionEntity(
    val id: String,
    val type: String,          // "buy" | "sell"
    val amountSompi: Long,     // KAS amount, sompi — matches how amounts are stored everywhere else in the app
    val fiatValue: Double,     // total USD paid (buy) or received (sell) for this transaction
    val timestampMillis: Long,
    val notes: String? = null
)
