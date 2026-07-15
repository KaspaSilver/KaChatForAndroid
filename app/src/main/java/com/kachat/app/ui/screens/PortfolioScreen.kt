package com.kachat.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.models.PortfolioTransactionEntity
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.PortfolioSummary
import com.kachat.app.viewmodels.PortfolioViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private fun formatUsd(value: Double): String {
    val sign = if (value < 0) "-" else ""
    return "$sign$${String.format(Locale.US, "%,.2f", kotlin.math.abs(value))}"
}

/**
 * For a single coin's price rather than a dollar total — KAS trades under $1, where 2 decimals
 * (CoinGecko rounds $0.0288... to "$0.03") loses essentially all the precision that actually
 * distinguishes one day's price from the next. Sub-$1 prices get 5 decimals instead; anything
 * $1 and up still just gets the usual 2.
 */
private fun formatUsdPrice(value: Double): String {
    val sign = if (value < 0) "-" else ""
    val decimals = if (kotlin.math.abs(value) < 1.0) 5 else 2
    return "$sign$${String.format(Locale.US, "%,.${decimals}f", kotlin.math.abs(value))}"
}

private fun formatKasAmount(kas: Double): String {
    return String.format(Locale.US, "%.8f", kas).trimEnd('0').trimEnd('.')
}

/** "1d"/"7d"/"30d" — matches the PortfolioViewModel.priceRangeDays values in the range switcher. */
private fun priceRangeLabel(days: Int): String = when (days) {
    1 -> "1d"
    7 -> "7d"
    else -> "${days}d"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    navController: NavController,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val priceHistory by viewModel.priceHistory.collectAsState()
    val priceRangeDays by viewModel.priceRangeDays.collectAsState()
    val valueHistory by viewModel.valueHistory.collectAsState()
    val summary by viewModel.summary.collectAsState()
    // (timestamp, price) while scrubbing the price sparkline above, null otherwise — lifted up
    // here (rather than kept local to PriceChartCard) since the summary card below needs it too.
    var scrubbedPrice by remember { mutableStateOf<Pair<Long, Double>?>(null) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Portfolio", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshPrice() }) {
                        Icon(Icons.Default.Refresh, "Refresh price", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PortfolioSummaryCard(summary = summary, currentPriceUsd = currentPriceUsd, scrubbedPrice = scrubbedPrice)
            if (priceHistory.size >= 2) {
                PriceChartCard(
                    priceHistory = priceHistory,
                    onScrub = { scrubbedPrice = it },
                    selectedRangeDays = priceRangeDays,
                    onRangeSelected = { viewModel.setPriceRangeDays(it) }
                )
            }
            if (valueHistory.size >= 2) {
                PortfolioValueChartCard(valueHistory = valueHistory)
            }
            PortfolioNavRow(
                icon = Icons.Default.Receipt,
                label = "Transactions",
                onClick = { navController.navigate("portfolio_transactions") }
            )
            PortfolioNavRow(
                icon = Icons.Default.QrCodeScanner,
                label = "Cold Storage Devices",
                onClick = { navController.navigate("cold_storage") }
            )
        }
    }
}

/** Dark rounded-card row with a chevron — matches the "Cold Storage Devices" row style used elsewhere (Profile, Edit KNS Profile's Domains box). */
@Composable
private fun PortfolioNavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
    }
}

