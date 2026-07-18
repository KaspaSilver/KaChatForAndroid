package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kachat.app.R
import com.kachat.app.services.WalletManager
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.authenticateWithDeviceCredential
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Main entry point for the onboarding flow.
 */
@Composable
fun OnboardingScreen(viewModel: WalletViewModel) {
    val navController = rememberNavController()
    val generatedMnemonic by viewModel.onMnemonicGenerated.collectAsState()

    LaunchedEffect(generatedMnemonic) {
        if (generatedMnemonic != null) {
            navController.navigate("backup_mnemonic/$generatedMnemonic")
        }
    }

    NavHost(navController = navController, startDestination = "welcome") {
        composable("welcome") {
            WelcomeScreen(
                viewModel,
                onNavigateToCreate = { navController.navigate("create_account") },
                onNavigateToImport = {
                    // A stale SUCCESS/FAILED status left over from a previous import would
                    // otherwise fire ImportWalletScreen's success LaunchedEffect immediately on
                    // entry (this state lives in the singleton WalletViewModel, not the screen),
                    // silently logging into whatever account is currently active instead of
                    // letting the user type a new phrase.
                    viewModel.resetImportWalletState()
                    navController.navigate("import_wallet")
                }
            )
        }
        composable("create_account") {
            CreateAccountScreen(viewModel, onBack = { navController.popBackStack() })
        }
        composable("import_wallet") {
            ImportWalletScreen(
                viewModel,
                onBack = { navController.popBackStack() },
                onImported = { viewModel.login() }
            )
        }
        composable("backup_mnemonic/{words}") { backStackEntry ->
            val words = backStackEntry.arguments?.getString("words") ?: ""
            BackupMnemonicScreen(
                mnemonic = words,
                onComplete = {
                    viewModel.clearMnemonic()
                    viewModel.login()
                }
            )
        }
    }
}

