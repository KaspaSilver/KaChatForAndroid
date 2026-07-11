package com.kachat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.ColdStorageViewModel

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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showScanner = true },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = CircleShape
            ) {
                Icon(Icons.Default.QrCodeScanner, "Scan kpub from KasSigner")
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

    LaunchedEffect(accountId) {
        viewModel.refreshAddresses(accountId)
    }

    val totalBalanceKas = addresses.sumOf { it.balanceSompi } / 100_000_000.0

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
                Text(
                    "Addresses",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (isDiscovering && addresses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                }
            } else if (addresses.isEmpty()) {
                item {
                    Text("No addresses discovered yet.", color = Color.Gray)
                }
            } else {
                items(addresses) { row ->
                    val kas = row.balanceSompi / 100_000_000.0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1C1C1E))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.address, color = Color.White, style = MaterialTheme.typography.bodySmall)
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
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(row.address)) }) {
                            Icon(Icons.Default.ContentCopy, "Copy address", tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        }
                    }
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
}
