package com.kachat.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kachat.app.models.BackupRetention
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.ImagePrep
import com.kachat.app.util.MessageReply
import com.kachat.app.util.TextLinkify
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.VoiceMessage
import com.kachat.app.util.VoiceMessageContent
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.ConnectionStatus as ConnStatus
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.NodeInfo
import com.kachat.app.viewmodels.SettingsViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatThreadScreen(
    navController: NavController,
    contactId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel(),
    startInPaymentMode: Boolean = false
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val conversation = conversations.find { it.contact.id == contactId }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val contactBalances by chatViewModel.contactBalances.collectAsState()
    val contactBalance = contactBalances[contactId] ?: "0.00000000"
    val showContactBalance by chatViewModel.showContactBalance.collectAsState()
    val requirePhotoApprovalForNewContacts by chatViewModel.requirePhotoApprovalForNewContacts.collectAsState()
    val revealedPhotoTxIds by chatViewModel.revealedPhotoTxIds.collectAsState()

    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val spendingBalance by walletViewModel.spendingBalance.collectAsState()
    val spendingBalanceSompi by walletViewModel.spendingBalanceSompi.collectAsState()
    val myKnsProfile by walletViewModel.knsProfile.collectAsState()
    val myAddress by walletViewModel.address.collectAsState()
    val paymentAmount by chatViewModel.paymentAmount.collectAsState()
    val estimatedFee by chatViewModel.estimatedFeeSompi.collectAsState()
    val estimateFeesEnabled by chatViewModel.estimateFeesEnabled.collectAsState()
    val messageText by chatViewModel.messageText.collectAsState()
    val voiceRecordingState by chatViewModel.voiceRecordingState.collectAsState()
    val pendingPhotoUri by chatViewModel.pendingPhotoUri.collectAsState()
    val replyingTo by chatViewModel.replyingTo.collectAsState()

    var paymentMode by remember { mutableStateOf(startInPaymentMode) }
    val clipboardManager = LocalClipboardManager.current
    val micContext = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chatViewModel.startVoiceRecording(contactId)
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatViewModel.setPendingPhoto(uri)
    }
    val startVoiceRecordingIfPermitted = {
        if (chatViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                chatViewModel.startVoiceRecording(contactId)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Saving a photo to the gallery needs WRITE_EXTERNAL_STORAGE only on API 28 and below (scoped
    // storage makes MediaStore inserts permission-free from API 29 on) — the pending bytes/name
    // survive the async permission prompt in this state, then get saved once it resolves.
    var pendingPhotoSave by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val writeStoragePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingPhotoSave
        pendingPhotoSave = null
        if (granted && pending != null) {
            val saved = ImagePrep.saveToGallery(micContext, pending.first, pending.second)
            Toast.makeText(micContext, if (saved) "Photo saved" else "Could not save photo", Toast.LENGTH_SHORT).show()
        }
    }
    val savePhotoIfPermitted = { bytes: ByteArray, fileName: String ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(micContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            val saved = ImagePrep.saveToGallery(micContext, bytes, fileName)
            Toast.makeText(micContext, if (saved) "Photo saved" else "Could not save photo", Toast.LENGTH_SHORT).show()
        } else {
            pendingPhotoSave = bytes to fileName
            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(contactId) {
        chatViewModel.refreshContactBalance(contactId)
        chatViewModel.markAsRead(contactId)
    }

    DisposableEffect(contactId) {
        chatViewModel.setActiveContact(contactId)
        onDispose { chatViewModel.setActiveContact(null) }
    }

    LaunchedEffect(paymentMode) {
        if (paymentMode) {
            chatViewModel.refreshSpendingUtxos()
            walletViewModel.refreshSpendingAddress()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(contactId))
                            Toast.makeText(micContext, "Address copied", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(
                            text = conversation?.contact?.alias ?: contactId.takeLast(8),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (showContactBalance) {
                            Text(
                                text = "$contactBalance KAS",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    val statusColor = Color(dotColorHex)
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
                    IconButton(onClick = { navController.navigate("chat_info/$contactId") }) {
                        Icon(Icons.Default.Info, "Chat Info", tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            // navigationBarsPadding() keeps the mic/send row clear of the system nav bar
            // (gesture pill or 3-button bar) when the keyboard is closed — its height varies
            // a lot across devices/manufacturers, so a fixed dp padding isn't enough on every
            // phone. imePadding() on the Scaffold above already handles the keyboard-open case.
            Column(modifier = Modifier.background(Color.Black).navigationBarsPadding().padding(8.dp)) {
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
                                        text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
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
                                    text = "available: $spendingBalance",
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
                                        // Mirror KaspaWalletEngine's own fee calculation exactly
                                        // (real Kaspa mass model, assuming a recipient + change
                                        // output) so the amount filled in here is always actually
                                        // sendable — the previous naive formula (300 + count*100)
                                        // didn't match the real fee, so "Max" sends kept failing
                                        // with "insufficient funds".
                                        val mass = com.kachat.app.util.KaspaMass.calculateMass(
                                            numInputs = currentUtxos.size.coerceAtLeast(1),
                                            outputScriptLens = listOf(34, 34),
                                            payloadSize = 0
                                        )
                                        val fee = com.kachat.app.util.KaspaMass.calculateFee(mass, networkFeeRate.toLong())

                                        val maxSendableSompi = (spendingBalanceSompi - fee).coerceAtLeast(0L)
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
                                if (paymentAmount.isNotEmpty()) {
                                    chatViewModel.sendPayment(contactId, paymentAmount)
                                    chatViewModel.setPaymentAmount("")
                                    paymentMode = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Send Payment", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (pendingPhotoUri != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (estimateFeesEnabled && estimatedFee != null) {
                            Surface(
                                color = Color(0xFF1C1C1E),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                            ) {
                                Text(
                                    text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1C1C1E))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = { chatViewModel.cancelPendingPhoto() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Cancel photo", tint = Color(0xFFFF3B30))
                            }
                            val thumbnailContext = LocalContext.current
                            // Fixed downsample for a quick composition-time thumbnail decode — the real
                            // compression (ImagePrep.prepareForChatMessage) happens off the main thread
                            // in the ViewModel when Send is tapped, this is display-only.
                            val thumbnail = remember(pendingPhotoUri) {
                                pendingPhotoUri?.let { uri ->
                                    try {
                                        thumbnailContext.contentResolver.openInputStream(uri)?.use {
                                            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            }
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text("Photo", color = Color.White, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { chatViewModel.sendPendingPhoto(contactId) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(KaspaTeal, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send photo",
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                } else if (voiceRecordingState.status == ChatViewModel.VoiceRecordingStatus.RECORDING) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (estimateFeesEnabled && estimatedFee != null) {
                            Surface(
                                color = Color(0xFF1C1C1E),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                            ) {
                                Text(
                                    text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1C1C1E))
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = { chatViewModel.cancelVoiceRecording() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Cancel recording", tint = Color(0xFFFF3B30))
                            }
                            Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                            Text(
                                text = "Recording... ${formatRecordingElapsed(voiceRecordingState.elapsedMs)}",
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { chatViewModel.stopAndSendVoiceRecording(contactId) },
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
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        replyingTo?.let { reply ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Replying to ${if (reply.direction == "sent") "yourself" else (conversation?.contact?.alias ?: contactId.takeLast(10))}",
                                        color = KaspaTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        VoiceMessage.parseOrNull(reply.plaintextBody)?.let { "🎤 Audio message" }
                                            ?: ImageMessage.parseOrNull(reply.plaintextBody)?.let { "📷 Photo" }
                                            ?: MessageReply.parseOrNull(reply.plaintextBody)?.text
                                            ?: (reply.plaintextBody ?: ""),
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { chatViewModel.cancelReply() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = Color.Gray)
                                }
                            }
                        }
                        if (estimateFeesEnabled && estimatedFee != null && messageText.isNotEmpty()) {
                            Surface(
                                color = Color(0xFF1C1C1E),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                            ) {
                                Text(
                                    text = "fee: ${ChatRepository.formatKas(estimatedFee ?: 0L)} KAS",
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
                                ChatActionButton(Icons.Default.CurrencyExchange, onClick = { paymentMode = true })
                                ChatActionButton(
                                    Icons.Default.Image,
                                    onClick = { photoPickerLauncher.launch("image/*") }
                                )
                                ChatActionButton(
                                    Icons.Default.Mic,
                                    onClick = { startVoiceRecordingIfPermitted() }
                                )
                                if (conversation?.contact?.handshakeComplete != true) {
                                    ChatActionButton(
                                        Icons.Default.BackHand,
                                        onClick = { chatViewModel.sendHandshake(contactId) }
                                    )
                                }
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
        val coroutineScope = rememberCoroutineScope()

        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                scrollState.animateScrollToItem(messages.size - 1)
            }
        }

        // Also re-pin to the bottom when the keyboard's own inset animation finishes (not
        // just when messageText changes — the IME resize is system-driven and can complete
        // after Compose's own recomposition, so keying on messageText alone raced with it
        // and left the latest message hidden behind the keyboard without a real scroll-up).
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible, messageText.isEmpty()) {
            if (messages.isNotEmpty()) {
                scrollState.animateScrollToItem(messages.size - 1)
            }
        }

        // Not scrollState.canScrollForward — that flips true for a frame or two whenever
        // the viewport merely shrinks (keyboard opening, the fee-estimate row appearing),
        // even though the user never scrolled and is still logically at the latest
        // message. Only show the button once the last message isn't even partially
        // among the visible items — a real, deliberate scroll away from the bottom.
        // Tolerate a 1-item gap — the keyboard/fee-row resize can leave the second-to-last
        // message as the last fully visible one for a moment even when the re-pin effect
        // above hasn't finished animating yet. A genuine scroll-up to read history moves
        // by much more than one item, so this threshold still catches real cases.
        val showScrollToBottom by remember {
            derivedStateOf {
                val lastVisibleIndex = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                lastVisibleIndex != null && lastVisibleIndex < messages.lastIndex - 1
            }
        }

        // Swipe-left-to-reveal-timestamps (iMessage-style) — see the matching implementation in
        // BroadcastScreens.kt for the full rationale; kept in sync with it.
        val revealOffsetPx = remember { Animatable(0f) }
        val maxRevealOffsetPx = with(LocalDensity.current) { 64.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        coroutineScope.launch {
                            revealOffsetPx.snapTo((revealOffsetPx.value + delta).coerceIn(-maxRevealOffsetPx, 0f))
                        }
                    },
                    onDragStopped = { coroutineScope.launch { revealOffsetPx.animateTo(0f) } }
                )
        ) {
            LazyColumn(
                state = scrollState,
                modifier = Modifier
                    .fillMaxSize()
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
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                        if (index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, msg.blockTimestamp)) {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(12.dp)) {
                                    Text(
                                        ChatTimeFormat.formatDateDivider(msg.blockTimestamp),
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        MessageBubble(
                            message = msg,
                            contactAvatarUrl = conversation?.contact?.knsAvatarUrl,
                            contactAvatarFallback = conversation?.contact?.alias ?: contactId.takeLast(8),
                            myAvatarUrl = myKnsProfile?.avatarUrl,
                            myAvatarFallback = myAddress?.takeLast(8) ?: "",
                            isPendingRequest = msg.type == MessageProtocol.TYPE_HANDSHAKE &&
                                msg.direction == "received" &&
                                conversation?.contact?.conversationStatus == "pending",
                            isHandshakeComplete = conversation?.contact?.conversationStatus == "active",
                            onAccept = { chatViewModel.acceptHandshake(contactId) },
                            onDecline = { chatViewModel.declineHandshake(contactId) },
                            onRetry = { chatViewModel.retrySendMessage(msg) },
                            onReply = { chatViewModel.startReplyTo(msg) },
                            onSavePhoto = savePhotoIfPermitted,
                            revealOffsetPx = revealOffsetPx,
                            maxRevealOffsetPx = maxRevealOffsetPx,
                            photosBlocked = !com.kachat.app.repository.ChatRepository.shouldAutoDisplayPhotos(
                                conversation?.contact,
                                requirePhotoApprovalForNewContacts
                            ),
                            isPhotoRevealed = msg.id in revealedPhotoTxIds,
                            onRevealPhoto = { chatViewModel.revealPhoto(msg.id) }
                        )
                    }
                    if (ChatViewModel.shouldShowUnnotifiedWarning(messages)) {
                        item { UnnotifiedMessageBanner() }
                    }
                }
            }

            if (showScrollToBottom && messages.isNotEmpty()) {
                IconButton(
                    onClick = {
                        coroutineScope.launch { scrollState.animateScrollToItem(messages.size - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(44.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to latest",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun UnnotifiedMessageBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFF9500).copy(alpha = 0.08f))
            .border(0.5.dp, Color(0xFFFF9500).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFF9500),
            modifier = Modifier.size(16.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "When you message someone new on KaChat, they won't get a notification and your message stays hidden until they message you back or add as well. This protects against spam and increases your privacy. If you want them to be notified, click the hand icon to send a request to communicate which will cost 0.2 KAS. (Note: all non KaChat messaging apps will require a request to communicate)",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    contactAvatarUrl: String? = null,
    contactAvatarFallback: String = "",
    myAvatarUrl: String? = null,
    myAvatarFallback: String = "",
    isPendingRequest: Boolean = false,
    isHandshakeComplete: Boolean = false,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    onRetry: () -> Unit = {},
    onReply: () -> Unit = {},
    onSavePhoto: (ByteArray, String) -> Unit = { _, _ -> },
    revealOffsetPx: Animatable<Float, AnimationVector1D> = remember { Animatable(0f) },
    maxRevealOffsetPx: Float = 1f,
    photosBlocked: Boolean = false,
    isPhotoRevealed: Boolean = false,
    onRevealPhoto: () -> Unit = {}
) {
    val isSent = message.direction == "sent"
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val replyContent = remember(message.plaintextBody) { MessageReply.parseOrNull(message.plaintextBody) }
    val displayBody = replyContent?.text ?: message.plaintextBody
    val imageContent = remember(displayBody) { ImageMessage.parseOrNull(displayBody) }

    if (isPendingRequest) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Surface(
                color = Color(0xFF2C2C2E),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = "👋 Request to communicate",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Surface(
                color = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("👋", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Contact has requested permission to communicate",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Accept", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onDecline,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Decline", color = Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = remember(message.blockTimestamp) { ChatTimeFormat.formatMessageTime(message.blockTimestamp) },
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .alpha((-revealOffsetPx.value / maxRevealOffsetPx).coerceIn(0f, 1f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(revealOffsetPx.value.toInt(), 0) },
            horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
        if (!isSent) {
            ContactAvatar(imageUrl = contactAvatarUrl, fallbackText = contactAvatarFallback, size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
        if (replyContent != null) {
            Surface(
                color = Color(0xFF2C2C2E),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 240.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        if (replyContent.replyToSender == message.walletAddress) "You" else "Them",
                        color = KaspaTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        replyContent.replyToPreview,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box {
            if (message.type == "pay") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Default.MonetizationOn, null, tint = Color(0xFFF39C12), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Payment", color = Color(0xFFF39C12), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Surface(
                    color = if (isSent) KaspaTeal else Color(0xFF1C1C1E),
                    shape = RoundedCornerShape(20.dp),
                    // Same off-screen-avatar risk as the plain text bubble — a long payment memo
                    // needs the same cap.
                    modifier = Modifier.widthIn(max = 280.dp).combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                ) {
                    Text(
                        text = message.plaintextBody ?: "Payment",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = if (isSent) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (message.type == MessageProtocol.TYPE_HANDSHAKE) {
                // Matches the iOS bubble for any handshake message once it's not the
                // pending accept/decline card anymore (sent, or an already-accepted
                // received one) — a small pill above a "[Request to communicate]" bubble.
                // A message I sent is always framed as my outreach/response and never
                // changes. Their message only flips to "completed" once the connection
                // is actually live — i.e. once I've received their side of it.
                val showCompleted = !isSent && isHandshakeComplete
                val pillText = if (showCompleted) "🤝 Handshake completed" else "👋 Request to communicate"
                val bodyText = if (showCompleted) "[Handshake completed]" else "[Request to communicate]"
                Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
                    Surface(
                        color = Color(0xFF2C2C2E),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = pillText,
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        color = if (isSent) KaspaTeal else Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                    ) {
                        Text(
                            text = bodyText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            color = if (isSent) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else if (VoiceMessage.parseOrNull(displayBody) != null) {
                AudioBubble(
                    voiceContent = VoiceMessage.parseOrNull(displayBody)!!,
                    isSent = isSent,
                    onLongPress = { showMenu = true },
                    onDoubleClick = onReply
                )
            } else if (ImageMessage.parseOrNull(displayBody) != null) {
                ImageBubble(
                    imageContent = ImageMessage.parseOrNull(displayBody)!!,
                    isSent = isSent,
                    onLongPress = { showMenu = true },
                    onDoubleClick = onReply,
                    photosBlocked = !isSent && photosBlocked,
                    senderDisplayName = contactAvatarFallback,
                    isRevealed = isPhotoRevealed,
                    onReveal = onRevealPhoto
                )
            } else {
                val bodyText = displayBody ?: ""
                val uriHandler = LocalUriHandler.current
                var textLayoutResult by remember(bodyText) { mutableStateOf<TextLayoutResult?>(null) }
                // Sent bubbles are teal (matching broadcast rooms' sent-message color) with black
                // text/links for contrast — a teal link on a teal background would be unreadable.
                val linkColor = if (isSent) Color.Black else KaspaTeal
                val annotatedBody = remember(bodyText, isSent) {
                    buildAnnotatedString {
                        append(bodyText)
                        for (match in TextLinkify.findUrls(bodyText)) {
                            addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                            addStringAnnotation("URL", match.uri, match.range.first, match.range.last + 1)
                        }
                    }
                }
                Surface(
                    color = if (isSent) KaspaTeal else Color(0xFF1C1C1E),
                    shape = RoundedCornerShape(20.dp),
                    // Without a cap, a long message claims the outer Row's full width before the
                    // avatar sibling ever gets measured, pushing the avatar off-screen entirely —
                    // matches the same 280.dp cap broadcast rooms' equivalent bubble already uses.
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Text(
                        text = annotatedBody,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .pointerInput(annotatedBody) {
                                detectTapGestures(
                                    onLongPress = { showMenu = true },
                                    onDoubleTap = { onReply() },
                                    onTap = { offset ->
                                        val layout = textLayoutResult ?: return@detectTapGestures
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        annotatedBody.getStringAnnotations("URL", charOffset, charOffset)
                                            .firstOrNull()?.let { uriHandler.openUri(it.item) }
                                    }
                                )
                            },
                        onTextLayout = { textLayoutResult = it },
                        color = if (isSent) Color.Black else Color.White
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier
                    .background(Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
            ) {
                DropdownMenuItem(
                    text = { Text("Copy Message", color = Color.White, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(displayBody ?: ""))
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy Transaction ID", color = Color.White, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Tag, null, tint = KaspaTeal) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.id))
                        showMenu = false
                    }
                )
                if (imageContent != null) {
                    DropdownMenuItem(
                        text = { Text("Save Photo", color = Color.White, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(Icons.Default.Download, null, tint = KaspaTeal) },
                        onClick = {
                            try {
                                val bytes = android.util.Base64.decode(ImageMessage.base64Payload(imageContent), android.util.Base64.DEFAULT)
                                onSavePhoto(bytes, "kachat_${message.id}.jpg")
                            } catch (e: Exception) {
                                android.util.Log.e("MessageBubble", "Could not decode photo for saving", e)
                            }
                            showMenu = false
                        }
                    )
                }
                if (ChatViewModel.shouldShowRetryOption(message)) {
                    DropdownMenuItem(
                        text = { Text("Retry Send", color = Color.White, fontWeight = FontWeight.SemiBold) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = KaspaTeal) },
                        onClick = {
                            onRetry()
                            showMenu = false
                        }
                    )
                }
            }
        }

        if (isSent) {
            Row(modifier = Modifier.padding(top = 4.dp)) {
                when (message.deliveryStatus) {
                    "failed" -> Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Failed to send",
                        tint = Color(0xFFFF3B30),
                        modifier = Modifier.size(12.dp)
                    )
                    "pending" -> Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Sending",
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                    else -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CD964),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        }
        if (isSent) {
            Spacer(Modifier.width(8.dp))
            ContactAvatar(imageUrl = myAvatarUrl, fallbackText = myAvatarFallback, size = 32.dp)
        }
        }
    }
}

/**
 * A voice message bubble — decodes the embedded base64 audio to a temp file once, then plays it
 * with [android.media.MediaPlayer] (which decodes WebM/Opus natively, no manual PCM handling
 * needed for playback). No waveform visualization — the [VoiceMessageContent] format doesn't
 * carry sample data, and re-decoding to PCM just to draw bars isn't worth the extra native-audio
 * surface area for a cosmetic detail; play/pause + duration covers the actual "does it work" bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioBubble(voiceContent: VoiceMessageContent, isSent: Boolean, onLongPress: () -> Unit, onDoubleClick: () -> Unit = {}) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var isReady by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    DisposableEffect(voiceContent.content) {
        var tempFile: java.io.File? = null
        try {
            val bytes = android.util.Base64.decode(VoiceMessage.base64Payload(voiceContent), android.util.Base64.DEFAULT)
            val file = java.io.File(context.cacheDir, "voice_playback_${System.nanoTime()}.webm")
            file.writeBytes(bytes)
            tempFile = file
            val player = android.media.MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                durationMs = it.duration
                isReady = true
            }
            player.setOnCompletionListener { isPlaying = false }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            android.util.Log.e("AudioBubble", "Could not prepare voice message for playback", e)
        }
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            tempFile?.delete()
        }
    }

    Surface(
        color = if (isSent) Color(0xFF2C2C2E) else Color(0xFF1C1C1E),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(min = 150.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = isReady,
                onClick = {
                    val player = mediaPlayer ?: return@IconButton
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        if (player.currentPosition >= player.duration) player.seekTo(0)
                        player.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isReady) KaspaTeal else Color.Gray
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isReady) VoiceMessage.formatDuration(durationMs) else "...",
                color = Color.White,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Decodes an incoming photo's raw bytes, trying [ImageDecoder] as a fallback if [BitmapFactory]
 * returns null. Both platforms only ever send plain JPEG chat photos now (see
 * `ImagePrep.prepareForChatMessage`'s doc comment for why AVIF was tried and removed), so this is
 * mostly future-proofing/defense-in-depth for any other format that could reach this decode path -
 * `ImageDecoder` has occasionally succeeded where `BitmapFactory` fails on the same bytes on some
 * OEM builds.
 */
private fun decodeIncomingPhotoBitmap(bytes: ByteArray): android.graphics.Bitmap? {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return try {
        val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        android.graphics.ImageDecoder.decodeBitmap(source)
    } catch (e: Exception) {
        null
    }
}

/**
 * A photo message bubble — decodes the embedded base64 image to a [Bitmap] once, renders it inline
 * capped to a max width, and opens a tap-to-dismiss full-screen viewer on tap. Same interaction
 * contract as [AudioBubble] (long-press for the context menu, double-click to reply).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageBubble(
    imageContent: VoiceMessageContent,
    isSent: Boolean,
    onLongPress: () -> Unit,
    onDoubleClick: () -> Unit = {},
    photosBlocked: Boolean = false,
    senderDisplayName: String = "",
    isRevealed: Boolean = false,
    onReveal: () -> Unit = {}
) {
    // Hidden behind a manual reveal - mirrors iOS's LazyImageBubble.hiddenBubble. Skips decoding
    // the bitmap entirely until revealed, matching the "don't auto-render" intent, not just
    // "don't show" - see ChatRepository.shouldAutoDisplayPhotos.
    if (photosBlocked && !isRevealed) {
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 220.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).widthIn(min = 180.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.VisibilityOff, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "$senderDisplayName sent a photo",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onReveal,
                    colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("Show Photo", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    var showFullScreen by remember { mutableStateOf(false) }
    val bitmap = remember(imageContent.content) {
        try {
            val bytes = android.util.Base64.decode(ImageMessage.base64Payload(imageContent), android.util.Base64.DEFAULT)
            decodeIncomingPhotoBitmap(bytes)
        } catch (e: Exception) {
            android.util.Log.e("ImageBubble", "Could not decode photo message", e)
            null
        }
    }

    if (bitmap == null) {
        Surface(
            color = if (isSent) Color(0xFF2C2C2E) else Color(0xFF1C1C1E),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
        ) {
            Text(
                text = "📷 Photo unavailable",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = if (isSent) Color.Black else Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .widthIn(max = 220.dp)
            .combinedClickable(onClick = { showFullScreen = true }, onLongClick = onLongPress, onDoubleClick = onDoubleClick)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Photo message",
            modifier = Modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.FillWidth
        )
    }

    if (showFullScreen) {
        Dialog(onDismissRequest = { showFullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showFullScreen = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo message, full screen",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
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

/** "0:07" — the composer's live recording timer, capped display-wise the same way the recording itself is capped at 10s. */
fun formatRecordingElapsed(elapsedMs: Long): String = VoiceMessage.formatDuration(elapsedMs.toInt())

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
    connectionViewModel: ConnectionViewModel = hiltViewModel()
) {
    val address by viewModel.address.collectAsState()
    val balance by viewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val scrollState = rememberScrollState()

    val spendingAddress by viewModel.spendingAddress.collectAsState()
    val spendingBalance by viewModel.spendingBalance.collectAsState()
    var showIdentityQr by remember { mutableStateOf(false) }
    var showSpendingQr by remember { mutableStateOf(false) }
    var showFundIdentityQr by remember { mutableStateOf(false) }
    // Deliberately separate from showSpendingQr — "Accept Kaspa As Payment" shows the same
    // address as the Spending Address section's own "Show QR Code" row, but with its own bigger
    // green border, so it can't be styled without also affecting that unrelated row.
    var showAcceptPaymentQr by remember { mutableStateOf(false) }
    var showWithdrawDialog by remember { mutableStateOf(false) }

    val ownedDomainAssets by viewModel.ownedDomainAssets.collectAsState()
    val knsInscribeState by viewModel.knsInscribeState.collectAsState()
    val pendingKnsCommit by viewModel.pendingKnsCommit.collectAsState()

    val profileAssetId by viewModel.profileDomainAssetId.collectAsState()
    val knsProfile by viewModel.knsProfile.collectAsState()
    val activeProfileDomainName = viewModel.activeProfileDomainName.collectAsState().value
    val hasAnyProfileData = knsProfile != null && listOf(
        knsProfile?.bio, knsProfile?.x, knsProfile?.website, knsProfile?.telegram,
        knsProfile?.discord, knsProfile?.contactEmail, knsProfile?.github, knsProfile?.redirectUrl
    ).any { !it.isNullOrBlank() }

    LaunchedEffect(Unit) {
        viewModel.refreshBalance()
        viewModel.refreshOwnedDomains()
        viewModel.refreshSpendingAddress()
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color.Black)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { navController.navigate("connection_status") },
                    dotColorHex = dotColorHex,
                    showAddButton = false
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (pendingKnsCommit != null) {
                SettingsSection(title = "Unfinished Inscription") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "A KNS inscription's commit transaction went through, but the reveal never finished. Retry now to complete it — the funds are safely tied up until you do.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.retryPendingKnsReveal() },
                            colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                            enabled = knsInscribeState.status != WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL
                        ) {
                            Text("Retry Inscription Reveal", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SettingsSection(title = "KNS Profile") {
                if (profileAssetId == null || activeProfileDomainName == null) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Register a domain first — a profile attaches to a domain.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.clickable { navController.navigate("edit_kns_profile") }
                    ) {
                        val bannerUrl = knsProfile?.bannerUrl?.takeIf { it.isNotBlank() }
                        if (bannerUrl != null) {
                            SubcomposeAsyncImage(
                                model = bannerUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                    .background(Color(0xFF1C1C1E))
                            )
                        }
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(
                                imageUrl = knsProfile?.avatarUrl,
                                fallbackText = activeProfileDomainName,
                                size = 48.dp
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(activeProfileDomainName, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                val bio = knsProfile?.bio?.takeIf { it.isNotBlank() }
                                Text(
                                    bio ?: if (hasAnyProfileData) "On-chain profile data available." else "No on-chain profile data yet.",
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                        val knsProfileSnapshot = knsProfile
                        val hasMoreInfo = knsProfileSnapshot != null && listOf(
                            knsProfileSnapshot.bio, knsProfileSnapshot.x, knsProfileSnapshot.website, knsProfileSnapshot.telegram,
                            knsProfileSnapshot.discord, knsProfileSnapshot.contactEmail, knsProfileSnapshot.github
                        ).any { !it.isNullOrBlank() }
                        if (hasMoreInfo) {
                            HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                        }
                        var moreInfoExpanded by remember { mutableStateOf(false) }
                        if (hasMoreInfo) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { moreInfoExpanded = !moreInfoExpanded }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("More Info", color = KaspaTeal, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(
                                    if (moreInfoExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (moreInfoExpanded) "Collapse" else "Expand",
                                    tint = KaspaTeal
                                )
                            }
                        }
                        if (moreInfoExpanded) {
                            HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                            val context = LocalContext.current
                            val clipboardManager = LocalClipboardManager.current
                            Column(modifier = Modifier.padding(16.dp)) {
                                knsProfile?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                                    Text(
                                        text = bio,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.clickable { clipboardManager.setText(AnnotatedString(bio)) }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                                    Spacer(Modifier.height(12.dp))
                                }

                                val socialLinks = listOfNotNull(
                                    knsProfile?.x?.takeIf { it.isNotBlank() }?.let { "X" to it },
                                    knsProfile?.website?.takeIf { it.isNotBlank() }?.let { "Website" to it },
                                    knsProfile?.telegram?.takeIf { it.isNotBlank() }?.let { "Telegram" to it },
                                    knsProfile?.discord?.takeIf { it.isNotBlank() }?.let { "Discord" to it },
                                    knsProfile?.contactEmail?.takeIf { it.isNotBlank() }?.let { "Email" to it },
                                    knsProfile?.github?.takeIf { it.isNotBlank() }?.let { "GitHub" to it }
                                )
                                socialLinks.forEachIndexed { index, (label, value) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val url = if (value.startsWith("http")) value else "https://$value"
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                } catch (e: Exception) { /* no browser available */ }
                                            }
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                                        Text(value, color = KaspaTeal, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (index < socialLinks.lastIndex) {
                                        HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable { navController.navigate("kns_domains") }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "KNS Domains (${ownedDomainAssets.size})",
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            }

            CollapsibleAddressSection(title = "Identity Address", balance = balance) {
                val clipboardManager = LocalClipboardManager.current
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                address?.let { clipboardManager.setText(AnnotatedString(it)) }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Address", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showIdentityQr = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.QrCode, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Show QR Code", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showWithdrawDialog = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Withdraw Kaspa", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Separate from the identity address above, purely for payment privacy — "Pay in
            // Kaspa" sends always come out of this address, never the identity one above. It
            // rotates to a freshly derived address after every send (see WalletManager's
            // spending-address doc comment), so this always shows whichever one is current.
            CollapsibleAddressSection(title = "Spending Address", balance = spendingBalance) {
                val clipboardManager = LocalClipboardManager.current
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                spendingAddress?.let { clipboardManager.setText(AnnotatedString(it)) }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Address", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSpendingQr = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.QrCode, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Show QR Code", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("manage_addresses") },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ManageAccounts, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Manage Addresses", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable { navController.navigate("cold_storage") }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.QrCodeScanner, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Cold Storage Devices",
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            }

            run {
                val clipboardManager = LocalClipboardManager.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable {
                            spendingAddress?.let { clipboardManager.setText(AnnotatedString(it)) }
                            showAcceptPaymentQr = true
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QrCode, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Accept Kaspa As Payment",
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable {
                            address?.let { clipboardManager.setText(AnnotatedString(it)) }
                            showFundIdentityQr = true
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QrCode, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Fund Identity Address For Chatting",
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SettingsSection(title = "Info") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Created", color = Color.White)
                    Text("Apr 22, 2026 at 8:33 AM", color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }

        if (showIdentityQr) {
            QrCodeOverlay(value = address ?: "", onDismiss = { showIdentityQr = false })
        }
        if (showSpendingQr) {
            QrCodeOverlay(value = spendingAddress ?: "", onDismiss = { showSpendingQr = false })
        }
        if (showFundIdentityQr) {
            QrCodeOverlay(
                value = address ?: "",
                onDismiss = { showFundIdentityQr = false },
                message = "Just send 5-10 KAS at a time, that's plenty to cover chat fees for a while (about 500 messages per KAS)",
                borderColor = Color(0xFF4CD964),
                borderWidth = 4.dp
            )
        }
        if (showAcceptPaymentQr) {
            QrCodeOverlay(
                value = spendingAddress ?: "",
                onDismiss = { showAcceptPaymentQr = false },
                message = "Only accept Kaspa you intend to use as money.",
                borderColor = Color(0xFF4CD964),
                borderWidth = 4.dp
            )
        }
        }
    }

    if (showWithdrawDialog) {
        var recipientInput by remember { mutableStateOf("") }
        var amountInput by remember { mutableStateOf("") }
        val isSending by viewModel.isSending.collectAsState()
        val sendResult by viewModel.sendResult.collectAsState()
        val identityBalanceSompi by viewModel.balanceSompi.collectAsState()
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current

        LaunchedEffect(sendResult) {
            val result = sendResult ?: return@LaunchedEffect
            if (result.isSuccess) {
                Toast.makeText(context, "Withdrawal sent", Toast.LENGTH_SHORT).show()
                showWithdrawDialog = false
            } else {
                Toast.makeText(context, result.exceptionOrNull()?.message ?: "Withdrawal failed", Toast.LENGTH_SHORT).show()
            }
            viewModel.clearSendResult()
        }

        val amountSompi = amountInput.toDoubleOrNull()?.let { Math.round(it * 100_000_000.0) }
        val isValidAddress = remember(recipientInput) { KaspaAddress.isValid(recipientInput) }

        AlertDialog(
            onDismissRequest = { if (!isSending) showWithdrawDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Withdraw Kaspa", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Sends KAS out of your identity address — the one you fund for chatting.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = recipientInput,
                        onValueChange = { recipientInput = it },
                        label = { Text("Recipient address") },
                        singleLine = true,
                        enabled = !isSending,
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
                    TextButton(
                        onClick = { clipboardManager.getText()?.text?.let { recipientInput = it.trim() } },
                        enabled = !isSending
                    ) {
                        Text("Paste from Clipboard", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Amount (KAS)") },
                        singleLine = true,
                        enabled = !isSending,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val mass = com.kachat.app.util.KaspaMass.calculateMass(
                                        numInputs = 1,
                                        outputScriptLens = listOf(34, 34),
                                        payloadSize = 0
                                    )
                                    val fee = com.kachat.app.util.KaspaMass.calculateFee(mass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                                    val maxSompi = (identityBalanceSompi - fee).coerceAtLeast(0L)
                                    amountInput = "%.8f".format(java.util.Locale.US, maxSompi / 100_000_000.0)
                                },
                                enabled = !isSending
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
                    Spacer(Modifier.height(8.dp))
                    Text("Available: $balance", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    if (isSending) {
                        Spacer(Modifier.height(12.dp))
                        InscribeProgressRow("Sending...")
                    }
                }
            },
            confirmButton = {
                val canSend = !isSending && isValidAddress && (amountSompi ?: 0) > 0
                TextButton(
                    onClick = { amountSompi?.let { viewModel.onSendClicked(recipientInput.trim(), it) } },
                    enabled = canSend
                ) {
                    Text("Withdraw", color = if (canSend) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isSending) {
                    TextButton(onClick = { showWithdrawDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            }
        )
    }
}

/**
 * Dedicated KNS domain-management screen — the owned-domain list (star to mark primary, swap
 * icon to transfer), plus inscribing a new domain. Used to live inline as a collapsible section
 * on [ProfileScreen] itself; broken out once the list plus its two dialogs (inscribe/transfer)
 * made that screen too crowded to scan at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnsDomainsScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    val ownedDomainAssets by viewModel.ownedDomainAssets.collectAsState()
    val primaryDomainName by viewModel.primaryDomainName.collectAsState()
    val setPrimaryState by viewModel.setPrimaryState.collectAsState()
    val domainPreview by viewModel.domainPreview.collectAsState()
    val knsInscribeState by viewModel.knsInscribeState.collectAsState()
    var showInscribeDialog by remember { mutableStateOf(false) }
    var domainLabelInput by remember { mutableStateOf("") }
    var transferDialogDomain by remember { mutableStateOf<com.kachat.app.services.KnsAsset?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshOwnedDomains()
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("KNS Domains", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    domainLabelInput = ""
                    viewModel.clearDomainPreview()
                    viewModel.resetKnsInscribeState()
                    showInscribeDialog = true
                },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = CircleShape
            ) {
                Icon(Icons.Default.AddCircleOutline, "Inscribe new domain")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))
            if (ownedDomainAssets.isEmpty()) {
                Text(text = "No domains yet.", color = Color.Gray, modifier = Modifier.padding(16.dp))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C1C1E))
                        .padding(16.dp)
                ) {
                    ownedDomainAssets.forEachIndexed { index, domainAsset ->
                        val name = domainAsset.asset ?: return@forEachIndexed
                        val assetId = domainAsset.assetId
                        val isPrimary = name == primaryDomainName
                        val settingThisOne = setPrimaryState.inFlight && setPrimaryState.assetId == assetId
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            if (settingThisOne) {
                                CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (isPrimary) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = if (isPrimary) "Primary domain" else "Set as primary",
                                    tint = if (isPrimary) KaspaTeal else Color.Gray,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .then(
                                            if (!isPrimary && assetId != null) Modifier.clickable { viewModel.setPrimaryDomain(assetId) }
                                            else Modifier
                                        )
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (assetId != null) {
                                Icon(
                                    Icons.Default.SwapHoriz,
                                    contentDescription = "Transfer domain",
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            viewModel.resetTransferDomainState()
                                            transferDialogDomain = domainAsset
                                        }
                                )
                            }
                        }
                        val setPrimaryError = setPrimaryState.errorMessage
                        if (setPrimaryState.assetId == assetId && setPrimaryError != null) {
                            Text(setPrimaryError, color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                        }
                        if (index < ownedDomainAssets.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    if (showInscribeDialog) {
        val inFlight = knsInscribeState.status !in listOf(
            WalletViewModel.KnsInscribeUiStatus.IDLE,
            WalletViewModel.KnsInscribeUiStatus.SUCCESS,
            WalletViewModel.KnsInscribeUiStatus.FAILED
        )
        AlertDialog(
            onDismissRequest = { if (!inFlight) showInscribeDialog = false },
            title = {
                Text(
                    when (knsInscribeState.status) {
                        WalletViewModel.KnsInscribeUiStatus.SUCCESS -> "Domain Registered"
                        WalletViewModel.KnsInscribeUiStatus.FAILED -> "Inscription Failed"
                        else -> "Inscribe New Domain"
                    },
                    color = Color.White
                )
            },
            containerColor = Color(0xFF1C1C1E),
            text = {
                Column {
                    when (knsInscribeState.status) {
                        WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                            OutlinedTextField(
                                value = domainLabelInput,
                                onValueChange = {
                                    domainLabelInput = it
                                    viewModel.checkDomainLabel(it)
                                },
                                label = { Text("Domain name") },
                                suffix = { Text(".kas") },
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
                            Spacer(Modifier.height(12.dp))
                            domainPreview?.let { preview ->
                                when {
                                    preview.checking -> Text("Checking availability...", color = Color.Gray)
                                    preview.errorMessage != null -> Text(preview.errorMessage, color = Color(0xFFFF3B30))
                                    preview.available == false -> Text("${preview.label}.kas is not available", color = Color(0xFFFF3B30))
                                    preview.available == true && preview.isReserved -> {
                                        Text("${preview.label}.kas is available", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                                        Text("Reserved domain — no registration fee, only network fees apply.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }
                                    preview.available == true -> {
                                        Text("${preview.label}.kas is available", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(8.dp))
                                        val revealKas = preview.revealKas ?: 0.0
                                        val commitKas = preview.commitKas ?: 0.0
                                        Text(
                                            "Registration fee: ${"%.2f".format(revealKas)} KAS",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "You'll send ~${"%.2f".format(commitKas)} KAS total; ~${"%.2f".format((commitKas - revealKas).coerceAtLeast(0.0))} KAS comes back as change, the rest covers the fee and network costs.",
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        WalletViewModel.KnsInscribeUiStatus.CHECKING_AVAILABILITY -> InscribeProgressRow("Checking availability...")
                        WalletViewModel.KnsInscribeUiStatus.FETCHING_FEE -> InscribeProgressRow("Calculating fee...")
                        WalletViewModel.KnsInscribeUiStatus.SUBMITTING_COMMIT -> InscribeProgressRow("Submitting commit transaction...")
                        WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL -> InscribeProgressRow("Submitting reveal transaction...")
                        WalletViewModel.KnsInscribeUiStatus.VERIFYING -> InscribeProgressRow("Verifying on-chain (this can take a minute)...")
                        WalletViewModel.KnsInscribeUiStatus.SUCCESS -> {
                            val result = knsInscribeState.result
                            Text("${result?.domain} is now yours.", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Commit tx: ${result?.commitTxId}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            Text("Reveal tx: ${result?.revealTxId}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            if (result?.verified == false) {
                                Spacer(Modifier.height(8.dp))
                                Text("Still indexing — it'll show up above shortly.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                            Text(knsInscribeState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30))
                        }
                    }
                }
            },
            confirmButton = {
                when (knsInscribeState.status) {
                    WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                        val preview = domainPreview
                        TextButton(
                            onClick = { viewModel.inscribeDomain(preview?.label ?: domainLabelInput) },
                            enabled = preview?.available == true
                        ) {
                            val costLabel = preview?.commitKas?.let { " (pay ~${"%.2f".format(it)} KAS)" } ?: ""
                            Text("Inscribe$costLabel", color = if (preview?.available == true) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                    WalletViewModel.KnsInscribeUiStatus.SUCCESS, WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                        TextButton(onClick = { showInscribeDialog = false }) {
                            Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                if (!inFlight && knsInscribeState.status == WalletViewModel.KnsInscribeUiStatus.IDLE) {
                    TextButton(onClick = { showInscribeDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            }
        )
    }

    transferDialogDomain?.let { domain ->
        val recipientPreview by viewModel.transferRecipientPreview.collectAsState()
        val transferState by viewModel.transferDomainState.collectAsState()
        var recipientInput by remember(domain) { mutableStateOf("") }
        var confirmStep by remember(domain) { mutableStateOf(false) }
        val inFlight = transferState.status !in listOf(
            WalletViewModel.KnsInscribeUiStatus.IDLE,
            WalletViewModel.KnsInscribeUiStatus.SUCCESS,
            WalletViewModel.KnsInscribeUiStatus.FAILED
        )
        val domainName = domain.asset ?: ""
        val assetId = domain.assetId ?: ""

        AlertDialog(
            onDismissRequest = { if (!inFlight) transferDialogDomain = null },
            title = {
                Text(
                    when {
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.SUCCESS -> "Domain Transferred"
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.FAILED -> "Transfer Failed"
                        confirmStep -> "Confirm Transfer"
                        else -> "Transfer $domainName"
                    },
                    color = Color.White
                )
            },
            containerColor = Color(0xFF1C1C1E),
            text = {
                Column {
                    when {
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.SUBMITTING_COMMIT -> InscribeProgressRow("Submitting commit transaction...")
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.SUBMITTING_REVEAL -> InscribeProgressRow("Submitting reveal transaction...")
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.VERIFYING -> InscribeProgressRow("Verifying new ownership on-chain (this can take a minute)...")
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.SUCCESS -> {
                            val result = transferState.result
                            Text("$domainName now belongs to ${result?.toAddress}.", color = Color(0xFF4CD964), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Commit tx: ${result?.commitTxId}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            Text("Reveal tx: ${result?.revealTxId}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            if (result?.verified == false) {
                                Spacer(Modifier.height(8.dp))
                                Text("Still indexing — ownership will update shortly.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        transferState.status == WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                            Text(transferState.errorMessage ?: "Something went wrong", color = Color(0xFFFF3B30))
                        }
                        confirmStep -> {
                            Text("This will permanently transfer ownership of $domainName to:", color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                recipientPreview?.resolvedAddress ?: "",
                                color = KaspaTeal,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "This action cannot be undone. Double-check the address above before confirming.",
                                color = Color(0xFFFF3B30),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        else -> {
                            Text("Inscription: $assetId", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = recipientInput,
                                onValueChange = {
                                    recipientInput = it
                                    viewModel.checkTransferRecipient(it)
                                },
                                label = { Text("Recipient address or .kas name") },
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
                            Spacer(Modifier.height(8.dp))
                            recipientPreview?.let { preview ->
                                when {
                                    preview.checking -> Text("Resolving...", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    preview.errorMessage != null -> Text(preview.errorMessage, color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodySmall)
                                    preview.resolvedAddress != null -> Text("Resolves to: ${preview.resolvedAddress}", color = Color(0xFF4CD964), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    transferState.status == WalletViewModel.KnsInscribeUiStatus.SUCCESS || transferState.status == WalletViewModel.KnsInscribeUiStatus.FAILED -> {
                        TextButton(onClick = { transferDialogDomain = null }) {
                            Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                    confirmStep -> {
                        TextButton(onClick = { viewModel.transferDomain(domainName, assetId) }) {
                            Text("Confirm Transfer", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        }
                    }
                    transferState.status == WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                        TextButton(
                            onClick = { confirmStep = true },
                            enabled = recipientPreview?.resolvedAddress != null
                        ) {
                            Text("Next", color = if (recipientPreview?.resolvedAddress != null) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                when {
                    confirmStep && !inFlight -> {
                        TextButton(onClick = { confirmStep = false }) {
                            Text("Back", color = Color.Gray)
                        }
                    }
                    !inFlight && transferState.status == WalletViewModel.KnsInscribeUiStatus.IDLE -> {
                        TextButton(onClick = { transferDialogDomain = null }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    }
                }
            }
        )
    }
}

/**
 * Every spending-chain address derived so far, plus the identity address shown first (grayed
 * out, tapping it warns rather than lets you copy it) — since paying the identity address
 * instead of the current spending address defeats the whole point of keeping them separate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAddressesScreen(viewModel: WalletViewModel, onBack: () -> Unit) {
    val identityAddress by viewModel.address.collectAsState()
    val addresses by viewModel.manageAddresses.collectAsState()
    val loading by viewModel.manageAddressesLoading.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showIdentityWarning by remember { mutableStateOf(false) }
    var activateIndex by remember { mutableStateOf<Int?>(null) }
    var qrAddress by remember { mutableStateOf<String?>(null) }
    var withdrawEntry by remember { mutableStateOf<com.kachat.app.services.WalletService.SpendingAddressEntry?>(null) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showConsolidateConfirm by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val consolidateState by viewModel.consolidateState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadManageAddresses()
    }

    LaunchedEffect(consolidateState.status) {
        when (consolidateState.status) {
            WalletViewModel.ConsolidateStatus.SUCCESS -> {
                val count = consolidateState.sweptCount
                Toast.makeText(
                    context,
                    if (count > 0) "Consolidated $count address${if (count == 1) "" else "es"}" else "Nothing to consolidate",
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.resetConsolidateState()
            }
            WalletViewModel.ConsolidateStatus.FAILED -> {
                Toast.makeText(context, consolidateState.errorMessage ?: "Consolidation failed", Toast.LENGTH_SHORT).show()
                viewModel.resetConsolidateState()
            }
            else -> {}
        }
    }

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            viewModel.loadManageAddresses()
        }
    }

    LaunchedEffect(loading) {
        if (!loading && pullRefreshState.isRefreshing) {
            pullRefreshState.endRefresh()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Manage Addresses", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showActionsMenu = true },
                    containerColor = KaspaTeal,
                    contentColor = Color.Black,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, "Address actions")
                }
                DropdownMenu(expanded = showActionsMenu, onDismissRequest = { showActionsMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Generate New Spending Address") },
                        leadingIcon = { Icon(Icons.Default.AddCircleOutline, null) },
                        onClick = {
                            showActionsMenu = false
                            viewModel.generateNewSpendingAddress()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Discover Addresses") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = {
                            showActionsMenu = false
                            viewModel.discoverSpendingAddresses { count ->
                                Toast.makeText(
                                    context,
                                    if (count > 0) "Found $count used address${if (count == 1) "" else "es"}" else "No additional used addresses found",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Send All Kaspa To Primary Spend Address") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallMerge, null) },
                        onClick = {
                            showActionsMenu = false
                            showConsolidateConfirm = true
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(pullRefreshState.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { showIdentityWarning = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Identity Address", color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text(
                            text = identityAddress ?: "Loading...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (loading && addresses.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = KaspaTeal)
                    }
                }
            } else {
                items(addresses.reversed()) { entry ->
                    val kas = entry.balanceSompi / 100_000_000.0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1C1C1E))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.address,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "%.8f KAS".format(java.util.Locale.US, kas),
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "  ·  " + if (entry.everUsed) "Used" else "Unused",
                                    color = if (entry.everUsed) Color(0xFFF39C12) else Color(0xFF4CD964),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(entry.address)) }) {
                            Icon(Icons.Default.ContentCopy, "Copy address", tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { qrAddress = entry.address }) {
                            Icon(Icons.Default.QrCode, "Show QR code", tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { if (!entry.isCurrent) activateIndex = entry.index }) {
                            Icon(
                                if (entry.isCurrent) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (entry.isCurrent) "Currently active for Pay in Kaspa" else "Make active for Pay in Kaspa",
                                tint = if (entry.isCurrent) KaspaTeal else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { if (entry.balanceSompi > 0) withdrawEntry = entry },
                            enabled = entry.balanceSompi > 0
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Withdraw from this address",
                                tint = if (entry.balanceSompi > 0) KaspaTeal else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
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

        qrAddress?.let { address ->
            QrCodeOverlay(value = address, onDismiss = { qrAddress = null })
        }
        }
    }

    if (showIdentityWarning) {
        AlertDialog(
            onDismissRequest = { showIdentityWarning = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Identity Address", color = Color.White) },
            text = {
                Text(
                    "Never send Kaspa you intend to spend to this address.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = { showIdentityWarning = false }) {
                    Text("OK", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    activateIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { activateIndex = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Make Active Address", color = Color.White) },
            text = {
                Text(
                    "Spending Kaspa on KaChat will come out of this address only now. Any Kaspa still sitting on your current spending address will be sent to this new one automatically.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setActiveSpendingAddress(index)
                    activateIndex = null
                }) {
                    Text("Confirm", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activateIndex = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showConsolidateConfirm) {
        val consolidating = consolidateState.status == WalletViewModel.ConsolidateStatus.RUNNING
        AlertDialog(
            onDismissRequest = { if (!consolidating) showConsolidateConfirm = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Send All Kaspa To Primary Spend Address", color = Color.White) },
            text = {
                Text(
                    "Sends every other spending address's balance to your currently starred one. Each address with a balance is its own real transaction, so this may take a few moments.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !consolidating,
                    onClick = {
                        viewModel.consolidateSpendingAddresses()
                        showConsolidateConfirm = false
                    }
                ) {
                    Text("Confirm", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = !consolidating, onClick = { showConsolidateConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    withdrawEntry?.let { entry ->
        var recipientInput by remember(entry) { mutableStateOf("") }
        var amountInput by remember(entry) { mutableStateOf("") }
        val isSending by viewModel.isSending.collectAsState()
        val sendResult by viewModel.sendResult.collectAsState()

        LaunchedEffect(sendResult) {
            val result = sendResult ?: return@LaunchedEffect
            if (result.isSuccess) {
                Toast.makeText(context, "Withdrawal sent", Toast.LENGTH_SHORT).show()
                withdrawEntry = null
            } else {
                Toast.makeText(context, result.exceptionOrNull()?.message ?: "Withdrawal failed", Toast.LENGTH_SHORT).show()
            }
            viewModel.clearSendResult()
        }

        val amountSompi = amountInput.toDoubleOrNull()?.let { Math.round(it * 100_000_000.0) }
        val isValidAddress = remember(recipientInput) { KaspaAddress.isValid(recipientInput) }

        AlertDialog(
            onDismissRequest = { if (!isSending) withdrawEntry = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Withdraw From This Address", color = Color.White) },
            text = {
                Column {
                    Text(entry.address, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = recipientInput,
                        onValueChange = { recipientInput = it },
                        label = { Text("Recipient address") },
                        singleLine = true,
                        enabled = !isSending,
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
                    TextButton(
                        onClick = { clipboardManager.getText()?.text?.let { recipientInput = it.trim() } },
                        enabled = !isSending
                    ) {
                        Text("Paste from Clipboard", color = KaspaTeal, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Amount (KAS)") },
                        singleLine = true,
                        enabled = !isSending,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val mass = com.kachat.app.util.KaspaMass.calculateMass(
                                        numInputs = 1,
                                        outputScriptLens = listOf(34, 34),
                                        payloadSize = 0
                                    )
                                    val fee = com.kachat.app.util.KaspaMass.calculateFee(mass, com.kachat.app.util.KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
                                    val maxSompi = (entry.balanceSompi - fee).coerceAtLeast(0L)
                                    amountInput = "%.8f".format(java.util.Locale.US, maxSompi / 100_000_000.0)
                                },
                                enabled = !isSending
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
                    Spacer(Modifier.height(8.dp))
                    Text("Available: %.8f KAS".format(java.util.Locale.US, entry.balanceSompi / 100_000_000.0), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    if (isSending) {
                        Spacer(Modifier.height(12.dp))
                        InscribeProgressRow("Sending...")
                    }
                }
            },
            confirmButton = {
                val canSend = !isSending && isValidAddress && (amountSompi ?: 0) > 0
                TextButton(
                    onClick = { amountSompi?.let { viewModel.withdrawFromSpendingAddress(entry.index, recipientInput.trim(), it) } },
                    enabled = canSend
                ) {
                    Text("Withdraw", color = if (canSend) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                if (!isSending) {
                    TextButton(onClick = { withdrawEntry = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            }
        )
    }
}

@Composable
private fun InscribeProgressRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = KaspaTeal, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditKnsProfileScreen(viewModel: WalletViewModel, onBack: () -> Unit, onNavigateToDomains: () -> Unit = {}) {
    val knsProfile by viewModel.knsProfile.collectAsState()
    val activeProfileDomainName by viewModel.activeProfileDomainName.collectAsState()
    val profileAssetId by viewModel.profileDomainAssetId.collectAsState()
    val pendingAvatarUri by viewModel.pendingAvatarUri.collectAsState()
    val pendingBannerUri by viewModel.pendingBannerUri.collectAsState()
    val editState by viewModel.editProfileState.collectAsState()

    var bio by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var telegram by remember { mutableStateOf("") }
    var discord by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var github by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }

    LaunchedEffect(knsProfile) {
        if (!seeded) {
            bio = knsProfile?.bio ?: ""
            x = knsProfile?.x ?: ""
            website = knsProfile?.website ?: ""
            telegram = knsProfile?.telegram ?: ""
            discord = knsProfile?.discord ?: ""
            email = knsProfile?.contactEmail ?: ""
            github = knsProfile?.github ?: ""
            redirect = knsProfile?.redirectUrl ?: ""
            seeded = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetEditProfileState() }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) viewModel.setPendingAvatar(uri) }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) viewModel.setPendingBanner(uri) }

    var showSaveDialog by remember { mutableStateOf(false) }

    // What "Save" is actually about to submit — each entry becomes its own on-chain commit/reveal
    // transaction, so this doubles as both the confirm dialog's change list and its cost count.
    val pendingChanges = remember(bio, x, website, telegram, discord, email, github, redirect, pendingAvatarUri, pendingBannerUri, knsProfile) {
        buildList {
            if (pendingAvatarUri != null) add("Avatar")
            if (pendingBannerUri != null) add("Banner")
            if (bio.trim() != (knsProfile?.bio ?: "")) add("Bio")
            if (x.trim() != (knsProfile?.x ?: "")) add("X")
            if (website.trim() != (knsProfile?.website ?: "")) add("Website")
            if (telegram.trim() != (knsProfile?.telegram ?: "")) add("Telegram")
            if (discord.trim() != (knsProfile?.discord ?: "")) add("Discord")
            if (email.trim() != (knsProfile?.contactEmail ?: "")) add("Email")
            if (github.trim() != (knsProfile?.github ?: "")) add("GitHub")
            if (redirect.trim() != (knsProfile?.redirectUrl ?: "")) add("Redirect")
        }
    }

    val inFlight = editState.step !in listOf(
        WalletViewModel.EditProfileStep.IDLE,
        WalletViewModel.EditProfileStep.SUCCESS,
        WalletViewModel.EditProfileStep.PARTIAL_FAILURE,
        WalletViewModel.EditProfileStep.FAILED
    )

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Edit KNS Profile", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !inFlight) {
                        Text("Cancel", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { if (pendingChanges.isNotEmpty()) showSaveDialog = true },
                        enabled = !inFlight && pendingChanges.isNotEmpty()
                    ) {
                        Text("Save", color = if (!inFlight && pendingChanges.isNotEmpty()) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SettingsSection(title = "Domains") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToDomains() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(activeProfileDomainName ?: "—", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(profileAssetId ?: "", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
            }

            SettingsSection(title = "Avatar") {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContactAvatar(
                        imageUrl = pendingAvatarUri?.toString() ?: knsProfile?.avatarUrl,
                        fallbackText = activeProfileDomainName ?: "?",
                        size = 64.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { avatarPicker.launch("image/*") }, enabled = !inFlight) {
                            Text("Choose Avatar", color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                        if (pendingAvatarUri != null) {
                            TextButton(onClick = { viewModel.setPendingAvatar(null) }, enabled = !inFlight) {
                                Text("Remove", color = Color(0xFFFF3B30))
                            }
                        }
                    }
                }
            }

            SettingsSection(title = "Banner") {
                Column(modifier = Modifier.padding(16.dp)) {
                    val previewUrl = pendingBannerUri?.toString() ?: knsProfile?.bannerUrl
                    if (previewUrl != null) {
                        SubcomposeAsyncImage(
                            model = previewUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1C1C1E))
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { bannerPicker.launch("image/*") }, enabled = !inFlight) {
                            Text("Choose Banner", color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                        if (pendingBannerUri != null) {
                            TextButton(onClick = { viewModel.setPendingBanner(null) }, enabled = !inFlight) {
                                Text("Remove", color = Color(0xFFFF3B30))
                            }
                        }
                    }
                }
            }

            SettingsSection(title = "Profile") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditProfileTextField("Bio", bio, { bio = it }, enabled = !inFlight, singleLine = false)
                    EditProfileTextField("X", x, { x = it }, enabled = !inFlight)
                    EditProfileTextField("Website", website, { website = it }, enabled = !inFlight)
                    EditProfileTextField("Telegram", telegram, { telegram = it }, enabled = !inFlight)
                    EditProfileTextField("Discord", discord, { discord = it }, enabled = !inFlight)
                    EditProfileTextField("Email", email, { email = it }, enabled = !inFlight)
                    EditProfileTextField("GitHub", github, { github = it }, enabled = !inFlight)
                    EditProfileTextField("Redirect", redirect, { redirect = it }, enabled = !inFlight)
                }
            }

            Spacer(Modifier.height(60.dp))
        }
    }

    if (showSaveDialog) {
        val terminal = editState.step in listOf(
            WalletViewModel.EditProfileStep.SUCCESS,
            WalletViewModel.EditProfileStep.PARTIAL_FAILURE,
            WalletViewModel.EditProfileStep.FAILED
        )
        fun closeDialog() {
            showSaveDialog = false
            viewModel.resetEditProfileState()
        }
        AlertDialog(
            onDismissRequest = {
                when (editState.step) {
                    WalletViewModel.EditProfileStep.IDLE -> showSaveDialog = false
                    else -> if (terminal) closeDialog()
                }
            },
            containerColor = Color(0xFF1C1C1E),
            title = {
                Text(
                    when (editState.step) {
                        WalletViewModel.EditProfileStep.IDLE -> "Confirm Changes"
                        WalletViewModel.EditProfileStep.SUCCESS -> "Saved"
                        WalletViewModel.EditProfileStep.PARTIAL_FAILURE -> "Some Changes Failed"
                        WalletViewModel.EditProfileStep.FAILED -> "Save Failed"
                        else -> "Saving..."
                    },
                    color = Color.White
                )
            },
            text = {
                when (editState.step) {
                    WalletViewModel.EditProfileStep.IDLE -> Column {
                        Text(
                            "${pendingChanges.size} change${if (pendingChanges.size == 1) "" else "s"} — each is submitted as its own on-chain transaction from your spending address:",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        pendingChanges.forEach { Text("• $it", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Each transaction temporarily uses ~2 KAS; ~1 KAS returns immediately as change — only the small network fee is a real cost.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    WalletViewModel.EditProfileStep.UPLOADING_AVATAR,
                    WalletViewModel.EditProfileStep.UPLOADING_BANNER,
                    WalletViewModel.EditProfileStep.SUBMITTING_FIELD -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(color = KaspaTeal)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when (editState.step) {
                                WalletViewModel.EditProfileStep.UPLOADING_AVATAR -> "Uploading avatar..."
                                WalletViewModel.EditProfileStep.UPLOADING_BANNER -> "Uploading banner..."
                                else -> "Submitting ${editState.currentFieldLabel}..."
                            },
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    WalletViewModel.EditProfileStep.SUCCESS -> Text(
                        if (editState.fieldResults.isEmpty()) "Nothing to save." else "All changes saved.",
                        color = Color(0xFF4CD964),
                        fontWeight = FontWeight.Bold
                    )
                    WalletViewModel.EditProfileStep.PARTIAL_FAILURE -> Column {
                        Text("Some changes failed to save:", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        editState.fieldResults.filter { !it.success }.forEach {
                            Text("${it.fieldKey}: ${it.errorMessage ?: "failed"}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    WalletViewModel.EditProfileStep.FAILED -> Text(
                        editState.fieldResults.firstOrNull { !it.success }?.errorMessage ?: "Save failed",
                        color = Color(0xFFFF3B30)
                    )
                }
            },
            confirmButton = {
                when (editState.step) {
                    WalletViewModel.EditProfileStep.IDLE -> TextButton(
                        onClick = {
                            viewModel.saveKnsProfile(
                                mapOf(
                                    "bio" to bio,
                                    "x" to x,
                                    "website" to website,
                                    "telegram" to telegram,
                                    "discord" to discord,
                                    "contactEmail" to email,
                                    "github" to github,
                                    "redirectUrl" to redirect
                                )
                            )
                        }
                    ) {
                        Text("Confirm", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    WalletViewModel.EditProfileStep.SUCCESS,
                    WalletViewModel.EditProfileStep.PARTIAL_FAILURE,
                    WalletViewModel.EditProfileStep.FAILED -> TextButton(onClick = { closeDialog() }) {
                        Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }
                    else -> {}
                }
            },
            dismissButton = {
                if (editState.step == WalletViewModel.EditProfileStep.IDLE) {
                    TextButton(onClick = { showSaveDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            }
        )
    }
}

@Composable
private fun EditProfileTextField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = Color.Gray,
            focusedBorderColor = KaspaTeal,
            unfocusedBorderColor = Color.Gray,
            focusedLabelColor = KaspaTeal,
            unfocusedLabelColor = Color.Gray
        ),
        modifier = Modifier.fillMaxWidth()
    )
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

/** Unwraps a Compose [android.content.Context] (often a ContextWrapper) to find the real hosting Activity — needed for Credential Manager / Drive authorization, which require an Activity, not just any Context. */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val estimateFees by chatViewModel.estimateFeesEnabled.collectAsState()
    val hideAutoCreatedPaymentChats by chatViewModel.hideAutoCreatedPaymentChats.collectAsState()
    val showContactBalance by chatViewModel.showContactBalance.collectAsState()
    val requirePhotoApprovalForNewContacts by chatViewModel.requirePhotoApprovalForNewContacts.collectAsState()
    val notificationsEnabled by settingsViewModel.notificationsEnabled.collectAsState()
    val chatPhotoQualityPreset by chatViewModel.chatPhotoQualityPreset.collectAsState()
    val syncSystemContactsEnabled by chatViewModel.syncSystemContactsEnabled.collectAsState()
    val autoCreateSystemContactsEnabled by chatViewModel.autoCreateSystemContactsEnabled.collectAsState()
    val exportChatHistoryState by chatViewModel.exportState.collectAsState()
    val importChatHistoryState by chatViewModel.importState.collectAsState()
    val diagnosticsExportState by chatViewModel.diagnosticsExportState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val syncContactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val autoCreatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val importChatHistoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chatViewModel.importChatHistory(uri)
    }

    LaunchedEffect(Unit) {
        walletViewModel.refreshBalance()
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color.Black)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                TopStatusBar(
                    balance = balance,
                    onStatusClick = { navController.navigate("connection_status") },
                    onAddClick = { navController.navigate("create_chat") },
                    dotColorHex = dotColorHex
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
                SettingsSwitchItem("Hide auto-created payment chats", hideAutoCreatedPaymentChats) {
                    chatViewModel.updateHideAutoCreatedPaymentChats(it)
                }
                SettingsDivider()
                SettingsSwitchItem("Show contact balance", showContactBalance) {
                    chatViewModel.updateShowContactBalance(it)
                }
                SettingsDivider()
                SettingsSwitchItem("Require approval for photos from new contacts", requirePhotoApprovalForNewContacts) {
                    chatViewModel.updateRequirePhotoApprovalForNewContacts(it)
                }
                SettingsDivider()
                SettingsNavigationItem(
                    "Photo Quality",
                    Icons.Default.Photo,
                    chatPhotoQualityPreset.displayName,
                    onClick = { navController.navigate("photo_quality_settings") }
                )
                SettingsDivider()
                SettingsNavigationItem(
                    "Notifications",
                    Icons.Default.NotificationsNone,
                    if (notificationsEnabled) "On" else "Off",
                    onClick = { navController.navigate("notification_settings") }
                )
            }

            SettingsSection(title = "Contacts") {
                SettingsSwitchItem("Sync system contacts", syncSystemContactsEnabled) { enabled ->
                    chatViewModel.setSyncSystemContactsEnabled(enabled)
                    if (enabled) syncContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
                SettingsDivider()
                SettingsSwitchItem("Autocreate system contacts", autoCreateSystemContactsEnabled) { enabled ->
                    chatViewModel.setAutoCreateSystemContactsEnabled(enabled)
                    if (enabled) {
                        autoCreatePermissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                        )
                    }
                }
                SettingsFooter("Uses your device contacts to match and enrich Kaspa contacts.")
            }

            SettingsSection(title = "Storage") {
                val googleBackupEnabled by chatViewModel.googleBackupEnabled.collectAsState()
                val googleBackupOpState by chatViewModel.googleBackupOpState.collectAsState()
                val restoreState by chatViewModel.restoreState.collectAsState()
                val pendingConsentIntent by chatViewModel.pendingConsentIntent.collectAsState()
                val activity = LocalContext.current.findActivity()

                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    chatViewModel.consentIntentLaunched()
                    result.data?.let { chatViewModel.completeGoogleDriveAuthorization(it) }
                }

                LaunchedEffect(pendingConsentIntent) {
                    pendingConsentIntent?.let { pendingIntent ->
                        consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    }
                }

                val backupInFlight = googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.IN_PROGRESS
                val restoreInFlight = restoreState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

                SettingsSwitchItem(
                    "Back Up to Google Drive",
                    checked = googleBackupEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            activity?.let { chatViewModel.enableGoogleDriveBackup(it) }
                        } else {
                            chatViewModel.disableGoogleDriveBackup()
                        }
                    }
                )
                val backupFooterText = when {
                    backupInFlight -> "Working..."
                    googleBackupOpState.status == ChatViewModel.GoogleBackupOpStatus.FAILED -> googleBackupOpState.message ?: "Something went wrong"
                    googleBackupEnabled && googleBackupOpState.signedInEmail != null -> "Signed in as ${googleBackupOpState.signedInEmail}"
                    else -> "Off by default. Backs up chat history to your own Google Drive — hidden storage, not visible in your regular Drive files."
                }
                SettingsFooter(backupFooterText)

                // Local vs. cloud storage — mirrors iOS's "Local storage used"/"iCloud storage
                // used" split. Android has no live per-record cloud sync like CloudKit; Google
                // Drive backup is one flat JSON file per account, so "cloud" here is just that
                // one file's current size in Drive.
                SettingsDivider()
                val context = LocalContext.current
                val localStorageSizeBytes by chatViewModel.localStorageSizeBytes.collectAsState()
                LaunchedEffect(Unit) { chatViewModel.refreshLocalStorageSize() }
                SettingsInfoItem(
                    label = "Local storage used",
                    value = localStorageSizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                        ?: "Calculating..."
                )

                SettingsDivider()
                val driveBackupSizeState by chatViewModel.driveBackupSizeState.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Google Drive backup used", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = when (driveBackupSizeState.status) {
                                ChatViewModel.DriveSizeStatus.IDLE -> "Not checked"
                                ChatViewModel.DriveSizeStatus.LOADING -> "Checking..."
                                ChatViewModel.DriveSizeStatus.LOADED -> driveBackupSizeState.bytes?.let {
                                    android.text.format.Formatter.formatShortFileSize(context, it)
                                } ?: "No backup found"
                                ChatViewModel.DriveSizeStatus.FAILED -> "Unavailable"
                            },
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (driveBackupSizeState.status == ChatViewModel.DriveSizeStatus.LOADING) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = KaspaTeal, strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { chatViewModel.refreshDriveBackupSize() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Check Drive backup size", tint = KaspaTeal)
                        }
                    }
                }

                if (googleBackupEnabled) {
                    SettingsDivider()
                    SettingsActionItem(
                        label = if (backupInFlight) "Backing Up..." else "Back Up Now",
                        icon = Icons.Default.CloudUpload,
                        color = if (backupInFlight) Color.Gray else KaspaTeal
                    ) {
                        if (!backupInFlight) chatViewModel.backupNow()
                    }
                    SettingsDivider()
                    SettingsActionItem(
                        label = if (restoreInFlight) "Restoring..." else "Restore from Google Drive",
                        icon = Icons.Default.CloudDownload,
                        color = if (restoreInFlight) Color.Gray else KaspaTeal
                    ) {
                        if (!restoreInFlight) chatViewModel.restoreFromGoogleDrive()
                    }
                    if (restoreState.status == ChatViewModel.ChatHistoryOpStatus.SUCCESS) {
                        SettingsFooter(restoreState.message ?: "Restore complete.")
                    }
                    if (restoreState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                        SettingsFooter(restoreState.message ?: "Restore failed.")
                    }

                    SettingsDivider()

                    val backupRetention by chatViewModel.backupRetention.collectAsState()
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Retention", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(
                                Triple("Forever", BackupRetention.FOREVER, 0),
                                Triple("30 Days", BackupRetention.DAYS_30, 1),
                                Triple("90 Days", BackupRetention.DAYS_90, 2)
                            )
                            options.forEach { (label, value, index) ->
                                SegmentedButton(
                                    selected = backupRetention == value,
                                    onClick = { chatViewModel.setBackupRetention(value) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = Color(0xFF2C2C2E),
                                        activeContentColor = Color.White,
                                        inactiveContainerColor = Color(0xFF1C1C1E),
                                        inactiveContentColor = Color.Gray
                                    )
                                ) {
                                    Text(label, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (backupRetention == BackupRetention.FOREVER) {
                                "Chat history is kept forever and backed up as-is."
                            } else {
                                "Messages older than ${backupRetention.days} days are permanently deleted from this device — not just excluded from the backup. This cannot be undone."
                            },
                            color = if (backupRetention == BackupRetention.FOREVER) Color.Gray else Color(0xFFFF3B30),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            SettingsSection(title = "Chat History") {
                val exportInFlight = exportChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS
                val importInFlight = importChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

                SettingsActionItem(
                    label = if (exportInFlight) "Exporting..." else "Export Chat History",
                    icon = Icons.Default.FileUpload,
                    color = if (exportInFlight) Color.Gray else KaspaTeal
                ) {
                    if (!exportInFlight) {
                        chatViewModel.exportChatHistory { uri ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export Chat History"))
                        }
                    }
                }
                if (exportChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    Text(
                        exportChatHistoryState.message ?: "Export failed",
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                SettingsDivider()

                SettingsActionItem(
                    label = if (importInFlight) "Importing..." else "Import Chat History",
                    icon = Icons.Default.FileDownload,
                    color = if (importInFlight) Color.Gray else KaspaTeal
                ) {
                    if (!importInFlight) {
                        importChatHistoryLauncher.launch(arrayOf("application/json"))
                    }
                }
                if (importChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.SUCCESS) {
                    Text(
                        importChatHistoryState.message ?: "Import complete",
                        color = Color(0xFF4CD964),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                if (importChatHistoryState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    Text(
                        importChatHistoryState.message ?: "Import failed",
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                SettingsFooter("Exports a plaintext JSON file of your decrypted chat history for this account — not encrypted, so only share it somewhere you trust. Importing merges into your existing history without overwriting anything.")
            }

            SettingsSection(title = "Connection") {
                SettingsNavigationItem("Connection Settings", Icons.Default.Language, "Mainnet", onClick = {
                    navController.navigate("connection_settings")
                })
            }

            SettingsSection(title = "About") {
                SettingsInfoItem("Version", com.kachat.app.BuildConfig.VERSION_NAME)
                SettingsDivider()
                SettingsInfoItem(
                    "Website",
                    "https://linktr.ee/Kachat_",
                    KaspaTeal,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://linktr.ee/Kachat_")))
                        } catch (e: Exception) { /* no browser available */ }
                    }
                )
                SettingsDivider()
                SettingsInfoItem(
                    "Support Email",
                    "kaspasilver@gmail.com",
                    KaspaTeal,
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:kaspasilver@gmail.com")))
                        } catch (e: Exception) { /* no email app available */ }
                    }
                )
                SettingsDivider()
                SettingsInfoItem(
                    "Donate",
                    ChatViewModel.DONATION_KNS_DOMAIN,
                    KaspaTeal,
                    onClick = {
                        chatViewModel.startDonationChat(
                            onResolved = { address -> navController.navigate("chat/$address?paymentMode=true") },
                            onError = {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Couldn't reach ${ChatViewModel.DONATION_KNS_DOMAIN} right now — try again later")
                                }
                            }
                        )
                    }
                )
            }

            SettingsSection(title = "Diagnostics") {
                val diagnosticsExportInFlight = diagnosticsExportState.status == ChatViewModel.ChatHistoryOpStatus.IN_PROGRESS

                SettingsActionItem(
                    label = if (diagnosticsExportInFlight) "Exporting..." else "Export Diagnostics Archive",
                    icon = Icons.Default.BugReport,
                    color = if (diagnosticsExportInFlight) Color.Gray else KaspaTeal
                ) {
                    if (!diagnosticsExportInFlight) {
                        chatViewModel.exportDiagnostics { uri ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export Diagnostics Archive"))
                        }
                    }
                }
                if (diagnosticsExportState.status == ChatViewModel.ChatHistoryOpStatus.FAILED) {
                    Text(
                        diagnosticsExportState.message ?: "Export failed",
                        color = Color(0xFFFF3B30),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                SettingsFooter("Exports app/device info, connection settings, local message counts, and recent app logs as a zip — for troubleshooting with support. No private keys, seed phrases, or decrypted message content are included.")
            }

            SettingsSection(title = "Actions") {
                SettingsActionItem("View Seed Phrase", Icons.Default.Key, KaspaTeal) {
                    navController.navigate("seed_phrase")
                }
                SettingsDivider()
                SettingsActionItem("Log Out", Icons.AutoMirrored.Filled.Logout, Color.Red) {
                    walletViewModel.logout()
                }
            }

            SettingsSection(title = "Danger Zone") {
                val activeAddress by walletViewModel.address.collectAsState()
                val wipeIncomingState by chatViewModel.wipeIncomingState.collectAsState()
                val wipeAccountState by chatViewModel.wipeAccountState.collectAsState()
                var showWipeIncomingConfirm by remember { mutableStateOf(false) }
                var showWipeAccountConfirm by remember { mutableStateOf(false) }
                var showWipeAccountCloudConfirm by remember { mutableStateOf(false) }

                val wipeIncomingInFlight = wipeIncomingState.status == ChatViewModel.DangerZoneOpStatus.IN_PROGRESS
                val wipeAccountInFlight = wipeAccountState.status == ChatViewModel.DangerZoneOpStatus.IN_PROGRESS

                SettingsActionItem(
                    label = if (wipeIncomingInFlight) "Wiping..." else "Wipe and re-sync incoming messages",
                    icon = Icons.Default.Cached,
                    color = if (wipeIncomingInFlight) Color.Gray else Color.Red
                ) {
                    if (!wipeIncomingInFlight) showWipeIncomingConfirm = true
                }
                if (wipeIncomingState.status == ChatViewModel.DangerZoneOpStatus.SUCCESS || wipeIncomingState.status == ChatViewModel.DangerZoneOpStatus.FAILED) {
                    SettingsFooter(wipeIncomingState.message ?: "Done")
                }
                SettingsDivider()
                SettingsActionItem(
                    label = if (wipeAccountInFlight) "Wiping..." else "Wipe account & messages",
                    icon = Icons.Default.PersonRemoveAlt1,
                    color = if (wipeAccountInFlight) Color.Gray else Color.Red
                ) {
                    if (!wipeAccountInFlight && activeAddress != null) showWipeAccountConfirm = true
                }
                SettingsDivider()
                SettingsActionItem(
                    label = if (wipeAccountInFlight) "Wiping..." else "Wipe account & messages & Cloud",
                    icon = Icons.Default.CloudOff,
                    color = if (wipeAccountInFlight) Color.Gray else Color.Red
                ) {
                    if (!wipeAccountInFlight && activeAddress != null) showWipeAccountCloudConfirm = true
                }
                if (wipeAccountState.status == ChatViewModel.DangerZoneOpStatus.FAILED) {
                    SettingsFooter(wipeAccountState.message ?: "Failed")
                }

                if (showWipeIncomingConfirm) {
                    AlertDialog(
                        onDismissRequest = { showWipeIncomingConfirm = false },
                        containerColor = Color(0xFF1C1C1E),
                        title = { Text("Wipe and re-sync incoming messages", color = Color.White) },
                        text = {
                            Text(
                                "This removes all incoming messages locally, then re-syncs them from the blockchain. Your account info and sent messages are preserved.",
                                color = Color.Gray
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showWipeIncomingConfirm = false
                                chatViewModel.wipeIncomingMessages()
                            }) {
                                Text("Wipe Incoming Messages", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWipeIncomingConfirm = false }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }

                if (showWipeAccountConfirm) {
                    val address = activeAddress
                    AlertDialog(
                        onDismissRequest = { showWipeAccountConfirm = false },
                        containerColor = Color(0xFF1C1C1E),
                        title = { Text("Wipe account & messages", color = Color.White) },
                        text = {
                            Text(
                                "This permanently deletes this account's wallet keys and all its local messages and contacts from this device. This cannot be undone unless you have saved your seed phrase — without it, any remaining balance is unrecoverable.",
                                color = Color.Gray
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showWipeAccountConfirm = false
                                if (address != null) {
                                    chatViewModel.wipeAccountAndMessages(address, alsoDeleteCloud = false) {
                                        walletViewModel.deleteWallet(address)
                                    }
                                }
                            }) {
                                Text("Wipe Account", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWipeAccountConfirm = false }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }

                if (showWipeAccountCloudConfirm) {
                    val address = activeAddress
                    AlertDialog(
                        onDismissRequest = { showWipeAccountCloudConfirm = false },
                        containerColor = Color(0xFF1C1C1E),
                        title = { Text("Wipe account & messages & Cloud", color = Color.White) },
                        text = {
                            Text(
                                "This permanently deletes this account's wallet keys, all its local messages and contacts, and its Google Drive backup. This cannot be undone unless you have saved your seed phrase — without it, any remaining balance is unrecoverable.",
                                color = Color.Gray
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showWipeAccountCloudConfirm = false
                                if (address != null) {
                                    chatViewModel.wipeAccountAndMessages(address, alsoDeleteCloud = true) {
                                        walletViewModel.deleteWallet(address)
                                    }
                                }
                            }) {
                                Text("Wipe Everything", color = Color.Red, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWipeAccountCloudConfirm = false }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }
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

/**
 * Same card chrome as [SettingsSection], but starts collapsed to a single summary row — for
 * sections whose content (KNS domains/profile fields) isn't relevant on every visit to the
 * Profile screen and would otherwise push more useful sections further down.
 */
@Composable
fun CollapsibleSettingsSection(
    title: String,
    summary: String,
    description: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)) {
                    append(title)
                }
                if (!description.isNullOrBlank()) {
                    append("  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, fontSize = MaterialTheme.typography.bodySmall.fontSize)) {
                        append(description)
                    }
                }
            },
            color = Color.Gray,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    summary,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = KaspaTeal
                )
            }
            if (expanded) {
                HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                content()
            }
        }
    }
}

/**
 * Same card chrome as [CollapsibleSettingsSection], but the title itself (with its optional
 * description) is the clickable dropdown header — there's no separate outer label or truncated
 * preview line, since revealing the address is the entire point of expanding.
 */
@Composable
fun CollapsibleAddressSection(
    title: String,
    balance: String? = null,
    description: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(description, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!balance.isNullOrBlank()) {
                Text(balance, color = KaspaTeal, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = KaspaTeal
            )
        }
        if (expanded) {
            HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
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
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
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
fun SettingsInfoItem(label: String, value: String, valueColor: Color = Color.Gray, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
    dotColorHex: Long = 0xFF4CD964,
    // Chats has this moved to a floating action button instead (see ChatsScreen.kt) — false
    // there. Still defaults on for Profile/Settings, which keep the icon in the status bar.
    showAddButton: Boolean = true,
    showEditButton: Boolean = false,
    isEditing: Boolean = false,
    onEditClick: () -> Unit = {},
    selectAllLabel: String? = null,
    onSelectAllClick: () -> Unit = {}
) {
    val statusColor = Color(dotColorHex)

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isEditing && selectAllLabel != null) {
                TextButton(onClick = onSelectAllClick) {
                    Text(selectAllLabel, color = KaspaTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (showEditButton) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF1C1C1E), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Done" else "Select Chats",
                        tint = KaspaTeal,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (!isEditing) Spacer(Modifier.width(8.dp))
            }
            if (!isEditing) {
                if (showAddButton) {
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
                } else if (!showEditButton) {
                    // Keeps the balance text centered between the two ends, same as when the button is shown.
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }
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
    // "Other Nodes" means genuinely other than what's already listed above under Active
    // Nodes — allNodes includes every known node (active and not), so without this filter
    // the same active nodes showed up twice, once in each section.
    val otherNodes = remember(allNodes) { allNodes.filterNot { it.status == "Active" } }
    val status by viewModel.status.collectAsState()
    val dotColorHex by viewModel.dotColorHex.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // status is derived from the exact same latency thresholds as dotColorHex, so
    // the text here can never contradict the dot's color: green only says
    // Connected/Healthy, orange only says Degraded, red only says Weak/Unhealthy.
    val statusColor = Color(dotColorHex)
    val statusText = when (status) {
        ConnStatus.CONNECTED -> "Connected"
        ConnStatus.DEGRADED -> "Degraded"
        ConnStatus.WEAK -> "Weak"
        ConnStatus.DISCONNECTED -> "Disconnected"
    }
    val poolHealthText = when (status) {
        ConnStatus.CONNECTED -> "Healthy"
        ConnStatus.DEGRADED -> "Degraded"
        ConnStatus.WEAK -> "Weak"
        ConnStatus.DISCONNECTED -> "Unhealthy"
    }
    // "Verified" = currently reachable at all (Active or Suspect), a broader real count
    // than "Active" (Active additionally requires being in-sync and not recently failing).
    // Excludes nodes that haven't been probed even once yet (latency == "—") — those also
    // report as "Suspect" (see NodeRegistry.statusOf's lastProbe == null branch), but counting
    // them as "Verified" is misleading: right after a fresh launch/DNS-seed resolution this made
    // the whole pool look "Verified" before a single probe had actually completed.
    val verifiedCount = allNodes.count { (it.status == "Active" || it.status == "Suspect") && it.latency != "—" }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                ConnectionInfoRow("Status", statusText, statusColor)
                SettingsDivider()
                ConnectionInfoRow("Protocol", "gRPC (plaintext)")
                SettingsDivider()
                ConnectionInfoRow("Connected Node", activeNodes.firstOrNull()?.ip ?: "None")
                SettingsDivider()
                ConnectionInfoRow("Latency", activeNodes.firstOrNull()?.latency ?: "—", statusColor)
                SettingsDivider()
                ConnectionInfoRow("Indexer", indexerUrl.substringAfter("://").substringBefore("/"))
                SettingsDivider()
                ConnectionInfoRow("Push Register", pushIndexerUrl.substringAfter("://").substringBefore("/"))
                SettingsDivider()
                ConnectionInfoRow("Last Sync", lastSyncAt)
            }

            SettingsSection(title = "Pool Status") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    PoolStatItem("Active", activeNodes.size.toString(), Color(0xFF4CD964))
                    PoolStatItem("Verified", verifiedCount.toString(), Color(0xFF2196F3))
                    PoolStatItem("Total", allNodes.size.toString(), Color.Gray)
                }
                SettingsDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pool Health", color = Color.White)
                    Text(poolHealthText, color = statusColor)
                }
            }

            SettingsSection(title = "Actions") {
                SettingsActionItem("Refresh Pool", Icons.Default.Refresh, KaspaTeal, onClick = {
                    viewModel.refreshPool()
                    coroutineScope.launch { snackbarHostState.showSnackbar("Refreshing pool…") }
                })
                SettingsDivider()
                SettingsActionItem("Clear Connection Pool", Icons.Default.DeleteSweep, Color.Red, onClick = {
                    viewModel.clearPool()
                    coroutineScope.launch { snackbarHostState.showSnackbar("Pool cleared — reconnecting to seed nodes") }
                })
                SettingsDivider()
                SettingsActionItem("Reconnect", Icons.Default.Replay, KaspaTeal, onClick = {
                    viewModel.reconnect()
                    coroutineScope.launch { snackbarHostState.showSnackbar("Reconnecting…") }
                })
            }

            Text(
                text = "Primary: ${activeNodes.firstOrNull()?.ip ?: "None"}",
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
                        placeholder = { Text("host:port", color = Color.DarkGray) },
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
                        onClick = {
                            if (endpoint.isNotBlank()) {
                                viewModel.addManualEndpoint(endpoint)
                                endpoint = ""
                            }
                        },
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
                Text(text = activeNodes.size.toString(), color = Color.Gray)
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
                Text(text = otherNodes.size.toString(), color = Color.Gray)
            }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E))
            ) {
                otherNodes.forEachIndexed { index, node ->
                    AllNodeRow(node)
                    if (index < otherNodes.size - 1) SettingsDivider()
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
fun NotificationSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val soundEnabled by viewModel.notificationSoundEnabled.collectAsState()
    val vibrationEnabled by viewModel.notificationVibrationEnabled.collectAsState()

    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Notifications", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = Color.White, modifier = Modifier.size(20.dp))
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (!permissionGranted) {
                Surface(
                    color = Color(0xFF2C1C1C),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Notifications are off in system settings", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "KaChat can't show notifications until you allow them for this app.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }
                        }) {
                            Text("Open Settings", color = KaspaTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SettingsSection(title = "Push Notifications") {
                SettingsSwitchItem("Notifications", notificationsEnabled && permissionGranted) {
                    viewModel.setNotificationsEnabled(it)
                }
                SettingsFooter(
                    if (notificationsEnabled && permissionGranted)
                        "Receive notifications when contacts send messages while KaChat is running. There's no remote push server yet, so nothing arrives once the app has been fully closed by the system."
                    else
                        "Notifications are disabled."
                )
            }

            if (notificationsEnabled && permissionGranted) {
                SettingsSection(title = "Sound & Vibration") {
                    SettingsSwitchItem("Play sound", soundEnabled) {
                        viewModel.setNotificationSoundEnabled(it)
                    }
                    SettingsDivider()
                    SettingsSwitchItem("Vibration", vibrationEnabled) {
                        viewModel.setNotificationVibrationEnabled(it)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * Global default photo-compression quality for chat photos — mirrors iOS's
 * `PhotoQualitySettingsSheet`/`ChatPhotoQualitySlider`. Writes take effect immediately (no
 * separate Save step), matching every other row in [SettingsScreen]; only affects photos attached
 * after the change, never a photo already staged in a chat's composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoQualitySettingsScreen(onBack: () -> Unit, chatViewModel: ChatViewModel = hiltViewModel()) {
    val preset by chatViewModel.chatPhotoQualityPreset.collectAsState()
    val presets = com.kachat.app.models.ChatPhotoQualityPreset.entries
    val sliderPosition = presets.indexOf(preset).coerceAtLeast(0).toFloat()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Photo Quality", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = Color.White, modifier = Modifier.size(20.dp))
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Controls how much photos are compressed before sending. Higher quality looks clearer but costs a larger fee and takes longer to send; lower quality sends faster and cheaper but looks more compressed. This only affects photos you send, not ones you receive.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )

            SettingsSection(title = "Chats") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Photo quality", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Text(preset.summaryText, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            chatViewModel.updateChatPhotoQualityPreset(
                                com.kachat.app.models.ChatPhotoQualityPreset.fromSliderValue(it.toInt())
                            )
                        },
                        valueRange = 0f..(presets.size - 1).toFloat(),
                        steps = presets.size - 2,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = KaspaTeal,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
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
                ConnectionUrlField(label = "Indexer URL", value = indexerUrl)
                SettingsFooter("Message indexer service for chat functionality")
            }

            SettingsSection(title = "Push Registration") {
                ConnectionUrlField(label = "Push Indexer URL", value = pushIndexerUrl)
                SettingsFooter("Used only for push registration and updates")
            }

            SettingsSection(title = "Kaspa Name Service") {
                ConnectionUrlField(label = "KNS API URL", value = knsApiUrl)
                SettingsFooter("KNS domain resolution service")
            }

            SettingsSection(title = "Kaspa Explorer API") {
                ConnectionUrlField(label = "Kaspa REST API URL", value = kaspaRestApiUrl)
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

/** Read-only for now — editing these let a mistyped URL crash the whole app (fixed at the NetworkService layer too, but not editable at all is safer). */
@Composable
fun ConnectionUrlField(label: String, value: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
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
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // weight(1f) here (not on the status badge) so a long address — real discovered
            // peers can be IPv6 literals like [2601:680:cc80:5630:e1e5:e6fa:c86b:b946]:16111,
            // much longer than the old hardcoded IPv4 seeds — elides instead of squeezing the
            // badge down to near-zero width, which forced its own text to wrap letter by letter.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shield, null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(node.ip, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.background(Color(0xFF1E3A1E), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(node.status, color = Color(node.color), fontSize = 10.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row {
                Text(node.type, color = Color(0xFF2196F3), fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(node.latency, color = Color(0xFFF39C12), fontSize = 10.sp)
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
        // weight(1f) here (not on the latency/status side) so a long address — real discovered
        // peers can be IPv6 literals like [2601:680:cc80:5630:e1e5:e6fa:c86b:b946]:16111, much
        // longer than the old hardcoded IPv4 seeds — elides instead of squeezing the status
        // badge down to near-zero width, which forced its own text to wrap letter by letter.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.size(8.dp).background(Color(node.color), CircleShape))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Shield, null, tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(node.ip, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.latency, color = Color(0xFFF39C12), fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.background(Color(0x33FF3B30), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(node.status, color = Color(node.color), fontSize = 10.sp, maxLines = 1)
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

/**
 * Byte-mode QR encoding for raw binary payloads (e.g. a KSPT transaction frame) — ISO-8859-1 maps
 * every byte 1:1 to a char and back, so wrapping [bytes] this way and forcing ZXing's
 * `CHARACTER_SET` hint round-trips the exact bytes through its String-based encoder in byte mode,
 * matching KasSigner's own raw-byte QR encoding (`qrcode::QrCode::new(&[u8])`) on the other end.
 */
@Composable
fun rememberQrBitmapPainter(
    bytes: ByteArray,
    size: Int = 512,
    padding: Int = 0
): BitmapPainter {
    val density = LocalDensity.current
    val sizePx = with(density) { size.dp.roundToPx() }

    val bitmap = remember(bytes) {
        if (bytes.isEmpty()) {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        } else {
            val content = String(bytes, Charsets.ISO_8859_1)
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(EncodeHintType.MARGIN to padding, EncodeHintType.CHARACTER_SET to "ISO-8859-1")
            )
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
            bmp.asImageBitmap()
        }
    }
    return BitmapPainter(bitmap)
}

/**
 * Cycles through [frames] at a fixed interval — the display half of KasSigner's animated multi-
 * frame QR protocol (see [com.kachat.app.util.QrFrameChunker]). A single-frame list just renders
 * a static code with no play/pause controls or frame counter.
 */
@Composable
fun AnimatedQrDisplay(frames: List<ByteArray>, modifier: Modifier = Modifier, frameDelayMs: Long = 2500L) {
    var frameIndex by remember(frames) { mutableStateOf(0) }
    // Matches KasSee's own 2.5s auto-advance (kassee/web/js/app.js's displayKsptQr) — a scanning
    // camera needs real time to lock onto and decode each frame; a faster cycle (this used to
    // default to 200ms) skips past frames before the scanner ever catches them.
    var isPlaying by remember(frames) { mutableStateOf(true) }

    LaunchedEffect(frames, isPlaying) {
        if (frames.size <= 1 || !isPlaying) return@LaunchedEffect
        while (true) {
            delay(frameDelayMs)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        val painter = rememberQrBitmapPainter(bytes = frames[frameIndex.coerceIn(frames.indices)])
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(2.dp, KaspaTeal, RoundedCornerShape(20.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(painter = painter, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
        }
        if (frames.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                frames.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i == frameIndex) KaspaTeal else Color(0xFF2C2C2E))
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Manual stepping works whether playing or paused — tapping prev/next doesn't
                // require pausing first, matching KasSee's own frame-nav buttons.
                IconButton(onClick = { frameIndex = (frameIndex - 1 + frames.size) % frames.size }) {
                    Icon(Icons.Default.SkipPrevious, "Previous frame", tint = KaspaTeal)
                }
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = KaspaTeal
                    )
                }
                IconButton(onClick = { frameIndex = (frameIndex + 1) % frames.size }) {
                    Icon(Icons.Default.SkipNext, "Next frame", tint = KaspaTeal)
                }
                Text("Frame ${frameIndex + 1} / ${frames.size}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Full-bleed QR overlay over the current screen's content area — tap anywhere to dismiss. [message], if given, renders as a caption below the code (e.g. funding guidance). */
@Composable
fun QrCodeOverlay(
    value: String,
    onDismiss: () -> Unit,
    message: String? = null,
    borderColor: Color = KaspaTeal,
    borderWidth: Dp = 2.dp
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val qrPainter = rememberQrBitmapPainter(value)
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = qrPainter,
                    contentDescription = "QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (message != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = message,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
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
    var showScanner by remember { mutableStateOf(false) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val isValidRawAddress = remember(address) { KaspaAddress.isValid(address) }
    val looksLikeKnsDomain = remember(address) { com.kachat.app.services.KnsService.looksLikeDomain(address) }

    val knsResolvedAddress by chatViewModel.knsResolvedAddress.collectAsState()
    val isResolvingKns by chatViewModel.isResolvingKns.collectAsState()
    val knsError by chatViewModel.knsError.collectAsState()

    val context = LocalContext.current
    // Reads the picked contact's data via the /entities sub-path of the URI the system picker
    // itself returns — covered by the temporary read grant that comes with that URI, so no
    // READ_CONTACTS runtime permission is needed (matches ChatInfoScreen's "Link from Contacts"
    // picker, which relies on the same grant for its own, narrower query).
    val importContactMimeTypes = setOf(
        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
    )
    val pickContactForImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val entityUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY)
        var foundAddress: String? = null
        var displayName: String? = null
        context.contentResolver.query(
            entityUri,
            arrayOf(
                ContactsContract.Contacts.Entity.MIMETYPE,
                ContactsContract.Contacts.Entity.DATA1,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            ),
            null, null, null
        )?.use { cursor ->
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.MIMETYPE)
            val dataIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.Entity.DATA1)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                if (displayName == null) displayName = cursor.getString(nameIdx)
                if (foundAddress != null) continue
                val mime = cursor.getString(mimeIdx) ?: continue
                if (mime !in importContactMimeTypes) continue
                val value = cursor.getString(dataIdx) ?: continue
                foundAddress = com.kachat.app.services.SystemContactsSyncService.extractKaspaAddresses(value).firstOrNull()
            }
        }
        if (foundAddress != null) {
            address = foundAddress!!
            if (name.isBlank()) name = displayName ?: ""
            importErrorMessage = null
        } else {
            importErrorMessage = "No Kaspa address found in ${displayName ?: "that contact"}"
        }
    }

    LaunchedEffect(address) {
        importErrorMessage = null
        chatViewModel.onCreateChatAddressChanged(address)
    }

    // The address actually used to create the contact — the resolved owner address
    // when the input is a KNS domain, otherwise whatever was typed directly.
    val effectiveAddress = if (looksLikeKnsDomain) knsResolvedAddress else address
    val isValidAddress = if (looksLikeKnsDomain) knsResolvedAddress != null else isValidRawAddress

    if (showScanner) {
        BackHandler { showScanner = false }
        QrScannerOverlay(
            onScanned = { scanned ->
                address = scanned
                showScanner = false
            },
            onDismiss = { showScanner = false }
        )
        return
    }

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
                            val resolvedAddress = effectiveAddress ?: return@TextButton
                            chatViewModel.addContact(
                                address = resolvedAddress,
                                name = name,
                                knsName = if (looksLikeKnsDomain) com.kachat.app.services.KnsService.normalizeDomain(address) else null
                            )
                            onChatCreated(resolvedAddress)
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
                
                if (looksLikeKnsDomain && isResolvingKns) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = KaspaTeal, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Resolving domain…", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (looksLikeKnsDomain && knsError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(knsError ?: "", color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (looksLikeKnsDomain && knsResolvedAddress != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Resolved to ${knsResolvedAddress?.takeLast(12)}",
                            color = Color(0xFF4CD964),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (isValidAddress) {
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
                    CreateChatActionItem(Icons.Default.PersonAddAlt1, "Import") {
                        pickContactForImportLauncher.launch(null)
                    }
                    CreateChatActionItem(Icons.Default.ContentPaste, "Paste") {
                        clipboardManager.getText()?.text?.let { address = it.trim() }
                    }
                    CreateChatActionItem(Icons.Default.QrCodeScanner, "Scan QR") { showScanner = true }
                }

                importErrorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(message, color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
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
    chatViewModel: ChatViewModel = hiltViewModel(),
    fromBroadcast: Boolean = false
) {
    val conversation = chatViewModel.conversations.collectAsState().value.find { it.contact.id == contactId }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val contactBalances by chatViewModel.contactBalances.collectAsState()
    val contactBalance = contactBalances[contactId] ?: "0.00000000"
    val showContactBalance by chatViewModel.showContactBalance.collectAsState()
    val knsProfile = chatViewModel.knsProfiles.collectAsState().value[contactId]
    val systemContactId = conversation?.contact?.systemContactId
    val systemContactName = conversation?.contact?.systemContactName

    var contactName by remember { mutableStateOf("") }

    // Synchronize local state with database when it loads
    LaunchedEffect(conversation?.contact?.alias) {
        contactName = conversation?.contact?.alias ?: ""
    }

    LaunchedEffect(contactId) {
        chatViewModel.refreshKnsProfile(contactId)
        // Not fetched anywhere else when arriving here directly (e.g. from a broadcast avatar's
        // "View Profile", which never visits the 1:1 chat thread that would otherwise trigger
        // this) — without it the Balance section below silently shows the "0.00000000" fallback
        // instead of a real balance.
        chatViewModel.refreshContactBalance(contactId)
    }

    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    var showQr by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val pickContactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val lookupKey = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY))
                val displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))
                if (lookupKey != null && displayName != null) {
                    contactName = displayName
                    chatViewModel.linkSystemContact(contactId, lookupKey, displayName)
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (fromBroadcast) "User Info" else "Chat Info", color = Color.White, fontWeight = FontWeight.Bold) },
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
                    ContactAvatar(
                        imageUrl = knsProfile?.profile?.avatarUrl,
                        fallbackText = conversation?.contact?.alias ?: contactId.takeLast(8),
                        size = 60.dp,
                        backgroundColor = Color(0xFF2C2C2E),
                        fontSize = 20.sp
                    )
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

            if (!fromBroadcast) {
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

                SettingsSection(title = "Photos") {
                    val requirePhotoApproval by chatViewModel.requirePhotoApprovalForNewContacts.collectAsState()
                    val photoOverride = com.kachat.app.models.PhotoAutoDisplayMode.fromName(conversation?.contact?.photoAutoDisplayOverride)
                    val automaticResolvesToShow = !requirePhotoApproval || conversation?.contact?.conversationStatus == "active"

                    Column(modifier = Modifier.padding(16.dp)) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(
                                Triple("Automatic", com.kachat.app.models.PhotoAutoDisplayMode.AUTOMATIC, 0),
                                Triple("Always Show", com.kachat.app.models.PhotoAutoDisplayMode.ALWAYS_SHOW, 1),
                                Triple("Always Hide", com.kachat.app.models.PhotoAutoDisplayMode.ALWAYS_HIDE, 2)
                            )
                            options.forEach { (label, value, index) ->
                                SegmentedButton(
                                    selected = photoOverride == value,
                                    onClick = {
                                        chatViewModel.updateContactPhotoOverride(
                                            contactId,
                                            if (value == com.kachat.app.models.PhotoAutoDisplayMode.AUTOMATIC) null else value
                                        )
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = Color(0xFF2C2C2E),
                                        activeContentColor = Color.White,
                                        inactiveContainerColor = Color(0xFF1C1C1E),
                                        inactiveContentColor = Color.Gray
                                    )
                                ) {
                                    Text(label, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    SettingsFooter(
                        "Automatic currently ${if (automaticResolvesToShow) "shows" else "hides"} photos from this contact. " +
                            "It hides photos from contacts you haven't added or messaged yet, until you tap to reveal them."
                    )
                }
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

            SettingsSection(title = "System Contact") {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (systemContactId != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Linked", color = Color.White)
                            Text(systemContactName ?: "", color = Color.Gray)
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                        Spacer(Modifier.height(12.dp))
                    } else {
                        Text("Not linked", color = Color.Gray)
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.clickable { pickContactLauncher.launch(null) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PersonAddAlt1, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Link from Contacts", color = KaspaTeal, fontWeight = FontWeight.Bold)
                    }

                    if (systemContactId != null) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.clickable { chatViewModel.unlinkSystemContact(contactId) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.RemoveCircleOutline, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Unlink", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (showContactBalance) {
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
            }

            val knsFields = knsProfile?.profile
            val hasKnsProfile = knsFields != null && listOf(
                knsFields.bio, knsFields.x, knsFields.website, knsFields.telegram,
                knsFields.discord, knsFields.contactEmail, knsFields.github
            ).any { !it.isNullOrBlank() }

            if (hasKnsProfile) {
                SettingsSection(title = "KNS Profile") {
                    val context = LocalContext.current
                    Column(modifier = Modifier.padding(16.dp)) {
                        knsFields?.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                            Text(
                                text = bio,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.clickable { clipboardManager.setText(AnnotatedString(bio)) }
                            )
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                            Spacer(Modifier.height(12.dp))
                        }

                        val socialLinks = listOfNotNull(
                            knsFields?.x?.takeIf { it.isNotBlank() }?.let { "X" to it },
                            knsFields?.website?.takeIf { it.isNotBlank() }?.let { "Website" to it },
                            knsFields?.telegram?.takeIf { it.isNotBlank() }?.let { "Telegram" to it },
                            knsFields?.discord?.takeIf { it.isNotBlank() }?.let { "Discord" to it },
                            knsFields?.contactEmail?.takeIf { it.isNotBlank() }?.let { "Email" to it },
                            knsFields?.github?.takeIf { it.isNotBlank() }?.let { "GitHub" to it }
                        )
                        socialLinks.forEachIndexed { index, (label, value) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val url = if (value.startsWith("http")) value else "https://$value"
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        } catch (e: Exception) { /* no browser available */ }
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                                Text(value, color = KaspaTeal, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (index < socialLinks.lastIndex) {
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                            }
                        }
                    }
                }
            }

            val ownedDomains = knsProfile?.ownedDomains.orEmpty()
            if (ownedDomains.isNotEmpty()) {
                SettingsSection(title = "KNS Domains") {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ownedDomains.forEachIndexed { index, domain ->
                            val isSelected = domain == knsProfile?.selectedDomain
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chatViewModel.selectKnsDomain(contactId, domain) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(domain, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .background(KaspaTeal.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Selected", color = KaspaTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            if (index < ownedDomains.lastIndex) {
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.2f))
                            }
                        }
                    }
                }
            }

            if (!fromBroadcast) {
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

