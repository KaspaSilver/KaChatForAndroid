package com.kachat.app.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.services.CoinGeckoApi
import com.kachat.app.services.database.KaChatDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KAS portfolio tracker — a manual buy/sell ledger plus current/historical price from
 * CoinGecko's free public API (same source Kaspium's wallet uses for its own price display).
 * No on-chain address tracking: an address's transaction history can't reliably distinguish a
 * real purchase from an ordinary payment, so entries are user-entered only (matches
 * CoinMarketCap's own portfolio feature).
 */
@Singleton
class PortfolioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: KaChatDatabase,
    private val coinGeckoApi: CoinGeckoApi
) {
    fun getTransactions(): Flow<List<PortfolioTransactionEntity>> = database.portfolioDao().getTransactions()

    suspend fun addTransaction(type: String, amountSompi: Long, fiatValue: Double, timestampMillis: Long = System.currentTimeMillis(), notes: String? = null) {
        database.portfolioDao().insert(
            PortfolioTransactionEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestampMillis,
                notes = notes
            )
        )
    }

    /** Same [id] — Room's REPLACE conflict strategy on insert() means this overwrites the existing row. */
    suspend fun updateTransaction(id: String, type: String, amountSompi: Long, fiatValue: Double, timestampMillis: Long, notes: String? = null) {
        database.portfolioDao().insert(
            PortfolioTransactionEntity(
                id = id,
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestampMillis,
                notes = notes
            )
        )
    }

    suspend fun deleteTransaction(id: String) = database.portfolioDao().delete(id)

    /** Null on any failure (offline, rate-limited, etc.) — callers fall back to the last-known price. */
    suspend fun getCurrentPriceUsd(): Double? {
        return try {
            coinGeckoApi.getSimplePrice().kaspa["usd"]
        } catch (e: Exception) {
            null
        }
    }

    /** (timestampMillis, priceUsd) pairs, oldest first — empty on failure rather than throwing. */
    suspend fun getPriceHistory(days: Int = 30): List<Pair<Long, Double>> {
        return try {
            coinGeckoApi.getMarketChart(days = days).prices.mapNotNull { point ->
                if (point.size < 2) null else point[0].toLong() to point[1]
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Builds the CSV in app-private cache and returns a content:// URI ready for a share sheet. */
    fun exportCsv(transactions: List<PortfolioTransactionEntity>): Uri {
        val exportDir = File(context.cacheDir, "portfolio_exports").apply { mkdirs() }
        val fileTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
        val csvFile = File(exportDir, "kachat-portfolio-$fileTimestamp.csv")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val csv = buildString {
            append("Type,Date,Quantity (KAS),Total (USD),Notes\n")
            transactions.sortedBy { it.timestampMillis }.forEach { tx ->
                val kas = tx.amountSompi / 100_000_000.0
                val date = dateFormat.format(Date(tx.timestampMillis))
                val notes = (tx.notes ?: "").replace("\"", "\"\"")
                append("${tx.type},$date,$kas,${tx.fiatValue},\"$notes\"\n")
            }
        }
        csvFile.writeText(csv)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
    }
}
