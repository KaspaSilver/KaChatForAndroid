package com.kachat.app.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.services.CoinGeckoApi
import com.kachat.app.services.database.KaChatDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

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

    // -------------------------------------------------------------------------
    // CSV (CoinMarketCap "Transaction History" format)
    // -------------------------------------------------------------------------
    //
    // Column order matches CoinMarketCap's portfolio Transaction History export exactly:
    // Date (UTC±H:MM),Token,Type,Price (USD),Amount,Total value (USD),Fee,Fee Currency,Notes
    // — so a file exported from CoinMarketCap imports here unmodified, and a file exported from
    // here imports back into CoinMarketCap unmodified. Mirrors iOS's PortfolioViewModel exactly.

    private val trackedToken = "KAS"

    /**
     * CoinMarketCap formats numeric columns with thousands-separator commas above 999 (e.g.
     * "10,597.25", "6,093,184.09"), which plain toDouble() rejects outright — parsing every such
     * row would otherwise silently fail and get skipped. Strips those before parsing.
     */
    private fun parseLenientDouble(raw: String): Double? =
        raw.trim().replace(",", "").toDoubleOrNull()

    private fun makeDateFormat(timeZone: TimeZone): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            isLenient = false
            this.timeZone = timeZone
        }

    /**
     * CoinMarketCap bakes the exporting user's local UTC offset into the date column's own
     * header name (e.g. "Date (UTC-4:00)") rather than into each row, so the offset has to be
     * parsed once from the header before any row's timestamp can be interpreted correctly. Falls
     * back to UTC if the header doesn't look like CoinMarketCap's (or is missing).
     */
    private fun parseHeaderUtcOffset(header: String): TimeZone {
        val utcTimeZone = TimeZone.getTimeZone("UTC")
        val utcIndex = header.indexOf("UTC", ignoreCase = true)
        if (utcIndex == -1) return utcTimeZone
        val afterUtc = utcIndex + 3
        val closeParen = header.indexOf(')', afterUtc)
        if (closeParen == -1) return utcTimeZone
        val offsetString = header.substring(afterUtc, closeParen).trim()
        val parts = offsetString.split(":")
        if (parts.size != 2) return utcTimeZone
        val hours = parts[0].toIntOrNull() ?: return utcTimeZone
        val minutes = parts[1].toIntOrNull() ?: return utcTimeZone
        val sign = if (offsetString.startsWith("-")) -1 else 1
        val offsetMillis = sign * (abs(hours) * 3600 + minutes * 60) * 1000
        return SimpleTimeZone(offsetMillis, "CMC-IMPORT")
    }

    /**
     * Builds a CoinMarketCap-compatible CSV in app-private cache and returns a content:// URI
     * ready for a share sheet. Rows are exported in ascending timestamp order, always in UTC
     * (spelled out in the header) so re-importing never depends on the exporting device's local
     * timezone. Fee / Fee Currency are written as zero/USD — the ledger doesn't keep fee as a
     * separate line item; any fee captured at import time is already folded into Total value
     * (USD).
     */
    fun exportCsv(transactions: List<PortfolioTransactionEntity>): Uri {
        val exportDir = File(context.cacheDir, "portfolio_exports").apply { mkdirs() }
        val fileTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
        val csvFile = File(exportDir, "kachat-portfolio-$fileTimestamp.csv")

        val dateFormat = makeDateFormat(TimeZone.getTimeZone("UTC"))
        val csv = buildString {
            append("Date (UTC+0:00),Token,Type,Price (USD),Amount,Total value (USD),Fee,Fee Currency,Notes\n")
            transactions.sortedBy { it.timestampMillis }.forEach { tx ->
                val kas = tx.amountSompi / 100_000_000.0
                val price = if (kas != 0.0) tx.fiatValue / kas else 0.0
                val date = dateFormat.format(Date(tx.timestampMillis))
                val notes = (tx.notes ?: "").replace("\"", "\"\"")
                append("\"$date\",\"$trackedToken\",\"${tx.type}\",\"$price\",\"$kas\",\"${tx.fiatValue}\",\"0.00\",\"USD\",\"$notes\"\n")
            }
        }
        csvFile.writeText(csv)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
    }

    /**
     * Parses a CoinMarketCap "Transaction History" CSV — same column order [exportCsv] writes,
     * so real CoinMarketCap exports import here directly too. Only rows for the tracked token
     * (KAS) are imported; other tokens in a mixed-portfolio CMC export are silently skipped, as
     * are malformed rows and unsupported Type values (only buy/sell are tracked). Fee is folded
     * into Total value (USD) when the fee is itself denominated in USD — added for buys,
     * subtracted for sells — since the ledger doesn't track fee as a separate line item. A row
     * whose timestamp exactly matches an existing transaction replaces it in place (same id, new
     * data) rather than adding a duplicate — re-importing a corrected or re-exported CSV updates
     * the ledger instead of piling up copies. Returns the number of rows imported or replaced.
     */
    suspend fun importCsv(uri: Uri): Int {
        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return 0
        val lines = content.split("\r\n", "\n", "\r").toMutableList()
        if (lines.isEmpty()) return 0
        val header = lines.removeAt(0)
        val dateFormat = makeDateFormat(parseHeaderUtcOffset(header))

        val existing = database.portfolioDao().getTransactions().first()
        val idByTimestamp = existing.associate { it.timestampMillis to it.id }.toMutableMap()

        var imported = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val fields = parseCsvLine(line)
            if (fields.size < 6) continue

            val token = fields[1].trim()
            if (!token.equals(trackedToken, ignoreCase = true)) continue

            val type = fields[2].trim().lowercase()
            if (type != "buy" && type != "sell") continue
            val timestampMillis = try { dateFormat.parse(fields[0].trim())?.time } catch (e: Exception) { null } ?: continue
            val kas = parseLenientDouble(fields[4]) ?: continue
            val totalValue = parseLenientDouble(fields[5]) ?: continue

            var fiatValue = totalValue
            if (fields.size > 7) {
                val feeCurrency = fields[7].trim()
                if (feeCurrency.equals("USD", ignoreCase = true)) {
                    val fee = parseLenientDouble(fields[6])
                    if (fee != null) {
                        fiatValue = if (type == "buy") fiatValue + fee else maxOf(fiatValue - fee, 0.0)
                    }
                }
            }

            val notes = if (fields.size > 8 && fields[8].isNotEmpty()) fields[8] else null
            val amountSompi = (kas * 100_000_000).roundToLong()

            val existingId = idByTimestamp[timestampMillis]
            if (existingId != null) {
                updateTransaction(existingId, type, amountSompi, fiatValue, timestampMillis, notes)
            } else {
                val newId = UUID.randomUUID().toString()
                database.portfolioDao().insert(
                    PortfolioTransactionEntity(
                        id = newId,
                        type = type,
                        amountSompi = amountSompi,
                        fiatValue = fiatValue,
                        timestampMillis = timestampMillis,
                        notes = notes
                    )
                )
                idByTimestamp[timestampMillis] = newId
            }
            imported++
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
