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

    /**
     * Parses a CSV in the format written by [exportCsv] (header + Type,Date,Quantity (KAS),Total
     * (USD),Notes rows) and inserts each row as a new transaction. Malformed rows are skipped
     * rather than aborting the whole import, since one bad line from a hand-edited file shouldn't
     * cost the rest. Returns the number of rows successfully imported.
     */
    suspend fun importCsv(uri: Uri): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: emptyList()
        var imported = 0
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val fields = parseCsvLine(line)
            if (fields.size < 4) continue
            val type = fields[0].trim().lowercase()
            if (type != "buy" && type != "sell") continue
            try {
                val timestampMillis = dateFormat.parse(fields[1].trim())?.time ?: continue
                val amountSompi = (fields[2].trim().toDouble() * 100_000_000).toLong()
                val fiatValue = fields[3].trim().toDouble()
                val notes = fields.getOrNull(4)?.takeIf { it.isNotEmpty() }
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
                imported++
            } catch (e: Exception) {
                continue
            }
        }
        return imported
    }

    /** Splits on commas outside double quotes, and unescapes "" back to " within a quoted field. */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
