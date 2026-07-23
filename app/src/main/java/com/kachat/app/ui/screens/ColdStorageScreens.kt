package com.kachat.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KsptCodec
import com.kachat.app.viewmodels.ColdStorageViewModel
import com.kachat.app.viewmodels.WalletViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cold Storage — a fully separate area of the app for watching/spending funds held on an
 * air-gapped KasSigner device. Everything here is watch-only (public keys only); signing always
 * happens on the physical device via QR exchange, never inside KaChat. See the KasSigner project
 * README for the device's own safety disclaimers: experimental, unaudited, no secure element.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageListScreen(
    navController: NavController,
    viewModel: ColdStorageViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val importState by viewModel.importState.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var manualKpubInput by remember { mutableStateOf("") }
    var pendingKpub by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    var renamingAccount by remember { mutableStateOf<ColdStorageManager.ColdAccount?>(null) }
    var renameInput by remember { mutableStateOf("") }

    LaunchedEffect(importState.status) {
        if (importState.status == ColdStorageViewModel.ImportStatus.SUCCESS) {
            pendingKpub = null
            nameInput = ""
            viewModel.resetImportState()
        }
    }

    // Cold Storage is a tab route, so the floating bottom nav bar is normally always shown on
    // top of it — this is a genuinely full-screen camera view, not a "pushed" detail screen, so
    // it has to explicitly ask the shell to hide the bar rather than that happening for free.
    LaunchedEffect(showScanner) { walletViewModel.setHideBottomBar(showScanner) }
    DisposableEffect(Unit) { onDispose { walletViewModel.setHideBottomBar(false) } }

    if (showScanner) {
        BackHandler { showScanner = false }
        QrScannerOverlay(
            onScanned = { scanned ->
                showScanner = false
                pendingKpub = scanned
                nameInput = "Cold Storage ${accounts.size + 1}"
            },
            onDismiss = { showScanner = false }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Cold Storage", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Matches iOS's ColdStorageListView: "Paste kpub" (outlined, secondary) and "Scan"
            // (filled, primary) side by side at the bottom, instead of a single centered FAB.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        manualKpubInput = ""
                        showManualEntry = true
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.5.dp, KaspaTeal),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KaspaTeal)
                ) {
                    Text("Paste kpub", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, contentColor = Color.Black)
                ) {
                    Text(
                        "Scan",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = "Scan kpub from KasSigner" }
                    )
                }
            }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Lock, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "No cold storage accounts yet. Scan a kpub exported from your KasSigner device to watch its balance and send from it.",
                    color = LocalAppColors.current.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(accounts) { account ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalAppColors.current.surface)
                            .clickable { navController.navigate("cold_storage_detail/${account.id}") }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.name, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
                            Text(
                                account.kpub,
                                color = LocalAppColors.current.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(account.kpub)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "Copy kpub", tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                renamingAccount = account
                                renameInput = account.name
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, "Rename", tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showManualEntry) {
        AlertDialog(
            onDismissRequest = { showManualEntry = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Enter kpub", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        "Paste the kpub exported from your KasSigner device. This contains no private key material.",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualKpubInput,
                        onValueChange = { manualKpubInput = it },
                        label = { Text("kpub...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = manualKpubInput.trim().isNotEmpty(),
                    onClick = {
                        val trimmed = manualKpubInput.trim()
                        showManualEntry = false
                        // Feeds the same pendingKpub/nameInput -> import AlertDialog flow the QR
                        // scanner already uses below - scan vs. paste only differ in how the raw
                        // kpub string is obtained, not in how it's validated/named/imported.
                        pendingKpub = trimmed
                        nameInput = "Cold Storage ${accounts.size + 1}"
                    }
                ) {
                    Text("Next", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualEntry = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (pendingKpub != null) {
        val kpub = pendingKpub!!
        val isInvalid = importState.status == ColdStorageViewModel.ImportStatus.INVALID_KPUB
        AlertDialog(
            onDismissRequest = { pendingKpub = null; viewModel.resetImportState() },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Import Cold Storage Account", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        "kpub: ${kpub.take(24)}…",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalAppColors.current.textPrimary,
                            unfocusedTextColor = LocalAppColors.current.textPrimary,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = LocalAppColors.current.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isInvalid) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            importState.errorMessage ?: "Not a valid kpub",
                            color = Color(0xFFFF3B30),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = { viewModel.importKpub(kpub, nameInput) }
                ) {
                    Text("Import", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingKpub = null; viewModel.resetImportState() }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    renamingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { renamingAccount = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Rename Cold Storage Account", color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
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
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        viewModel.renameAccount(account.id, renameInput.trim())
                        renamingAccount = null
                    }
                ) {
                    Text("Save", color = if (renameInput.isNotBlank()) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingAccount = null }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageDetailScreen(accountId: String, navController: NavController, viewModel: ColdStorageViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    val account = accounts.find { it.id == accountId }
    val addresses by viewModel.addresses.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sendFromRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var labelingRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var qrRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var actionsMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(accountId) {
        viewModel.refreshAddresses(accountId)
    }

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.refreshAddresses(accountId)
        }
    }

    LaunchedEffect(isDiscovering) {
        if (!isDiscovering && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    // Funded addresses always sort to the top; within each group, newest (highest index) first —
    // so a freshly generated (zero-balance) address lands right below the last funded one rather
    // than jumping above it just for being newest. Matches Manage Addresses' spending-address list.
    val visibleAddresses = remember(addresses) {
        addresses.filterNot { it.hidden }
            .sortedWith(compareByDescending<ColdStorageViewModel.AddressRow> { it.balanceSompi > 0 }.thenByDescending { it.index })
    }
    val hiddenAddresses = remember(addresses) { addresses.filter { it.hidden } }
    // A hidden address is excluded on purpose (a "put this aside" gesture) — it shouldn't keep
    // inflating the balance you actually think of as available.
    val totalBalanceKas = visibleAddresses.sumOf { it.balanceSompi } / 100_000_000.0

    sendFromRow?.let { row ->
        ColdSendFlow(
            fromAddress = row.address,
            availableBalanceSompi = row.balanceSompi,
            viewModel = viewModel,
            onDone = { sendFromRow = null; viewModel.refreshAddressesSoonAfterSend(accountId) }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(account?.name ?: "Cold Storage", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Remove account", tint = Color.Red)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Hidden while the QR overlay is up — its Dialog window doesn't fully cover the
            // screen, so the FAB would otherwise still show through around the QR card.
            if (qrRow == null) {
            FloatingActionButton(
                onClick = { showActionsMenu = true },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .height(56.dp)
                    .onGloballyPositioned { coords -> actionsMenuAnchor = coords.positionInWindow() }
            ) {
                Text(
                    "Address Actions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .semantics { contentDescription = "Address actions" }
                )
            }
            }
            // Anchored to the FAB's top edge and horizontally centered (this FAB is itself
            // screen-centered, so the usual left/right-edge-hugging anchor math doesn't apply) —
            // see ManageAddressesScreen's identical Address Actions menu for the full rationale.
            if (showActionsMenu) {
                CenteredOptionsMenu(
                    onDismissRequest = { showActionsMenu = false },
                    anchor = actionsMenuAnchor,
                    centerHorizontally = true
                ) {
                    PopupMenuRow(Icons.Default.AddCircleOutline, "Generate More Addresses") {
                        showActionsMenu = false
                        if (!isDiscovering) viewModel.generateMoreAddresses(accountId)
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.Search, "Discover Addresses") {
                        showActionsMenu = false
                        if (!isDiscovering) {
                            viewModel.refreshAddresses(accountId) { count ->
                                Toast.makeText(
                                    context,
                                    if (count > 0) "Found $count used address${if (count == 1) "" else "es"}" else "No additional used addresses found",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(pullRefreshState.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Name", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(
                                account?.name ?: "Cold Storage",
                                color = LocalAppColors.current.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        IconButton(
                            onClick = {
                                renameInput = account?.name ?: ""
                                showRenameDialog = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, "Rename", tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("kpub", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        account?.kpub ?: "",
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.clickable {
                            account?.kpub?.let { clipboardManager.setText(AnnotatedString(it)) }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy kpub", color = KaspaTeal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .padding(20.dp)
                ) {
                    Text("Total Balance", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                    Text(
                        "%.8f KAS".format(java.util.Locale.US, totalBalanceKas),
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Addresses",
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (hiddenAddresses.isNotEmpty()) {
                            Row(
                                modifier = Modifier.clickable { navController.navigate("cold_storage_hidden/$accountId") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VisibilityOff, "Hidden addresses", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Hidden (${hiddenAddresses.size})",
                                    color = LocalAppColors.current.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (isDiscovering && addresses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                }
            } else if (visibleAddresses.isEmpty()) {
                item {
                    Text(
                        if (addresses.isEmpty()) "No addresses discovered yet." else "All addresses are hidden.",
                        color = LocalAppColors.current.textSecondary
                    )
                }
            } else {
                items(visibleAddresses, key = { it.index }) { row ->
                    ColdAddressRow(
                        row = row,
                        onAddressClick = { navController.navigate("cold_storage_tx_history/${row.address}") },
                        onLabelClick = { labelingRow = row; labelInput = row.label ?: "" },
                        onCopyClick = { clipboardManager.setText(AnnotatedString(row.address)) },
                        onSendClick = { if (row.balanceSompi > 0) sendFromRow = row },
                        onShowQrClick = { qrRow = row },
                        onHideToggleClick = { viewModel.setAddressHidden(accountId, row.index, true) }
                    )
                }
            }

            item {
                // Leaves room so the last address row isn't hidden behind the FAB.
                Spacer(Modifier.height(64.dp))
            }
        }
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        qrRow?.let { row ->
            QrCodeOverlay(value = row.address, onDismiss = { qrRow = null })
        }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Remove Cold Storage Account", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This only removes it from KaChat's watch list. It has no effect on the KasSigner device or any funds it holds.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAccount(accountId)
                    showDeleteConfirm = false
                    navController.popBackStack()
                }) {
                    Text("Remove", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Rename Cold Storage Account", color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
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
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        viewModel.renameAccount(accountId, renameInput.trim())
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", color = if (renameInput.isNotBlank()) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    labelingRow?.let { row ->
        AlertDialog(
            onDismissRequest = { labelingRow = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Name This Address", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(row.address, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Name") },
                        singleLine = true,
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setAddressLabel(accountId, row.index, labelInput)
                        labelingRow = null
                    }
                ) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labelingRow = null }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/**
 * Every address hidden under one Cold Storage account, reached via the "Hidden (N)" link on
 * [ColdStorageDetailScreen] — the only place a hidden address can be unhidden again. Hidden
 * addresses are excluded from [ColdStorageDetailScreen]'s Total Balance (see that screen's
 * `totalBalanceKas`), but nothing here ever deletes them — hiding is purely a display preference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageHiddenAddressesScreen(accountId: String, navController: NavController, viewModel: ColdStorageViewModel = hiltViewModel()) {
    val addresses by viewModel.addresses.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var sendFromRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var labelingRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var qrRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }

    // No refresh-on-entry here on purpose — [viewModel] is the same instance ColdStorageDetailScreen
    // already loaded (shared via the nav graph, see KaChatApp.kt), so its address list (and each
    // row's `hidden` flag) is already current the moment this screen appears.
    val hiddenAddresses = remember(addresses) { addresses.filter { it.hidden } }

    sendFromRow?.let { row ->
        ColdSendFlow(
            fromAddress = row.address,
            availableBalanceSompi = row.balanceSompi,
            viewModel = viewModel,
            onDone = { sendFromRow = null; viewModel.refreshAddressesSoonAfterSend(accountId) }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Hidden Addresses", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        if (hiddenAddresses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("No hidden addresses.", color = LocalAppColors.current.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(hiddenAddresses, key = { it.index }) { row ->
                    ColdAddressRow(
                        row = row,
                        onAddressClick = { navController.navigate("cold_storage_tx_history/${row.address}") },
                        onLabelClick = { labelingRow = row; labelInput = row.label ?: "" },
                        onCopyClick = { clipboardManager.setText(AnnotatedString(row.address)) },
                        onSendClick = { if (row.balanceSompi > 0) sendFromRow = row },
                        onShowQrClick = { qrRow = row },
                        onHideToggleClick = { viewModel.setAddressHidden(accountId, row.index, false) }
                    )
                }
            }
            qrRow?.let { row ->
                QrCodeOverlay(value = row.address, onDismiss = { qrRow = null })
            }
            }
        }
    }

    labelingRow?.let { row ->
        AlertDialog(
            onDismissRequest = { labelingRow = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Name This Address", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(row.address, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Name") },
                        singleLine = true,
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setAddressLabel(accountId, row.index, labelInput)
                        labelingRow = null
                    }
                ) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labelingRow = null }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/**
 * One address row — shared by the visible and "Hidden Addresses" sections of [ColdStorageDetailScreen],
 * differing only in whether [ColdStorageViewModel.AddressRow.hidden] shows a hide or an unhide action.
 * Hiding/unhiding is a swipe-left action (matching Chats' swipe-to-delete) rather than a permanent
 * icon button, since it's not something reached for as often as copy/send/QR. Unhiding is always
 * available, but an address can't be hidden while it still holds a balance — see
 * [ColdStorageViewModel.setAddressHidden], which enforces the same rule as a backstop.
 */
@Composable
private fun ColdAddressRow(
    row: ColdStorageViewModel.AddressRow,
    onAddressClick: () -> Unit,
    onLabelClick: () -> Unit,
    onCopyClick: () -> Unit,
    onSendClick: () -> Unit,
    onShowQrClick: () -> Unit,
    onHideToggleClick: () -> Unit
) {
    val kas = row.balanceSompi / 100_000_000.0
    val canHide = row.hidden || row.balanceSompi == 0L
    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }

    SwipeActionRow(
        enabled = canHide,
        cornerRadius = 12.dp,
        trailingIcon = if (row.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        trailingLabel = if (row.hidden) "Unhide" else "Hide",
        trailingColor = Color(0xFF48484A),
        onTrailingClick = onHideToggleClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LocalAppColors.current.surface)
                .clickable(onClick = onAddressClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label?.takeIf { it.isNotBlank() } ?: "Address #${row.index}",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${row.address.take(14)}...${row.address.takeLast(6)}",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "%.8f KAS".format(java.util.Locale.US, kas),
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (row.hasHistory) "Used" else "Unused",
                    color = if (row.hasHistory) Color(0xFFF39C12) else Color(0xFF4CD964),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier
                    .size(44.dp)
                    .onGloballyPositioned { coords ->
                        menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
                    }
            ) {
                Icon(Icons.Default.MoreVert, "Address actions", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(28.dp))
            }
        }
    }

    if (showMenu) {
        CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
            PopupMenuRow(Icons.Default.ContentCopy, "Copy Address") {
                showMenu = false
                onCopyClick()
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.QrCode, "Show QR Code") {
                showMenu = false
                onShowQrClick()
            }
            if (row.balanceSompi > 0) {
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.AutoMirrored.Filled.Send, "Send From This Address") {
                    showMenu = false
                    onSendClick()
                }
            }
            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
            PopupMenuRow(Icons.Default.Edit, "Rename Address") {
                showMenu = false
                onLabelClick()
            }
        }
    }
}

/**
 * The whole "send" round trip for one Cold Storage address: enter recipient/amount, build an
 * unsigned tx, display it as an animated KSPT QR for the KasSigner device to scan/sign, scan the
 * signed response back, then broadcast. Takes over the full screen (like [ColdStorageListScreen]'s
 * scanner) rather than living in a dialog — the animated QR needs real room to be scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColdSendFlow(
    fromAddress: String,
    availableBalanceSompi: Long,
    viewModel: ColdStorageViewModel,
    onDone: () -> Unit
) {
    val sendState by viewModel.sendState.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    var toAddress by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var showSignedScanner by remember { mutableStateOf(false) }
    var showRecipientScanner by remember { mutableStateOf(false) }
    var feeRateOverrideSompi by remember { mutableStateOf<Long?>(null) }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    DisposableEffect(Unit) {
        onDispose { viewModel.resetColdSendState() }
    }

    val inFlight = sendState.step in listOf(
        ColdStorageViewModel.ColdSendStep.BUILDING,
        ColdStorageViewModel.ColdSendStep.BROADCASTING
    )
    val availableKas = availableBalanceSompi / 100_000_000.0
    val amountSompi = amountText.toDoubleOrNull()?.let { Math.round(it * 100_000_000.0) }
    val isValidRecipient = remember(toAddress) { KaspaAddress.isValid(toAddress) }

    // Same simplified single-input, two-output estimate the regular Withdraw dialog's fee
    // adjuster uses — real UTXO selection can differ slightly, but it's close enough to preview
    // and to translate a user-entered KAS fee back into a sompi-per-gram rate.
    val estimatedMass = remember {
        com.kachat.app.util.KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
    }
    val defaultFeeSompi = com.kachat.app.util.KaspaMass.calculateFee(estimatedMass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
    val effectiveFeeSompi = feeRateOverrideSompi?.let { com.kachat.app.util.KaspaMass.calculateFee(estimatedMass, it) } ?: defaultFeeSompi

    BackHandler(enabled = !inFlight) { onDone() }

    if (showRecipientScanner) {
        BackHandler { showRecipientScanner = false }
        QrScannerOverlay(
            onScanned = { scanned -> toAddress = scanned.trim(); showRecipientScanner = false },
            onDismiss = { showRecipientScanner = false }
        )
        return
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Send from Cold Storage", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (!inFlight) onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (inFlight) Color.Gray else KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp)
            ) {
                Text("From", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                Text(fromAddress, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Available: %.8f KAS".format(java.util.Locale.US, availableKas), color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            }

            when (sendState.step) {
                ColdStorageViewModel.ColdSendStep.IDLE, ColdStorageViewModel.ColdSendStep.FAILED -> {
                    OutlinedTextField(
                        value = toAddress,
                        onValueChange = { toAddress = it },
                        label = { Text("Recipient address") },
                        singleLine = true,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { clipboardManager.getText()?.text?.let { toAddress = it.trim() } }) {
                            Text("Paste from Clipboard", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { showRecipientScanner = true }) {
                            Text("Scan QR Code", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (KAS)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val maxSompi = (availableBalanceSompi - effectiveFeeSompi).coerceAtLeast(0L)
                                    amountText = "%.8f".format(java.util.Locale.US, maxSompi / 100_000_000.0)
                                }
                            ) {
                                Text("Max", color = KaspaTeal)
                            }
                        },
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fee: %.8f KAS".format(java.util.Locale.US, effectiveFeeSompi / 100_000_000.0),
                        color = KaspaTeal,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            feeEditorInput = "%.8f".format(java.util.Locale.US, effectiveFeeSompi / 100_000_000.0)
                            showFeeEditor = true
                        }
                    )
                    if (sendState.step == ColdStorageViewModel.ColdSendStep.FAILED) {
                        Text(sendState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { amountSompi?.let { viewModel.startColdSend(fromAddress, toAddress.trim(), it, feeRateOverrideSompi) } },
                        enabled = isValidRecipient && (amountSompi ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            "Build Unsigned Transaction",
                            color = if (isValidRecipient && (amountSompi ?: 0) > 0) Color.Black else Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                ColdStorageViewModel.ColdSendStep.BUILDING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        CircularProgressIndicator(color = KaspaTeal)
                        Spacer(Modifier.height(12.dp))
                        Text("Building transaction...", color = LocalAppColors.current.textSecondary)
                    }
                }

                ColdStorageViewModel.ColdSendStep.SHOWING_QR -> {
                    if (!showSignedScanner) {
                        // A bright, high-contrast quiet zone around the code — same reasoning as
                        // the full-screen "big mode" QR overlay elsewhere — gets a more reliable
                        // scan on the KasSigner device's camera than the app's own dark theme.
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                Text(
                                    "Scan this with your KasSigner device, review it there, then sign.",
                                    color = Color(0xFF6B6B70),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                AnimatedQrDisplay(frames = sendState.qrFrames, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Network fee: ~%.8f KAS".format(java.util.Locale.US, sendState.feeSompi / 100_000_000.0),
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { showSignedScanner = true },
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Scan Signed Transaction", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        MultiFrameQrScannerOverlay(
                            isComplete = { KsptCodec.looksLikeKspt(it) },
                            onComplete = { bytes ->
                                showSignedScanner = false
                                viewModel.onSignedKsptScanned(bytes)
                            },
                            onCancel = { showSignedScanner = false }
                        )
                    }
                }

                ColdStorageViewModel.ColdSendStep.BROADCASTING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        CircularProgressIndicator(color = KaspaTeal)
                        Spacer(Modifier.height(12.dp))
                        Text("Broadcasting...", color = LocalAppColors.current.textSecondary)
                    }
                }

                ColdStorageViewModel.ColdSendStep.SUCCESS -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Sent", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(20.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.surface)
                                .padding(16.dp)
                        ) {
                            Text("To", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(toAddress, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LocalAppColors.current.surface)
                                .clickable {
                                    sendState.txId?.let { uriHandler.openUri(kaspaExplorer.txUrl(it)) }
                                }
                                .padding(16.dp)
                        ) {
                            Text("Transaction ID · tap to view in ${kaspaExplorer.displayName}", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                            Text(sendState.txId ?: "", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
                        "Default: %.8f KAS".format(java.util.Locale.US, defaultFeeSompi / 100_000_000.0),
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    feeRateOverrideSompi = if (kas != null && kas > 0) {
                        val desiredFeeSompi = Math.round(kas * 100_000_000.0)
                        kotlin.math.ceil(desiredFeeSompi.toDouble() / estimatedMass).toLong()
                    } else {
                        null
                    }
                    showFeeEditor = false
                }) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { feeRateOverrideSompi = null; showFeeEditor = false }) {
                        Text("Use Default", color = LocalAppColors.current.textSecondary)
                    }
                    TextButton(onClick = { showFeeEditor = false }) {
                        Text("Cancel", color = LocalAppColors.current.textSecondary)
                    }
                }
            }
        )
    }
}

/** On-chain transaction history for one Cold Storage address — reached by tapping an address row in [ColdStorageDetailScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageTxHistoryScreen(address: String, onBack: () -> Unit, viewModel: ColdStorageViewModel = hiltViewModel()) {
    val txHistory by viewModel.txHistory.collectAsState()
    val isLoading by viewModel.isLoadingTxHistory.collectAsState()
    val kaspaExplorer by viewModel.kaspaExplorer.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(address) {
        viewModel.loadTxHistory(address)
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Transaction History", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                address,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { clipboardManager.setText(AnnotatedString(address)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
            when {
                isLoading && txHistory.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                }
                txHistory.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No transactions yet.", color = LocalAppColors.current.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(txHistory, key = { it.txId }) { tx ->
                            ColdTxHistoryRow(
                                tx = tx,
                                onClick = { uriHandler.openUri(kaspaExplorer.txUrl(tx.txId)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColdTxHistoryRow(tx: ColdStorageAddressDiscovery.AddressTransaction, onClick: () -> Unit) {
    val kas = tx.amountSompi / 100_000_000.0
    val dateStr = tx.blockTimeMillis?.let {
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(it))
    } ?: "Pending"
    val directionColor = if (tx.sent) Color(0xFFFF3B30) else Color(0xFF4CD964)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(directionColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (tx.sent) Icons.AutoMirrored.Filled.Send else Icons.AutoMirrored.Filled.CallReceived,
                null,
                tint = directionColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (tx.sent) "Sent" else "Received", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            Text(dateStr, color = LocalAppColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(
                tx.txId,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${if (tx.sent) "-" else "+"}%.8f KAS".format(java.util.Locale.US, kas),
            color = directionColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