/**
 * The transaction ledger, broken out from the main Portfolio screen (which now just links here)
 * so that screen can stay focused on price/value at a glance — this owns the list, CSV import/
 * export, and the add/edit/delete dialog. Shares PortfolioScreen's own PortfolioViewModel
 * instance (see KaChatApp.kt's "portfolio_transactions" route) rather than a fresh one, so a
 * transaction added/edited/deleted here is immediately reflected in the summary card and charts
 * back on Portfolio without a redundant re-fetch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioTransactionsScreen(
    onBack: () -> Unit,
    viewModel: PortfolioViewModel = hiltViewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val currentPriceUsd by viewModel.currentPriceUsd.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<PortfolioTransactionEntity?>(null) }
    var showCsvMenu by remember { mutableStateOf(false) }

    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importCsv(uri) { result ->
                val message = result.fold(
                    onSuccess = { count -> "Imported $count transaction${if (count == 1) "" else "s"}" },
                    onFailure = { "Import failed — check the CSV format" }
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Transactions", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showCsvMenu = true }) {
                            Icon(Icons.Default.ImportExport, "Import or export CSV", tint = KaspaTeal)
                        }
                        DropdownMenu(expanded = showCsvMenu, onDismissRequest = { showCsvMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export CSV") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = {
                                    showCsvMenu = false
                                    viewModel.exportCsv { uri ->
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Export Portfolio CSV"))
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import CSV") },
                                leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                onClick = {
                                    showCsvMenu = false
                                    importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, "Add transaction")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (transactions.isEmpty()) {
                item {
                    Text(
                        "No transactions yet. Tap + to add your first buy or sell.",
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(transactions.reversed()) { tx ->
                    TransactionRow(
                        tx = tx,
                        onClick = { editingTransaction = tx },
                        onDelete = { viewModel.deleteTransaction(tx.id) }
                    )
                }
            }
        }
    }

    if (showAddDialog || editingTransaction != null) {
        val existing = editingTransaction
        TransactionDialog(
            existing = existing,
            currentPriceUsd = currentPriceUsd,
            onDismiss = {
                showAddDialog = false
                editingTransaction = null
            },
            onSave = { type, amountKas, fiatValue, timestampMillis, notes ->
                if (existing != null) {
                    viewModel.updateTransaction(existing.id, type, amountKas, fiatValue, timestampMillis, notes)
                } else {
                    viewModel.addTransaction(type, amountKas, fiatValue, timestampMillis, notes)
                }
                showAddDialog = false
                editingTransaction = null
            },
            onDelete = existing?.let { tx ->
                {
                    viewModel.deleteTransaction(tx.id)
                    showAddDialog = false
                    editingTransaction = null
                }
            }
        )
    }
}

@Composable
private fun PortfolioSummaryCard(summary: PortfolioSummary, currentPriceUsd: Double?, scrubbedPrice: Pair<Long, Double>? = null) {
    val plColor = if (summary.totalPL >= 0) Color(0xFF4CD964) else Color(0xFFFF3B30)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .padding(20.dp)
    ) {
        Text(
            if (scrubbedPrice != null) formatDateTime(scrubbedPrice.first) else "KAS Price",
            color = Color.Gray,
            fontSize = 12.sp
        )
        Text(
            text = when {
                scrubbedPrice != null -> formatUsdPrice(scrubbedPrice.second)
                currentPriceUsd != null -> formatUsdPrice(currentPriceUsd)
                else -> "—"
            },
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Holdings", color = Color.Gray, fontSize = 12.sp)
                Text("${formatKasAmount(summary.holdingsKas)} KAS", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Current Value", color = Color.Gray, fontSize = 12.sp)
                Text(formatUsd(summary.currentValue), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.HorizontalDivider(color = Color.Black.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Total Invested", color = Color.Gray, fontSize = 12.sp)
                Text(formatUsd(summary.totalInvested), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total P&L", color = Color.Gray, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (summary.totalPL >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        tint = plColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${formatUsd(summary.totalPL)} (${String.format(Locale.US, "%.1f", summary.totalPLPercent)}%)",
                        color = plColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Compact sparkline — just enough to show the price trend at a glance above the summary card. */
