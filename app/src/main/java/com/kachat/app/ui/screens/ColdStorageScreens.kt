package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KsptCodec
import com.kachat.app.viewmodels.ColdStorageViewModel
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
fun ColdStorageListScreen(navController: NavController, viewModel: ColdStorageViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsState()
    val importState by viewModel.importState.collectAsState()
    var showScanner by remember { mutableStateOf(false) }
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
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Cold Storage", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showScanner = true },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 120.dp)
            ) {
                Text(
                    "Scan",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = "Scan kpub from KasSigner" }
                )
            }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Lock, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "No cold storage accounts yet. Scan a kpub exported from your KasSigner device to watch its balance and send from it.",
                    color = Color.Gray,
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
                            .background(Color(0xFF1C1C1E))
                            .clickable { navController.navigate("cold_storage_detail/${account.id}") }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                account.kpub,
                                color = Color.Gray,
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

    if (pendingKpub != null) {
        val kpub = pendingKpub!!
        val isInvalid = importState.status == ColdStorageViewModel.ImportStatus.INVALID_KPUB
        AlertDialog(
            onDismissRequest = { pendingKpub = null; viewModel.resetImportState() },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Import Cold Storage Account", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Scanned: ${kpub.take(24)}…",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = Color.Gray
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
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    renamingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { renamingAccount = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Rename Cold Storage Account", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = Color.Gray
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
                    Text("Cancel", color = Color.Gray)
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sendFromRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var labelingRow by remember { mutableStateOf<ColdStorageViewModel.AddressRow?>(null) }
    var labelInput by remember { mutableStateOf("") }

    LaunchedEffect(accountId) {
        viewModel.refreshAddresses(accountId)
    }

    val visibleAddresses = remember(addresses) { addresses.filterNot { it.hidden } }
    val hiddenAddresses = remember(addresses) { addresses.filter { it.hidden } }
    // A hidden address is excluded on purpose (a "put this aside" gesture) — it shouldn't keep
    // inflating the balance you actually think of as available.
    val totalBalanceKas = visibleAddresses.sumOf { it.balanceSompi } / 100_000_000.0

    sendFromRow?.let { row ->
        ColdSendFlow(
            fromAddress = row.address,
            availableBalanceSompi = row.balanceSompi,
            viewModel = viewModel,
            onDone = { sendFromRow = null; viewModel.refreshAddresses(accountId) }
        )
        return
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(account?.name ?: "Cold Storage", color = Color.White, fontWeight = FontWeight.Bold) },
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C1C1E))
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Name", color = Color.Gray, fontSize = 12.sp)
                            Text(
                                account?.name ?: "Cold Storage",
                                color = Color.White,
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
                    Text("kpub", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        account?.kpub ?: "",
                        color = Color.White,
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
                        .background(Color(0xFF1C1C1E))
                        .padding(20.dp)
                ) {
                    Text("Total Balance", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "%.8f KAS".format(java.util.Locale.US, totalBalanceKas),
                        color = Color.White,
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
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.clickable(enabled = !isDiscovering) { viewModel.generateMoreAddresses(accountId) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                "Generate more addresses",
                                tint = if (isDiscovering) Color.Gray else KaspaTeal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Generate Address",
                                color = if (isDiscovering) Color.Gray else KaspaTeal,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (hiddenAddresses.isNotEmpty()) {
                            Row(
                                modifier = Modifier.clickable { navController.navigate("cold_storage_hidden/$accountId") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VisibilityOff, "Hidden addresses", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Hidden (${hiddenAddresses.size})",
                                    color = Color.Gray,
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
                        color = Color.Gray
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
                        onHideToggleClick = { viewModel.setAddressHidden(accountId, row.index, true) }
                    )
                }
            }

        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Remove Cold Storage Account", color = Color.White) },
            text = {
                Text(
                    "This only removes it from KaChat's watch list — it has no effect on the KasSigner device or any funds it holds.",
                    color = Color.Gray
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
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Rename Cold Storage Account", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = KaspaTeal,
                        unfocusedLabelColor = Color.Gray
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
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    labelingRow?.let { row ->
        AlertDialog(
            onDismissRequest = { labelingRow = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Name This Address", color = Color.White) },
            text = {
                Column {
                    Text(row.address, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = Color.Gray
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
                    Text("Cancel", color = Color.Gray)
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

    // No refresh-on-entry here on purpose — [viewModel] is the same instance ColdStorageDetailScreen
    // already loaded (shared via the nav graph, see KaChatApp.kt), so its address list (and each
    // row's `hidden` flag) is already current the moment this screen appears.
    val hiddenAddresses = remember(addresses) { addresses.filter { it.hidden } }

    sendFromRow?.let { row ->
        ColdSendFlow(
            fromAddress = row.address,
            availableBalanceSompi = row.balanceSompi,
            viewModel = viewModel,
            onDone = { sendFromRow = null; viewModel.refreshAddresses(accountId) }
        )
        return
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Hidden Addresses", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        if (hiddenAddresses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("No hidden addresses.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
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
                        onHideToggleClick = { viewModel.setAddressHidden(accountId, row.index, false) }
                    )
                }
            }
        }
    }

    labelingRow?.let { row ->
        AlertDialog(
            onDismissRequest = { labelingRow = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Name This Address", color = Color.White) },
            text = {
                Column {
                    Text(row.address, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = Color.Gray
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
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

/** One address row — shared by the visible and "Hidden Addresses" sections of [ColdStorageDetailScreen], differing only in whether [ColdStorageViewModel.AddressRow.hidden] shows a hide or an unhide action. */
@Composable
private fun ColdAddressRow(
    row: ColdStorageViewModel.AddressRow,
    onAddressClick: () -> Unit,
    onLabelClick: () -> Unit,
    onCopyClick: () -> Unit,
    onSendClick: () -> Unit,
    onHideToggleClick: () -> Unit
) {
    val kas = row.balanceSompi / 100_000_000.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onAddressClick)) {
            if (row.label != null) {
                Text(row.label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                row.address,
                color = if (row.label != null) Color.Gray else Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%.8f KAS".format(java.util.Locale.US, kas),
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "  ·  " + if (row.hasHistory) "Used" else "Unused",
                    color = if (row.hasHistory) Color(0xFFF39C12) else Color(0xFF4CD964),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        IconButton(onClick = onLabelClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, "Name this address", tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onCopyClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ContentCopy, "Copy address", tint = KaspaTeal, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onSendClick, enabled = row.balanceSompi > 0, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                "Send from this address",
                tint = if (row.balanceSompi > 0) KaspaTeal else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onHideToggleClick, modifier = Modifier.size(32.dp)) {
            Icon(
                if (row.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                if (row.hidden) "Unhide address" else "Hide address",
                tint = Color.Gray,
                modifier = Modifier.size(18.dp)
            )
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
    var toAddress by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var showSignedScanner by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

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

    BackHandler(enabled = !inFlight) { onDone() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Send from Cold Storage", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (!inFlight) onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (inFlight) Color.Gray else KaspaTeal)
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(16.dp)
            ) {
                Text("From", color = Color.Gray, fontSize = 12.sp)
                Text(fromAddress, color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Available: %.8f KAS".format(java.util.Locale.US, availableKas), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }

            when (sendState.step) {
                ColdStorageViewModel.ColdSendStep.IDLE, ColdStorageViewModel.ColdSendStep.FAILED -> {
                    OutlinedTextField(
                        value = toAddress,
                        onValueChange = { toAddress = it },
                        label = { Text("Recipient address") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { clipboardManager.getText()?.text?.let { toAddress = it.trim() } }) {
                        Text("Paste from Clipboard", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (KAS)") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val mass = com.kachat.app.util.KaspaMass.calculateMass(
                                        numInputs = 1,
                                        outputScriptLens = listOf(34, 34),
                                        payloadSize = 0
                                    )
                                    val fee = com.kachat.app.util.KaspaMass.calculateFee(mass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                                    val maxSompi = (availableBalanceSompi - fee).coerceAtLeast(0L)
                                    amountText = "%.8f".format(java.util.Locale.US, maxSompi / 100_000_000.0)
                                }
                            ) {
                                Text("Max", color = KaspaTeal)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = KaspaTeal,
                            unfocusedLabelColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (sendState.step == ColdStorageViewModel.ColdSendStep.FAILED) {
                        Text(sendState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { amountSompi?.let { viewModel.startColdSend(fromAddress, toAddress.trim(), it) } },
                        enabled = isValidRecipient && (amountSompi ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = Color(0xFF2C2C2E)),
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
                        Text("Building transaction...", color = Color.Gray)
                    }
                }

                ColdStorageViewModel.ColdSendStep.SHOWING_QR -> {
                    if (!showSignedScanner) {
                        Text(
                            "Scan this with your KasSigner device, review it there, then sign.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        AnimatedQrDisplay(frames = sendState.qrFrames, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Network fee: ~%.8f KAS".format(java.util.Locale.US, sendState.feeSompi / 100_000_000.0),
                            color = Color.Gray,
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
                        Text("Broadcasting...", color = Color.Gray)
                    }
                }

                ColdStorageViewModel.ColdSendStep.SUCCESS -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CD964), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Sent", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Transaction ID: ${sendState.txId}",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable {
                                sendState.txId?.let { clipboardManager.setText(AnnotatedString(it)) }
                            }
                        )
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
}

/** On-chain transaction history for one Cold Storage address — reached by tapping an address row in [ColdStorageDetailScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColdStorageTxHistoryScreen(address: String, onBack: () -> Unit, viewModel: ColdStorageViewModel = hiltViewModel()) {
    val txHistory by viewModel.txHistory.collectAsState()
    val isLoading by viewModel.isLoadingTxHistory.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(address) {
        viewModel.loadTxHistory(address)
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Transaction History", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                address,
                color = Color.Gray,
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
                        Text("No transactions yet.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                                onCopyTxId = { clipboardManager.setText(AnnotatedString(tx.txId)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColdTxHistoryRow(tx: ColdStorageAddressDiscovery.AddressTransaction, onCopyTxId: () -> Unit) {
    val kas = tx.amountSompi / 100_000_000.0
    val dateStr = tx.blockTimeMillis?.let {
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(it))
    } ?: "Pending"
    val directionColor = if (tx.sent) Color(0xFFFF3B30) else Color(0xFF4CD964)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onCopyTxId)
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
            Text(if (tx.sent) "Sent" else "Received", color = Color.White, fontWeight = FontWeight.Bold)
            Text(dateStr, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Text(
                tx.txId,
                color = Color.Gray,
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
