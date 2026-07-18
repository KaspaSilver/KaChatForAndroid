package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.R
import com.kachat.app.models.SwapCoin
import com.kachat.app.models.SwapTransactionEntity
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.SwapViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** KAS <-> USDC (Polygon) swaps, powered by ChangeNOW — see [SwapViewModel] and [SwapRepository][com.kachat.app.repository.SwapRepository]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwapScreen(
    navController: androidx.navigation.NavController? = null,
    swapViewModel: SwapViewModel = hiltViewModel()
) {
    val kasIsSendSide by swapViewModel.kasIsSendSide.collectAsState()
    val otherCoin by swapViewModel.otherCoin.collectAsState()
    val amountText by swapViewModel.amountText.collectAsState()
    val payoutAddressText by swapViewModel.payoutAddressText.collectAsState()
    val estimateState by swapViewModel.estimateState.collectAsState()
    val createSwapState by swapViewModel.createSwapState.collectAsState()
    val spendingBalanceSompi by swapViewModel.spendingBalanceSompi.collectAsState()
    val selectedFromAddress by swapViewModel.selectedFromAddress.collectAsState()
    val toAddress by swapViewModel.toAddress.collectAsState()
    val feeRateOverrideSompi by swapViewModel.feeRateOverrideSompi.collectAsState()
    val swapHistory by swapViewModel.swapHistory.collectAsState()
    val swapDisclaimerAgreed by swapViewModel.swapDisclaimerAgreed.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val pagerScope = rememberCoroutineScope()
    var selectedSwapId by remember { mutableStateOf<String?>(null) }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    val selectedSwap = swapHistory.find { it.id == selectedSwapId }

    // Same simplified single-input, two-output shape Manage Addresses' withdraw dialog estimates
    // against — the swap's KAS leg is a plain send to ChangeNOW's deposit address, same shape.
    val estimatedMass = remember {
        com.kachat.app.util.KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
    }
    val defaultFeeSompi = com.kachat.app.util.KaspaMass.calculateFee(estimatedMass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
    val effectiveFeeSompi = feeRateOverrideSompi?.let { com.kachat.app.util.KaspaMass.calculateFee(estimatedMass, it) } ?: defaultFeeSompi

    val savedStateHandle = navController?.currentBackStackEntry?.savedStateHandle
    val pickedFromIndex = savedStateHandle?.getStateFlow<Int?>("picked_from_index", null)?.collectAsState()
    LaunchedEffect(pickedFromIndex?.value) {
        val index = pickedFromIndex?.value ?: return@LaunchedEffect
        val balance = savedStateHandle?.get<Long>("picked_from_balance") ?: 0L
        swapViewModel.selectFromSpendingAddress(index, balance)
        savedStateHandle?.remove<Int>("picked_from_index")
        savedStateHandle?.remove<Long>("picked_from_balance")
    }
    val pickedToIndex = savedStateHandle?.getStateFlow<Int?>("picked_to_index", null)?.collectAsState()
    LaunchedEffect(pickedToIndex?.value) {
        val index = pickedToIndex?.value ?: return@LaunchedEffect
        swapViewModel.selectToSpendingAddress(index)
        savedStateHandle?.remove<Int>("picked_to_index")
    }

    val toCoinForDisplay = if (kasIsSendSide) otherCoin else com.kachat.app.models.KAS_SWAP_COIN
    val needsPayoutAddress = toCoinForDisplay.ticker != "kas"

    LaunchedEffect(createSwapState.status) {
        if (createSwapState.status == SwapViewModel.CreateSwapStatus.SUCCESS) {
            Toast.makeText(context, "Swap started", Toast.LENGTH_SHORT).show()
        }
        if (createSwapState.status == SwapViewModel.CreateSwapStatus.FAILED) {
            Toast.makeText(context, createSwapState.errorMessage ?: "Swap failed", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("Swap", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
                )
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = LocalAppColors.current.background,
                    contentColor = KaspaTeal
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { pagerScope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("Swap", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text("Swap History", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
        if (page == 1) {
            SwapHistoryPage(
                swapHistory = swapHistory,
                onSwapClick = { selectedSwapId = it },
                onSwapDelete = { swapViewModel.deleteSwap(it) }
            )
            return@HorizontalPager
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SwapAmountCard(
                label = "You Send",
                coin = if (kasIsSendSide) com.kachat.app.models.KAS_SWAP_COIN else otherCoin,
                coinLabel = if (kasIsSendSide) "KAS" else otherCoin.displayName,
                amountText = amountText,
                onAmountChange = { swapViewModel.setAmountText(it) },
                editable = true,
                onMaxClick = if (kasIsSendSide) {
                    {
                        val maxSompi = (spendingBalanceSompi - effectiveFeeSompi).coerceAtLeast(0L)
                        swapViewModel.setAmountText("%.8f".format(Locale.US, maxSompi / 100_000_000.0))
                    }
                } else null
            )

            if (kasIsSendSide) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable(enabled = navController != null) {
                            navController?.navigate("manage_addresses_pick/from")
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Available", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "%.8f KAS (${if (selectedFromAddress != null) "Address #${selectedFromAddress?.index}" else "Primary"})"
                                .format(Locale.US, spendingBalanceSompi / 100_000_000.0),
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Fee: %.8f KAS".format(Locale.US, effectiveFeeSompi / 100_000_000.0),
                            color = KaspaTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                feeEditorInput = "%.8f".format(Locale.US, effectiveFeeSompi / 100_000_000.0)
                                showFeeEditor = true
                            }
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Change",
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                }
            }

            // Flip control and the primary CTA share a row instead of the CTA sitting in its own
            // full-width button further down — keeps the whole form on screen without scrolling.
            val isBusy = createSwapState.status == SwapViewModel.CreateSwapStatus.SENDING_KAS ||
                createSwapState.status == SwapViewModel.CreateSwapStatus.CREATING
            val amountSompi = amountText.toDoubleOrNull()?.let { Math.round(it * 100_000_000.0) } ?: 0L
            val insufficientFunds = kasIsSendSide && amountSompi > spendingBalanceSompi
            val canSwap = estimateState.status == SwapViewModel.EstimateStatus.SUCCESS && !isBusy && !insufficientFunds
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { swapViewModel.flipDirection() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(KaspaTeal)
                ) {
                    Icon(Icons.Default.SwapVert, "Switch direction", tint = Color.Black)
                }
                Button(
                    onClick = { swapViewModel.executeSwap() },
                    enabled = canSwap,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text(
                            when {
                                insufficientFunds -> "Insufficient Funds"
                                kasIsSendSide -> "Swap"
                                else -> "Get Deposit Address"
                            },
                            color = if (canSwap) Color.Black else LocalAppColors.current.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            val estimatedAmountText = when (estimateState.status) {
                SwapViewModel.EstimateStatus.SUCCESS -> "%.8f".format(Locale.US, estimateState.toAmount ?: 0.0)
                SwapViewModel.EstimateStatus.LOADING -> "..."
                else -> ""
            }
            SwapAmountCard(
                label = "You Get",
                coin = if (kasIsSendSide) otherCoin else com.kachat.app.models.KAS_SWAP_COIN,
                coinLabel = if (kasIsSendSide) otherCoin.displayName else "KAS",
                amountText = estimatedAmountText,
                onAmountChange = {},
                editable = false
            )

            if (needsPayoutAddress) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = payoutAddressText,
                    onValueChange = { swapViewModel.setPayoutAddressText(it) },
                    label = { Text("Receive ${toCoinForDisplay.displayName} at") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.surfaceVariant,
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!kasIsSendSide) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable(enabled = navController != null) {
                            navController?.navigate("manage_addresses_pick/to")
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Receiving KAS At", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (toAddress.length > 20) "${toAddress.take(12)}...${toAddress.takeLast(6)}" else toAddress,
                            color = LocalAppColors.current.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Change",
                        color = KaspaTeal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val isEstimateFailed = estimateState.status == SwapViewModel.EstimateStatus.FAILED
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(10.dp)
            ) {
                Text("Rate", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                val rateText = if (estimateState.status == SwapViewModel.EstimateStatus.SUCCESS) {
                    val fromAmount = amountText.toDoubleOrNull() ?: 0.0
                    val toAmount = estimateState.toAmount ?: 0.0
                    if (fromAmount > 0) {
                        val fromLabel = if (kasIsSendSide) "KAS" else otherCoin.displayName
                        val toLabel = if (kasIsSendSide) otherCoin.displayName else "KAS"
                        "1 $fromLabel ≈ %.8f $toLabel".format(Locale.US, toAmount / fromAmount)
                    } else "N/A"
                } else if (isEstimateFailed) {
                    estimateState.errorMessage ?: "Unavailable"
                } else "N/A"
                Text(
                    rateText,
                    color = if (isEstimateFailed) Color(0xFFFF3B30) else LocalAppColors.current.textSecondary,
                    fontSize = 12.sp
                )
            }

            createSwapState.result?.let { result ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = LocalAppColors.current.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (kasIsSendSide) "KAS sent, exchange in progress" else "Send ${otherCoin.displayName} to this address",
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        if (!kasIsSendSide) {
                            // Other coin -> KAS: this device can't send that coin itself, so the
                            // user needs to pay into the deposit address from wherever they hold it.
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val qrPainter = rememberQrBitmapPainter(result.payinAddress ?: "")
                                Box(
                                    modifier = Modifier
                                        .size(180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(qrPainter, "Deposit address QR", modifier = Modifier.fillMaxSize())
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    result.payinAddress?.let {
                                        clipboardManager.setText(AnnotatedString(it))
                                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                result.payinAddress ?: "",
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Status: ${result.status ?: "new"}", color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("ChangeNOW Exchange ID", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        Text(
                            result.id,
                            color = LocalAppColors.current.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(result.id))
                                    Toast.makeText(context, "Exchange ID copied", Toast.LENGTH_SHORT).show()
                                }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "Refresh Status",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { swapViewModel.refreshSwapStatus(result.id) }
                            )
                            Text(
                                "View on ChangeNOW",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://changenow.io/exchange/txs/${result.id}")
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Powered by ChangeNOW",
                color = KaspaTeal,
                fontSize = 12.sp,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://changenow.io/terms-of-use/changenow-terms")
                            )
                        )
                    },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))
        }
        }
    }

    selectedSwap?.let { swap ->
        SwapDetailDialog(
            swap = swap,
            onDismiss = { selectedSwapId = null },
            onRefresh = { swapViewModel.refreshSwapStatus(swap.id) },
            onAddToPortfolio = {
                val isKasReceived = swap.toTicker == "kas"
                val amountKas = (if (isKasReceived) swap.toAmount else swap.fromAmount).toDoubleOrNull()
                val fiatValue = (if (isKasReceived) swap.fromAmount else swap.toAmount).toDoubleOrNull()
                if (amountKas == null || fiatValue == null) {
                    Toast.makeText(context, "Couldn't read this swap's amounts", Toast.LENGTH_SHORT).show()
                } else {
                    val notes = android.net.Uri.encode("ChangeNOW swap ${swap.id}")
                    selectedSwapId = null
                    navController?.navigate(
                        "portfolio_transactions?prefillType=${if (isKasReceived) "buy" else "sell"}" +
                            "&prefillAmountKas=$amountKas&prefillFiatValue=$fiatValue" +
                            "&prefillTimestamp=${swap.createdAtMillis}&prefillNotes=$notes&prefillSwapId=${swap.id}"
                    )
                }
            }
        )
    }

    if (showFeeEditor) {
        AlertDialog(
            onDismissRequest = { showFeeEditor = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Adjust Network Fee", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        "If the network is busy, a higher fee can help your transaction confirm faster.",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = feeEditorInput,
                        onValueChange = { feeEditorInput = it },
                        label = { Text("Fee (KAS)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Default: %.8f KAS".format(Locale.US, defaultFeeSompi / 100_000_000.0),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    swapViewModel.setFeeRateOverride(
                        if (kas != null && kas > 0) {
                            val desiredFeeSompi = Math.round(kas * 100_000_000.0)
                            kotlin.math.ceil(desiredFeeSompi.toDouble() / estimatedMass).toLong()
                        } else {
                            null
                        }
                    )
                    showFeeEditor = false
                }) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        swapViewModel.setFeeRateOverride(null)
                        showFeeEditor = false
                    }) {
                        Text("Use Default", color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text("Cancel", color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }

    if (!swapDisclaimerAgreed) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = LocalAppColors.current.surface,
            title = { Text("Before You Swap", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Swaps are processed by ChangeNOW, a third-party exchange. By continuing, you " +
                        "confirm you've read and agree to ChangeNOW's own Terms of Service. KaChat only " +
                        "submits your swap request and displays its status; KaChat is not responsible for " +
                        "failed, delayed, or lost swaps. If a swap doesn't go through, contact ChangeNOW " +
                        "support directly.",
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { swapViewModel.agreeToSwapDisclaimer() }) {
                    Text("I Agree", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { navController?.popBackStack() }) {
                    Text("Not Now", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/** Full detail for one past swap — its deposit QR again, live-ish status, the ChangeNOW exchange id, and a link to track it on changenow.io. */
@Composable
private fun SwapDetailDialog(
    swap: SwapTransactionEntity,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onAddToPortfolio: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                val toAmountText = swap.toAmount.toDoubleOrNull()?.let { "%.8f".format(Locale.US, it) } ?: swap.toAmount
                Text(
                    "${swap.fromAmount} ${swap.fromTicker.uppercase()} → $toAmountText ${swap.toTicker.uppercase()}",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    remember(swap.createdAtMillis) {
                        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(swap.createdAtMillis))
                    },
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val qrPainter = rememberQrBitmapPainter(swap.payinAddress)
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(qrPainter, "Deposit address QR", modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("Deposit Address", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(
                    swap.payinAddress,
                    color = LocalAppColors.current.textPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(swap.payinAddress))
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                        }
                )
                Spacer(Modifier.height(12.dp))

                Text("Status", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        swap.status.replaceFirstChar { it.uppercase() },
                        color = when (swap.status) {
                            "finished" -> Color(0xFF4CD964)
                            "failed", "refunded" -> Color(0xFFFF3B30)
                            else -> Color(0xFFF39C12)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (swap.status == "finished") {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Add to Portfolio",
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable(onClick = onAddToPortfolio)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("ChangeNOW Exchange ID", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(
                    swap.id,
                    color = LocalAppColors.current.textPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(swap.id))
                            Toast.makeText(context, "Exchange ID copied", Toast.LENGTH_SHORT).show()
                        }
                )
                Spacer(Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Refresh Status",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = onRefresh)
                    )
                    Text(
                        "View on ChangeNOW",
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://changenow.io/exchange/txs/${swap.id}")
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

/** KAS gets the real brand mark; anything else is shown as its coin icon with a small network badge in the corner. */
@Composable
private fun CoinIcon(coin: SwapCoin, size: Dp = 28.dp) {
    if (coin.ticker == "kas") {
        Image(
            painterResource(R.drawable.ic_kaspa_logo),
            contentDescription = coin.displayName,
            modifier = Modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(modifier = Modifier.size(size)) {
            Image(
                painterResource(R.drawable.ic_usdc_coin),
                contentDescription = coin.displayName,
                modifier = Modifier.size(size).clip(CircleShape)
            )
            Image(
                painterResource(R.drawable.ic_polygon_network),
                contentDescription = "Polygon network",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.5f)
                    .clip(CircleShape)
                    .border(1.dp, LocalAppColors.current.surface, CircleShape)
            )
        }
    }
}

/** Full-page swap history — its own pager page rather than a collapsible section, so it's a normal-height scrollable list. */
@Composable
private fun SwapHistoryPage(swapHistory: List<SwapTransactionEntity>, onSwapClick: (String) -> Unit, onSwapDelete: (String) -> Unit) {
    if (swapHistory.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No swaps yet.", color = LocalAppColors.current.textSecondary, textAlign = TextAlign.Center)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Surface(
            color = LocalAppColors.current.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                swapHistory.forEachIndexed { index, swap ->
                    SwapHistoryRow(swap, onClick = { onSwapClick(swap.id) }, onDelete = { onSwapDelete(swap.id) })
                    if (index < swapHistory.lastIndex) {
                        HorizontalDivider(color = LocalAppColors.current.divider)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwapHistoryRow(swap: SwapTransactionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val toAmountText = swap.toAmount.toDoubleOrNull()?.let { "%.8f".format(Locale.US, it) } ?: swap.toAmount
            Text(
                "${swap.fromAmount} ${swap.fromTicker.uppercase()} → $toAmountText ${swap.toTicker.uppercase()}",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                remember(swap.createdAtMillis) {
                    SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(swap.createdAtMillis))
                },
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            swap.status.replaceFirstChar { it.uppercase() },
            color = when (swap.status) {
                "finished" -> Color(0xFF4CD964)
                "failed", "refunded" -> Color(0xFFFF3B30)
                else -> Color(0xFFF39C12)
            },
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SwapAmountCard(
    label: String,
    coin: SwapCoin,
    coinLabel: String,
    amountText: String,
    onAmountChange: (String) -> Unit,
    editable: Boolean,
    onMaxClick: (() -> Unit)? = null
) {
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editable) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = onAmountChange,
                        placeholder = { Text("0.00", color = LocalAppColors.current.textSecondary) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        trailingIcon = onMaxClick?.let { max ->
                            { TextButton(onClick = max) { Text("Max", color = KaspaTeal) } }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.surfaceVariant,
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        amountText.ifBlank { "0.00" },
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(LocalAppColors.current.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoinIcon(coin, size = 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(coinLabel, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