@Composable
private fun PriceChartCard(
    priceHistory: List<Pair<Long, Double>>,
    onScrub: (Pair<Long, Double>?) -> Unit,
    selectedRangeDays: Int,
    onRangeSelected: (Int) -> Unit
) {
    var canvasWidthPx by remember { mutableStateOf(0) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.clickable {
                // Cycles 1 -> 7 -> 30 -> 1 day...
                val nextDays = when (selectedRangeDays) {
                    1 -> 7
                    7 -> 30
                    else -> 1
                }
                onRangeSelected(nextDays)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Price (${priceRangeLabel(selectedRangeDays)})",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        val minPrice = priceHistory.minOf { it.second }
        val maxPrice = priceHistory.maxOf { it.second }
        val range = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .onSizeChanged { canvasWidthPx = it.width }
                .pointerInput(priceHistory) {
                    fun scrubAt(x: Float) {
                        if (canvasWidthPx <= 0) return
                        val index = ((x / canvasWidthPx) * (priceHistory.size - 1)).roundToInt().coerceIn(0, priceHistory.size - 1)
                        selectedIndex = index
                        onScrub(priceHistory[index])
                    }
                    detectDragGestures(
                        onDragStart = { offset -> scrubAt(offset.x) },
                        onDrag = { change, _ -> scrubAt(change.position.x); change.consume() },
                        onDragEnd = { selectedIndex = null; onScrub(null) },
                        onDragCancel = { selectedIndex = null; onScrub(null) }
                    )
                }
        ) {
            val stepX = size.width / (priceHistory.size - 1).coerceAtLeast(1)
            val path = Path()
            priceHistory.forEachIndexed { index, (_, price) ->
                val x = index * stepX
                val y = size.height - ((price - minPrice) / range * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = KaspaTeal, style = Stroke(width = 3f))

            selectedIndex?.let { index ->
                val x = index * stepX
                val y = size.height - ((priceHistory[index].second - minPrice) / range * size.height).toFloat()
                drawLine(color = Color.Gray, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
                drawCircle(color = KaspaTeal, radius = 4f, center = Offset(x, y))
            }
        }
    }
}

/**
 * Holdings' USD value over time, not price — touch and drag horizontally to scrub through
 * history; the header above the chart swaps to show the value/date under your finger while
 * dragging, and reverts to the latest value on release.
 */
@Composable
private fun PortfolioValueChartCard(valueHistory: List<Pair<Long, Double>>) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val minValue = valueHistory.minOf { it.second }
    val maxValue = valueHistory.maxOf { it.second }
    val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0

    val selectedIndex = touchX?.let { x ->
        if (canvasSize.width <= 0) null
        else ((x / canvasSize.width) * (valueHistory.size - 1)).roundToInt().coerceIn(0, valueHistory.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .padding(16.dp)
    ) {
        val (headerLabel, headerTimestamp, headerValue) = if (selectedIndex != null) {
            val (ts, value) = valueHistory[selectedIndex]
            Triple("Value on ${formatDateTime(ts)}", ts, value)
        } else {
            Triple("Value Over Time", valueHistory.last().first, valueHistory.last().second)
        }
        Text(headerLabel, color = Color.Gray, fontSize = 12.sp)
        Text(formatUsd(headerValue), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .onSizeChanged { canvasSize = it }
                .pointerInput(valueHistory) {
                    detectDragGestures(
                        onDragStart = { offset -> touchX = offset.x },
                        onDrag = { change, _ -> touchX = change.position.x; change.consume() },
                        onDragEnd = { touchX = null },
                        onDragCancel = { touchX = null }
                    )
                }
        ) {
            val stepX = size.width / (valueHistory.size - 1).coerceAtLeast(1)
            val path = Path()
            valueHistory.forEachIndexed { index, (_, value) ->
                val x = index * stepX
                val y = size.height - ((value - minValue) / range * size.height).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = KaspaTeal, style = Stroke(width = 4f))

            if (selectedIndex != null) {
                val x = selectedIndex * stepX
                val y = size.height - ((valueHistory[selectedIndex].second - minValue) / range * size.height).toFloat()
                drawLine(color = Color.Gray, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 2f)
                drawCircle(color = KaspaTeal, radius = 6f, center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: PortfolioTransactionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val isBuy = tx.type == "buy"
    val amountKas = tx.amountSompi / 100_000_000.0
    val dateStr = remember(tx.timestampMillis) {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(tx.timestampMillis))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isBuy) Color(0xFF4CD964).copy(alpha = 0.15f) else Color(0xFFFF3B30).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isBuy) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                    contentDescription = null,
                    tint = if (isBuy) Color(0xFF4CD964) else Color(0xFFFF3B30),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (isBuy) "Buy" else "Sell", color = Color.White, fontWeight = FontWeight.Bold)
                Text(dateStr, color = Color.Gray, fontSize = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${formatKasAmount(amountKas)} KAS", color = Color.White)
            Text(formatUsd(tx.fiatValue), color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Delete, "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDialog(
    existing: PortfolioTransactionEntity?,
    currentPriceUsd: Double?,
    onDismiss: () -> Unit,
    onSave: (type: String, amountKas: Double, fiatValue: Double, timestampMillis: Long, notes: String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var isBuy by remember { mutableStateOf(existing?.let { it.type == "buy" } ?: true) }
    var quantityText by remember {
        mutableStateOf(existing?.let { formatKasAmount(it.amountSompi / 100_000_000.0) } ?: "")
    }
    // Editing: derive price-per-coin from the stored total rather than the live price, so
    // reopening an old entry shows what was actually paid, not today's price. Fee isn't stored
    // separately (see PortfolioRepository), so it isn't recoverable into its own field here —
    // the derived price-per-coin already nets it out, and the total still matches exactly
    // unless the user changes quantity/price/fee themselves.
    var priceText by remember {
        mutableStateOf(
            existing?.let {
                val kas = it.amountSompi / 100_000_000.0
                if (kas > 0) String.format(Locale.US, "%.8f", it.fiatValue / kas).trimEnd('0').trimEnd('.') else ""
            } ?: currentPriceUsd?.let { String.format(Locale.US, "%.8f", it).trimEnd('0').trimEnd('.') } ?: ""
        )
    }
    var feeText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf(existing?.notes ?: "") }
    var timestampMillis by remember { mutableStateOf(existing?.timestampMillis ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val quantity = quantityText.toDoubleOrNull()
    val pricePerCoin = priceText.toDoubleOrNull()
    val fee = feeText.toDoubleOrNull() ?: 0.0
    val total = if (quantity != null && pricePerCoin != null) {
        val base = quantity * pricePerCoin
        if (isBuy) base + fee else base - fee
    } else null
    val isValid = quantity != null && quantity > 0 && pricePerCoin != null && pricePerCoin > 0

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing != null) "Edit Transaction" else "Add Transaction", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Gray)
                    }
                }
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Buy" to true, "Sell" to false).forEach { (label, value) ->
                        Surface(
                            color = if (isBuy == value) KaspaTeal else Color(0xFF2C2C2E),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).clickable { isBuy = value }
                        ) {
                            Text(
                                label,
                                color = if (isBuy == value) Color.Black else Color.White,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Static — this tracker is KAS-only (see PortfolioTransactionEntity's doc comment).
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MonetizationOn, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Kaspa", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("KAS", color = Color.Gray)
                }
                Spacer(Modifier.height(12.dp))

                // Full width, stacked rather than side-by-side — a half-width field cut off KAS
                // prices with several decimal digits (e.g. "0.02874099"), which didn't fit next
                // to Quantity in a shared row and just clipped at the field's edge.
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Price Per Coin") },
                    leadingIcon = { Text("$", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = Color(0xFF2C2C2E),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { showDatePicker = true }
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarToday, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(formatDateTime(timestampMillis), color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it },
                    label = { Text("Fee (USD, optional)") },
                    leadingIcon = { Text("$", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2C2C2E))
                        .padding(16.dp)
                ) {
                    Text(if (isBuy) "Total Spent" else "Total Received", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        text = if (total != null) formatUsd(total) else "$0",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (isValid) onSave(if (isBuy) "buy" else "sell", quantity!!, total ?: 0.0, timestampMillis, notesText.ifBlank { null })
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = Color(0xFF2C2C2E)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (existing != null) "Save Changes" else "Add Transaction",
                        color = if (isValid) Color.Black else Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (onDelete != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Delete Transaction", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DateTimePickerFlow(
            initialMillis = timestampMillis,
            onDismiss = { showDatePicker = false },
            onConfirm = { millis ->
                timestampMillis = millis
                showDatePicker = false
            }
        )
    }
}

/** Date picker first, then a time picker, merged into one epoch-millis value in the local timezone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerFlow(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var pickingTime by remember { mutableStateOf(false) }
    var pickedDateMillis by remember { mutableStateOf(initialMillis) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    val initialCal = remember(initialMillis) { Calendar.getInstance().apply { timeInMillis = initialMillis } }
    val timeState = rememberTimePickerState(
        initialHour = initialCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = initialCal.get(Calendar.MINUTE)
    )

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    pickedDateMillis = dateState.selectedDateMillis ?: initialMillis
                    pickingTime = true
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        ) {
            DatePicker(state = dateState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Select Time", color = Color.White) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker's selectedDateMillis is UTC midnight of the chosen day — pull the
                    // year/month/day out in UTC, then build the final instant in the local timezone
                    // with the picked time-of-day, so this doesn't silently shift a day depending on
                    // the device's offset from UTC.
                    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedDateMillis }
                    val merged = Calendar.getInstance().apply {
                        set(
                            utcCal.get(Calendar.YEAR),
                            utcCal.get(Calendar.MONTH),
                            utcCal.get(Calendar.DAY_OF_MONTH),
                            timeState.hour,
                            timeState.minute,
                            0
                        )
                        set(Calendar.MILLISECOND, 0)
                    }
                    onConfirm(merged.timeInMillis)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }
}