@Composable
fun WelcomeScreen(viewModel: WalletViewModel, onNavigateToCreate: () -> Unit, onNavigateToImport: () -> Unit) {
    Surface(
        color = LocalAppColors.current.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top spacer to help center the middle content
            Spacer(modifier = Modifier.weight(1f))

            // App logo
            Image(
                painter = painterResource(id = R.drawable.ic_kachat_logo),
                contentDescription = "KaChat Logo",
                modifier = Modifier.size(160.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "KaChat",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp
                ),
                color = LocalAppColors.current.textPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Secure messaging on Kaspa BlockDAG",
                style = MaterialTheme.typography.bodyLarge,
                color = KaspaSubtext,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.weight(1f))

            val hasWallet by viewModel.hasWallet.collectAsState()
            val accounts by viewModel.accounts.collectAsState()
            val biometricAccountLoginEnabled by viewModel.biometricAccountLoginEnabled.collectAsState()

            if (hasWallet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Saved Accounts",
                        color = LocalAppColors.current.textPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Saved account cards
                    accounts.forEach { account ->
                        SavedAccountCard(
                            account = account,
                            requireBiometricLogin = biometricAccountLoginEnabled,
                            onLogin = { viewModel.login(account.address) },
                            onRename = { newName -> viewModel.renameAccount(account.address, newName) },
                            onDelete = { viewModel.deleteWallet(account.address) }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            // Create new wallet button
            Button(
                onClick = onNavigateToCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KaspaTeal,
                    contentColor = Color.Black // iOS uses black text on teal
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Create New Account",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Import existing wallet button
            Button(
                onClick = onNavigateToImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = LocalAppColors.current.textPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Import Existing Account",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = LocalAppColors.current.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun SavedAccountCard(
    account: WalletManager.Account,
    requireBiometricLogin: Boolean = true,
    onLogin: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf(account.name) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LocalAppColors.current.surface)
            .clickable {
                if (requireBiometricLogin) {
                    context.authenticateWithDeviceCredential(
                        title = "Unlock ${account.name}",
                        onSuccess = onLogin
                    )
                } else {
                    onLogin()
                }
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF2C3E50), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = KaspaTeal,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = account.address,
                color = LocalAppColors.current.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit account",
                    tint = KaspaTeal,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = {
                        showMenu = false
                        nameInput = account.name
                        showRenameDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) },
                    onClick = {
                        showMenu = false
                        showDeleteConfirm = true
                    }
                )
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Rename Account", color = LocalAppColors.current.textPrimary) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary,
                        focusedBorderColor = KaspaTeal,
                        unfocusedBorderColor = LocalAppColors.current.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        onRename(nameInput)
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete Account", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This removes \"${account.name}\" from this device. Without its saved seed phrase, any remaining balance is unrecoverable.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    var accountName by remember { mutableStateOf("My Account") }
    var wordCount by remember { mutableIntStateOf(24) }

    Surface(
        color = LocalAppColors.current.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                // Without this, the "Account Name" field and "Generate Account" button below it
                // (this Column has no scroll of its own — it relies on a weight(1f) spacer to push
                // the button to the bottom) can end up rendered behind the keyboard on devices
                // where edge-to-edge means windowSoftInputMode="adjustResize" alone doesn't shrink
                // the window — Compose has to react to the IME inset itself.
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = LocalAppColors.current.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Create Account",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = LocalAppColors.current.textPrimary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Important notice box
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF39C12),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Important",
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "You will be shown a seed phrase. This is the only way to recover your account. Make sure you only write this down. Never take a screenshot or store it on your device.",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Seed Phrase Length",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Segmented control for word count
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = wordCount == 24,
                    onClick = { wordCount = 24 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) {
                    Text("24 words (recommended)", fontSize = 12.sp)
                }
                SegmentedButton(
                    selected = wordCount == 12,
                    onClick = { wordCount = 12 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = LocalAppColors.current.surfaceVariant,
                        activeContentColor = LocalAppColors.current.textPrimary,
                        inactiveContainerColor = LocalAppColors.current.surface,
                        inactiveContentColor = LocalAppColors.current.textSecondary
                    )
                ) {
                    Text("12 words", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Account Name",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Text field for account name
            TextField(
                value = accountName,
                onValueChange = { accountName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surface,
                    unfocusedContainerColor = LocalAppColors.current.surface,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            // A fixed gap, not weight(1f) — a scrollable Column (added above for imePadding to
            // actually help) can't host a weight()'d child, since scrolling gives it unbounded
            // height to measure against.
            Spacer(modifier = Modifier.height(32.dp))

            // Generate button
            Button(
                onClick = { viewModel.createWallet(accountName, wordCount) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Generate Account",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Lowercases, trims, and splits a pasted/typed seed phrase on any whitespace — matches iOS's `updateWordCount` splitting rule exactly. */
internal fun parseSeedPhraseWords(raw: String): List<String> =
    raw.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(viewModel: WalletViewModel, onBack: () -> Unit, onImported: () -> Unit) {
    var seedPhraseText by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("Imported Account") }
    val importState by viewModel.importWalletState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    val words = remember(seedPhraseText) { parseSeedPhraseWords(seedPhraseText) }
    val wordCount = words.size
    val isValidCount = wordCount == 12 || wordCount == 24
    val isImporting = importState.status == WalletViewModel.ImportWalletStatus.IMPORTING
    val canImport = isValidCount && accountName.isNotBlank() && !isImporting

    LaunchedEffect(importState.status) {
        if (importState.status == WalletViewModel.ImportWalletStatus.SUCCESS) {
            onImported()
        }
    }

    Surface(color = LocalAppColors.current.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                // Already scrollable, but without imePadding() the keyboard can still cover the
                // "Account Name" field and "Import Account" button on devices where edge-to-edge
                // means windowSoftInputMode="adjustResize" alone doesn't shrink the window.
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(LocalAppColors.current.surface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = LocalAppColors.current.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Import Account",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = LocalAppColors.current.textPrimary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Seed Phrase",
                    color = LocalAppColors.current.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$wordCount words",
                    color = if (isValidCount) Color(0xFF4CD964) else Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = seedPhraseText,
                onValueChange = { seedPhraseText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clip(RoundedCornerShape(12.dp)),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("Enter your 12 or 24 word seed phrase", color = LocalAppColors.current.textSecondary) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surface,
                    unfocusedContainerColor = LocalAppColors.current.surface,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            if (seedPhraseText.isNotBlank() && !isValidCount) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Please enter exactly 12 or 24 words",
                    color = Color(0xFFFF3B30),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = { clipboardManager.getText()?.text?.let { seedPhraseText = it } }) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste from Clipboard", color = KaspaTeal, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Account Name",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = accountName,
                onValueChange = { accountName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalAppColors.current.surface,
                    unfocusedContainerColor = LocalAppColors.current.surface,
                    focusedTextColor = LocalAppColors.current.textPrimary,
                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { viewModel.importWallet(accountName, words) },
                enabled = canImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal, disabledContainerColor = LocalAppColors.current.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isImporting) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = "Import Account",
                        color = if (canImport) Color.Black else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (importState.status == WalletViewModel.ImportWalletStatus.FAILED) {
        AlertDialog(
            onDismissRequest = { viewModel.resetImportWalletState() },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Error", color = LocalAppColors.current.textPrimary) },
            text = { Text(importState.errorMessage ?: "Something went wrong", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetImportWalletState() }) {
                    Text("OK", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BackupMnemonicScreen(mnemonic: String, onComplete: () -> Unit) {
    val words = remember { mnemonic.split(" ") }

    Surface(
        color = LocalAppColors.current.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(40.dp))
                Text("Seed Phrase", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onComplete) {
                    Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security Warning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2C1E1E))
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF39C12),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Security Warning",
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Anyone with your seed phrase can access your account. Never share it with anyone. Make sure you only write this down. Never take a screenshot or store it on your device.",
                        color = Color(0xFF948B8B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Words Grid
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3
            ) {
                words.forEachIndexed { index, word ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(LocalAppColors.current.surface)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = word,
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "I've backed it up",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
