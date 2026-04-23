package com.kachat.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.KaspaAddress
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.ConnectionStatus as ConnStatus
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.NodeInfo
import com.kachat.app.viewmodels.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(
    navController: NavController, 
    contactId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val conversation = remember(contactId) { chatViewModel.getConversation(contactId) }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val contactBalances by chatViewModel.contactBalances.collectAsState()
    val contactBalance = contactBalances[contactId] ?: "0.00000000"
    
    val connStatus by connectionViewModel.status.collectAsState()
    val balance by walletViewModel.fullBalance.collectAsState()
    val balanceSompi by walletViewModel.balanceSompi.collectAsState()
    val paymentAmount by chatViewModel.paymentAmount.collectAsState()
    val estimatedFee by chatViewModel.estimatedFeeSompi.collectAsState()
    val estimateFeesEnabled by chatViewModel.estimateFeesEnabled.collectAsState()
    val messageText by chatViewModel.messageText.collectAsState()

    var paymentMode by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(contactId) {
        chatViewModel.refreshContactBalance(contactId)
    }

    LaunchedEffect(paymentMode) {
        if (paymentMode) {
            chatViewModel.refreshUtxos()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { navController.navigate("chat_info/$contactId") }
                    ) {
                        Text(
                            text = conversation?.contact?.alias ?: contactId.takeLast(8),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "$contactBalance KAS",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    val statusColor = when (connStatus) {
                        ConnStatus.CONNECTED -> Color(0xFF4CD964)
                        ConnStatus.WEAK -> Color(0xFFF39C12)
                        ConnStatus.DISCONNECTED -> Color.Red
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF1C1C1E), CircleShape)
                            .clickable { navController.navigate("connection_status") },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(contactId)) }) {
                        Icon(Icons.Default.ContentCopy, "Copy Address", tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(Color.Black).padding(8.dp)) {
                if (paymentMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (estimateFeesEnabled && estimatedFee != null) {
                                Surface(
                                    color = Color(0xFF1C1C1E),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "fee: $estimatedFee sompi",
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            
                            Surface(
                                color = Color(0xFF1C1C1E),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "available: $balance",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = paymentAmount,
                                onValueChange = { chatViewModel.setPaymentAmount(it) },
                                placeholder = { Text("Amount (KAS)", color = Color.DarkGray) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(25.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1C1C1E),
                                    unfocusedContainerColor = Color(0xFF1C1C1E),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = KaspaTeal,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                trailingIcon = {
                                    val currentUtxos by chatViewModel.currentUtxos.collectAsState()
                                    val networkFeeRate by chatViewModel.networkFeeRate.collectAsState()
                                    TextButton(onClick = {
                                        // Calculate fee for total balance using real network rate
                                        val count = currentUtxos.size
                                        val estimatedSize = 300 + (count * 100L)
                                        val fee = (estimatedSize * networkFeeRate).toLong().coerceAtLeast(1L)

                                        val maxSendableSompi = (balanceSompi - fee).coerceAtLeast(0L)
                                        val maxSendableKas = maxSendableSompi.toDouble() / 100_000_000.0
                                        chatViewModel.setPaymentAmount("%.8f".format(java.util.Locale.US, maxSendableKas))
                                    }) {
                                        Text("Max", color = KaspaTeal, fontWeight = FontWeight.Bold)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            
                            ChatActionButton(Icons.AutoMirrored.Filled.Chat, onClick = { 
                                paymentMode = false
                                chatViewModel.setPaymentAmount("")
                            })
                            ChatActionButton(Icons.Default.Mic)
                        }

                        Button(
                            onClick = {
                                android.util.Log.d("ChatThreadScreen", "Send Payment clicked. Amount: $paymentAmount, Contact: $contactId")
                                if (paymentAmount.isNotEmpty()) {
                                    chatViewModel.sendPayment(contactId, paymentAmount)
                                    chatViewModel.setPaymentAmount("")
                                    paymentMode = false
                                } else {
                                    android.util.Log.w("ChatThreadScreen", "Payment amount is empty")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Send Payment", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (estimateFeesEnabled && estimatedFee != null && messageText.isNotEmpty()) {
                            Surface(
                                color = Color(0xFF1C1C1E),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                            ) {
                                Text(
                                    text = "fee: $estimatedFee sompi",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = messageText,
                                onValueChange = { chatViewModel.setMessageText(it) },
                                placeholder = { Text("Message", color = Color.Gray) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1C1C1E),
                                    unfocusedContainerColor = Color(0xFF1C1C1E),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = KaspaTeal,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                maxLines = 4
                            )
                            
                            if (messageText.isEmpty()) {
                                ChatActionButton(Icons.Default.EmojiEmotions)
                                ChatActionButton(Icons.Default.CurrencyExchange, onClick = { paymentMode = true })
                                ChatActionButton(Icons.Default.Mic)
                                ChatActionButton(Icons.Default.BackHand)
                            } else {
                                IconButton(
                                    onClick = { 
                                        chatViewModel.sendMessage(contactId, messageText)
                                        chatViewModel.setMessageText("")
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(KaspaTeal, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val scrollState = rememberLazyListState()
        
        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                scrollState.animateScrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No messages yet",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(messages) { msg ->
                    MessageBubble(msg)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity) {
    val isSent = message.direction == "sent"
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        if (message.type == "pay") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(Icons.Default.MonetizationOn, null, tint = Color(0xFFF39C12), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Payment", color = Color(0xFFF39C12), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            
            Surface(
                color = if (isSent) KaspaTeal else Color(0xFF1C1C1E),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = message.plaintextBody ?: "Payment",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (isSent) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Surface(
                color = if (isSent) Color(0xFF2C2C2E) else Color(0xFF1C1C1E),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = message.plaintextBody ?: "",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White
                )
            }
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                text = "11:32", 
                color = Color.Gray,
                fontSize = 11.sp
            )
            if (isSent) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CD964),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun ChatActionButton(icon: ImageVector, onClick: () -> Unit = {}) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(Color(0xFF1C1C1E), CircleShape)
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(navController: NavController) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Contacts") }) }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("Contact list — Phase 5", color = KaspaSubtext)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: WalletViewModel, 
    navController: NavController,
    onNavigateToSeed: () -> Unit,
    connectionViewModel: ConnectionViewModel = hiltViewModel()
) {
    val address by viewModel.address.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val accountName by viewModel.accountName.collectAsState()
    val connStatus by connectionViewModel.status.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Column(modifier = Modifier.background(Color.Black).padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(16.dp))
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { navController.navigate("connection_status") },
                    onAddClick = { navController.navigate("create_chat") },
                    connectionStatus = connStatus
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "Name") {
                Text(
                    text = accountName ?: "No Name",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                )
            }

            SettingsSection(title = "KNS Domains") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "No domains yet.", color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AddCircleOutline, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Inscribe New Domain", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SettingsSection(title = "KNS Profile") {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xFF2C3E50), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("TE", color = Color(0xFF7F8C8D), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("No on-chain profile fields set.", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SettingsSection(title = "Address") {
                val clipboardManager = LocalClipboardManager.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            if (address != null) {
                                clipboardManager.setText(AnnotatedString(address!!))
                            }
                        }
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val qrPainter = rememberQrBitmapPainter(address ?: "")
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = qrPainter,
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Scan to share account. Tap anywhere here to copy address.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = address ?: "Loading...",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            SettingsSection(title = "Balance") {
                val clipboardManager = LocalClipboardManager.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clipboardManager.setText(AnnotatedString(balance)) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$balance KAS", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                }
            }

            SettingsSection(title = "Gift") {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Verified, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gift already claimed", color = Color.Gray)
                }
            }

            SettingsSection(title = "Info") {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Created", color = Color.White)
                    Text("Apr 22, 2026 at 8:33 AM", color = Color.Gray)
                }
            }

            SettingsSection(title = "Actions") {
                SettingsActionItem("View Seed Phrase", Icons.Default.Key, KaspaTeal) {
                    onNavigateToSeed()
                }
                SettingsDivider()
                SettingsActionItem("Log Out", Icons.AutoMirrored.Filled.Logout, Color.Red) {
                    viewModel.logout()
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SeedPhraseScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val mnemonic = remember { viewModel.getActiveMnemonic() ?: "" }
    val privateKey = remember { viewModel.getPrivateKeyHex() }
    val words = remember { mnemonic.split(" ") }
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Seed Phrase", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onBack) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            Spacer(modifier = Modifier.weight(1f))

            if (!revealed) {
                Column(
                    modifier = Modifier
                        .clickable { revealed = true }
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Tap to reveal seed phrase",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
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
                                .background(Color(0xFF1C1C1E))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = word,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (revealed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { 
                        clipboardManager.setText(AnnotatedString(mnemonic))
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Seed Phrase", color = KaspaTeal)
                        }
                    }
                    TextButton(onClick = { 
                        clipboardManager.setText(AnnotatedString(privateKey))
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tag, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Private Key Hex", color = KaspaTeal)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val balance by walletViewModel.balance.collectAsState()
    val connStatus by connectionViewModel.status.collectAsState()
    val estimateFees by chatViewModel.estimateFeesEnabled.collectAsState()
    val archivedCount by chatViewModel.archivedCount.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Column(modifier = Modifier.background(Color.Black).padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(16.dp))
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { navController.navigate("connection_status") },
                    onAddClick = { navController.navigate("create_chat") },
                    connectionStatus = connStatus
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "Chats") {
                SettingsSwitchItem("Estimate fees while composing", estimateFees) {
                    chatViewModel.updateEstimateFees(it)
                }
                SettingsDivider()
                SettingsSwitchItem("Hide auto-created payment chats", false)
                SettingsDivider()
                SettingsSwitchItem("Show contact balance", true)
                SettingsDivider()
                SettingsNavigationItem("Notifications", Icons.Default.NotificationsNone, "Remote push")
                SettingsDivider()
                SettingsNavigationItem("Archived Chats", Icons.Outlined.Inventory2, archivedCount.toString(), onClick = {
                    navController.navigate("archived_chats")
                })
            }

            SettingsSection(title = "Contacts") {
                SettingsSwitchItem("Sync system contacts", true)
                SettingsDivider()
                SettingsSwitchItem("Autocreate system contacts", false)
                SettingsFooter("Uses your device contacts to match and enrich Kaspa contacts.")
            }

            SettingsSection(title = "Storage") {
                SettingsSwitchItem("Store encrypted messages in Google Drive", true)
                SettingsFooter("Required for cross-device sync and backup of sent messages.")
                SettingsDivider()
                SettingsNavigationItem("Message retention", null, "30 days", showIcon = false)
                SettingsFooter("Storage used: 436 KB")
            }

            SettingsSection(title = "Chat History") {
                SettingsActionItem("Export Chat History", Icons.Default.FileUpload, KaspaTeal)
                SettingsDivider()
                SettingsActionItem("Import Chat History", Icons.Default.FileDownload, KaspaTeal)
            }

            SettingsSection(title = "Connection") {
                SettingsNavigationItem("Connection Settings", Icons.Default.Language, "Mainnet", onClick = {
                    navController.navigate("connection_settings")
                })
            }

            SettingsSection(title = "About") {
                SettingsInfoItem("Version", "1.1.1 (202602271119)")
                SettingsDivider()
                SettingsInfoItem("Website", "kachat.app", KaspaTeal)
                SettingsDivider()
                SettingsInfoItem("Support Email", "support@kachat.app", KaspaTeal)
            }

            SettingsSection(title = "Diagnostics") {
                SettingsActionItem("Export Diagnostics Archive", Icons.Default.FileUpload, KaspaTeal)
            }

            SettingsSection(title = "Danger Zone") {
                SettingsActionItem("Wipe and re-sync incoming messages", Icons.Default.Cached, Color.Red)
                SettingsDivider()
                SettingsActionItem("Wipe account & messages", Icons.Default.PersonRemoveAlt1, Color.Red)
                SettingsDivider()
                SettingsActionItem("Wipe account & messages & Cloud", Icons.Default.CloudOff, Color.Red)
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.Gray,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
        ) {
            content()
        }
    }
}

@Composable
fun SettingsSwitchItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = KaspaTeal,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.Gray
            )
        )
    }
}

@Composable
fun SettingsNavigationItem(label: String, icon: ImageVector?, value: String = "", showIcon: Boolean = true, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIcon && icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(text = value, color = Color.Gray, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun SettingsActionItem(label: String, icon: ImageVector, color: Color, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsInfoItem(label: String, value: String, valueColor: Color = Color.Gray) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, color = valueColor, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.Black.copy(alpha = 0.2f), thickness = 0.5.dp)
}

@Composable
fun SettingsFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
fun TopStatusBar(
    balance: String, 
    onStatusClick: () -> Unit,
    onAddClick: () -> Unit = {},
    connectionStatus: ConnStatus = ConnStatus.CONNECTED
) {
    val statusColor = when (connectionStatus) {
        ConnStatus.CONNECTED -> Color(0xFF4CD964)
        ConnStatus.WEAK -> Color(0xFFF39C12)
        ConnStatus.DISCONNECTED -> Color.Red
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onStatusClick,
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF1C1C1E), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, CircleShape)
            )
        }

        Text(
            text = balance,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )

        IconButton(
            onClick = onAddClick,
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF1C1C1E), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.PersonAddAlt1,
                contentDescription = "Add Contact",
                tint = KaspaTeal,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionStatusScreen(onBack: () -> Unit, viewModel: ConnectionViewModel = hiltViewModel()) {
    val network by viewModel.network.collectAsState()
    val indexerUrl by viewModel.indexerUrl.collectAsState()
    val pushIndexerUrl by viewModel.pushIndexerUrl.collectAsState()
    
    val activeNodes by viewModel.activeNodes.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Connection Status", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onBack) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "Connection Status") {
                ConnectionInfoRow("Status", "Connected", Color(0xFF4CD964))
                SettingsDivider()
                ConnectionInfoRow("Protocol", "gRPC (plaintext)")
                SettingsDivider()
                ConnectionInfoRow("Connected Node", activeNodes.firstOrNull()?.ip ?: "None")
                SettingsDivider()
                ConnectionInfoRow("Latency", "52 ms", Color(0xFF4CD964))
                SettingsDivider()
                ConnectionInfoRow("Distance", "0.8k km")
                SettingsDivider()
                ConnectionInfoRow("Country", "US")
                SettingsDivider()
                ConnectionInfoRow("Indexer", indexerUrl.substringAfter("://").substringBefore("/"))
                SettingsDivider()
                ConnectionInfoRow("Push Register", pushIndexerUrl.substringAfter("://").substringBefore("/"))
                SettingsDivider()
                ConnectionInfoRow("Last Sync", "51s ago")
            }

            SettingsSection(title = "Pool Status") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    PoolStatItem("Active", "12", Color(0xFF4CD964))
                    PoolStatItem("Verified", "27", Color(0xFF2196F3))
                    PoolStatItem("Total", "380", Color.Gray)
                }
                SettingsDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pool Health", color = Color.White)
                    Text("Healthy", color = Color(0xFF4CD964))
                }
            }

            SettingsSection(title = "Actions") {
                SettingsActionItem("Refresh Pool", Icons.Default.Refresh, KaspaTeal)
                SettingsDivider()
                SettingsActionItem("Clear Connection Pool", Icons.Default.DeleteSweep, Color.Red)
                SettingsDivider()
                SettingsActionItem("Reconnect", Icons.Default.Replay, KaspaTeal)
            }

            Text(
                text = "Primary: 67.235.212.32",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )

            SettingsSection(title = "Add Custom Endpoint") {
                var endpoint by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        placeholder = { Text("grpc://host:port", color = Color.DarkGray) },
                        modifier = Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2C2C2E),
                            unfocusedContainerColor = Color(0xFF2C2C2E),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = KaspaTeal,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(
                        onClick = { },
                        modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.Black)
                    }
                }
            }

            Text(
                text = "Manual endpoints have highest priority",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CD964), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(text = "Active Nodes", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Text(text = "12", color = Color.Gray)
            }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E))
            ) {
                activeNodes.forEachIndexed { index, node ->
                    ActiveNodeRow(node)
                    if (index < activeNodes.size - 1) SettingsDivider()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color.Gray, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(text = "Other Nodes", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Text(text = "380", color = Color.Gray)
            }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E))
            ) {
                allNodes.forEachIndexed { index, node ->
                    AllNodeRow(node)
                    if (index < allNodes.size - 1) SettingsDivider()
                }
            }

            Text(
                text = "All discovered nodes sorted by state and latency. Nodes are deduplicated by host:port.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, viewModel: ConnectionViewModel = hiltViewModel()) {
    val network by viewModel.network.collectAsState()
    val indexerUrl by viewModel.indexerUrl.collectAsState()
    val pushIndexerUrl by viewModel.pushIndexerUrl.collectAsState()
    val knsApiUrl by viewModel.knsApiUrl.collectAsState()
    val kaspaRestApiUrl by viewModel.kaspaRestApiUrl.collectAsState()
    val discoverNewPeers by viewModel.discoverNewPeers.collectAsState()
    
    val activeNodes by viewModel.activeNodes.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Connection Settings", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp).padding(end = 8.dp)
                    ) {
                        Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "Network") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Network", color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(network, color = KaspaTeal)
                        Icon(Icons.Default.UnfoldMore, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
                SettingsFooter("Select mainnet for real transactions or testnet for testing")
            }

            SettingsSection(title = "KaChat Indexer") {
                ConnectionUrlField(label = "Indexer URL", value = indexerUrl, onValueChange = { viewModel.setIndexerUrl(it) })
                SettingsFooter("Message indexer service for chat functionality")
            }

            SettingsSection(title = "Push Registration") {
                ConnectionUrlField(label = "Push Indexer URL", value = pushIndexerUrl, onValueChange = { viewModel.setPushIndexerUrl(it) })
                SettingsFooter("Used only for push registration and updates")
            }

            SettingsSection(title = "Kaspa Name Service") {
                ConnectionUrlField(label = "KNS API URL", value = knsApiUrl, onValueChange = { viewModel.setKnsApiUrl(it) })
                SettingsFooter("KNS domain resolution service")
            }

            SettingsSection(title = "Kaspa Explorer API") {
                ConnectionUrlField(label = "Kaspa REST API URL", value = kaspaRestApiUrl, onValueChange = { viewModel.setKaspaRestApiUrl(it) })
                SettingsFooter("REST API for transaction history and balance lookups")
            }

            SettingsSection(title = "Node Pool") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    PoolStatItem("Active", "12", Color(0xFF4CD964))
                    PoolStatItem("Verified", "27", Color(0xFF2196F3))
                    PoolStatItem("Profiled", "2", Color(0xFFF0AD4E))
                    PoolStatItem("Other", "291", Color.Gray)
                }
                SettingsDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Primary Latency", color = Color.Gray)
                    Text("61 ms", color = Color(0xFF4CD964))
                }
                SettingsDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pool Health", color = Color.Gray)
                    Text("Healthy", color = Color(0xFF4CD964))
                }
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Discover new peers", color = Color.White)
                    Switch(
                        checked = discoverNewPeers,
                        onCheckedChange = { viewModel.setDiscoverNewPeers(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = KaspaTeal)
                    )
                }
                SettingsDivider()
                TextButton(onClick = { }, modifier = Modifier.padding(8.dp)) {
                    Text("Refresh Pool Now", color = KaspaTeal)
                }
                SettingsFooter("Active = in use, Verified = ready, Profiled = checked, Other = candidates/suspect")
            }

            SettingsSection(title = "Custom Endpoint") {
                var endpoint by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        placeholder = { Text("grpc://host:port", color = Color.DarkGray) },
                        modifier = Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2C2C2E),
                            unfocusedContainerColor = Color(0xFF2C2C2E),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = KaspaTeal,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(12.dp))
                    IconButton(
                        onClick = { },
                        modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.Black)
                    }
                }
                SettingsFooter("Manual endpoints have highest priority and are never auto-removed")
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CD964), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(text = "Active Nodes", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Text(text = "12", color = Color.Gray)
            }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E))
            ) {
                activeNodes.forEachIndexed { index, node ->
                    ActiveNodeRow(node)
                    if (index < activeNodes.size - 1) SettingsDivider()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color.Gray, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(text = "Other Nodes", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Text(text = "293", color = Color.Gray)
            }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E))
            ) {
                allNodes.forEachIndexed { index, node ->
                    AllNodeRow(node)
                    if (index < allNodes.size - 1) SettingsDivider()
                }
            }

            Text(
                text = "Profiled, candidate, and suspect nodes. Showing first 20.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Reset to Defaults", color = Color.Red, fontSize = 18.sp)
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun ConnectionUrlField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = KaspaTeal,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            textStyle = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun ConnectionInfoRow(label: String, value: String, valueColor: Color = Color.Gray) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label == "Status") {
                Box(modifier = Modifier.size(8.dp).background(valueColor, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PoolStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        Text(value, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ActiveNodeRow(node: NodeInfo) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(node.ip, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.background(Color(0xFF1E3A1E), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(node.status, color = Color(node.color), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row {
                Text(node.type, color = Color(0xFF2196F3), fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(node.latency, color = Color(0xFFF39C12), fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(node.distance, color = Color.Gray, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(node.country, color = Color.Gray, fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text("9✓", color = Color(0xFF4CD964), fontSize = 10.sp)
            }
            Text("DAA: ${node.daaScore}", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
fun AllNodeRow(node: NodeInfo) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(Color(node.color), CircleShape))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Shield, null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(node.ip, color = Color.White, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.country, color = Color.Gray, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(node.distance, color = Color.Gray, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(node.latency, color = Color(0xFFF39C12), fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.background(Color(0x33FF3B30), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(node.status, color = Color(node.color), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun rememberQrBitmapPainter(
    content: String,
    size: Int = 512,
    padding: Int = 0
): BitmapPainter {
    val density = LocalDensity.current
    val sizePx = with(density) { size.dp.roundToPx() }
    
    val bitmap = remember(content) {
        if (content.isEmpty()) {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        } else {
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(EncodeHintType.MARGIN to padding)
            )
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bitmap.asImageBitmap()
        }
    }
    return BitmapPainter(bitmap)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatScreen(
    onBack: () -> Unit,
    onChatCreated: (String) -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val isValidAddress = remember(address) { KaspaAddress.isValid(address) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Create chat", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Cancel", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { 
                            chatViewModel.addContact(address, name)
                            onChatCreated(address)
                        },
                        enabled = isValidAddress
                    ) {
                        Text("Add", color = if (isValidAddress) KaspaTeal else Color.DarkGray, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Address",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Kaspa Address or KNS Domain",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
                TextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("kaspa:qr... or name.kas", color = Color.DarkGray) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = KaspaTeal,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
                
                if (isValidAddress) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CD964),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Valid address",
                            color = Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    CreateChatActionItem(Icons.Default.PersonAddAlt1, "Import") { }
                    CreateChatActionItem(Icons.Default.ContentPaste, "Paste") { }
                    CreateChatActionItem(Icons.Default.QrCodeScanner, "Scan QR") { }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Enter a Kaspa address (kaspa:...) or KNS domain name (e.g., alice.kas)",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Name (Optional)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Contact name", color = Color.DarkGray) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(25.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1C1C1E),
                    unfocusedContainerColor = Color(0xFF1C1C1E),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = KaspaTeal,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )
        }
    }
}

@Composable
fun CreateChatActionItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = KaspaTeal, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoScreen(
    contactId: String,
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val conversation = chatViewModel.conversations.collectAsState().value.find { it.contact.id == contactId }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val contactBalances by chatViewModel.contactBalances.collectAsState()
    val contactBalance = contactBalances[contactId] ?: "0.00000000"
    
    var contactName by remember { mutableStateOf("") }

    // Synchronize local state with database when it loads
    LaunchedEffect(conversation?.contact?.alias) {
        contactName = conversation?.contact?.alias ?: ""
    }
    
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Chat Info", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Cancel", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        chatViewModel.updateContactName(contactId, contactName)
                        onBack()
                    }) {
                        Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            
            // Header Card
            Surface(
                color = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color(0xFF2C2C2E), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (conversation?.contact?.alias ?: contactId.takeLast(8)).take(2).uppercase(),
                            color = KaspaTeal,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        TextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            placeholder = { Text("Contact Name", color = Color.Gray) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = KaspaTeal,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.fillMaxWidth().offset(x = (-16).dp)
                        )
                        Text(
                            text = contactId.take(12) + "..." + contactId.takeLast(12),
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 0.dp)
                        )
                    }
                }
            }

            SettingsSection(title = "Incoming Notifications") {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Default (Sound)", color = KaspaTeal)
                    Icon(Icons.Default.UnfoldMore, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
                SettingsFooter("Default follows Settings > Notifications. Off disables notifications for this contact.")
            }

            SettingsSection(title = "Address") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = contactId,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(contactId)) }) {
                            Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.clickable { showQr = !showQr },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.QrCode, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (showQr) "Hide QR" else "Show QR", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    
                    if (showQr) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            val qrPainter = rememberQrBitmapPainter(contactId)
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(qrPainter, "QR Code", modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }

            SettingsSection(title = "Balance") {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$contactBalance KAS", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(contactBalance)) }) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    }
                }
            }

            SettingsSection(title = "System Contact") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Not linked", color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PersonAddAlt1, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Link from Contacts", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            SettingsSection(title = "Info") {
                Column(modifier = Modifier.padding(16.dp)) {
                    val addedDate = remember(conversation) {
                        conversation?.contact?.addedAt?.let {
                            java.text.SimpleDateFormat("MMMM dd, yyyy", java.util.Locale.US).format(java.util.Date(it))
                        } ?: "Unknown"
                    }
                    
                    val lastMessageTime = remember(messages) {
                        messages.firstOrNull()?.blockTimestamp?.let {
                            val diff = System.currentTimeMillis() - it
                            val hours = diff / (1000 * 60 * 60)
                            val minutes = (diff / (1000 * 60)) % 60
                            if (hours > 0) "${hours} hr, ${minutes} min" else "${minutes} min"
                        } ?: "None"
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Added", color = Color.White)
                        Text(addedDate, color = Color.Gray)
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Message", color = Color.White)
                        Text(lastMessageTime, color = Color.Gray)
                    }
                    Spacer(Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        InfoStatItem(label = "Sent", value = messages.count { it.direction == "sent" }.toString())
                        InfoStatItem(label = "Received", value = messages.count { it.direction == "received" }.toString())
                        InfoStatItem(label = "Total", value = messages.size.toString())
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun InfoStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = Color.Gray, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    navController: NavController,
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val archivedConversations by chatViewModel.archivedConversations.collectAsState()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Archived Chats", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        if (archivedConversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No archived chats", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(archivedConversations) { conversation ->
                    Surface(
                        color = Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conversation.contact.alias ?: conversation.contact.id.takeLast(8),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = conversation.contact.id.take(16) + "...",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { chatViewModel.unarchiveChat(conversation.contact.id) }) {
                                Text("Unarchive", color = KaspaTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
