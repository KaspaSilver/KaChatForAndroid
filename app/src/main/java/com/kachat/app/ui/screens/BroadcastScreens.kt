package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.FeaturedBroadcastChannels
import com.kachat.app.repository.ChatRepository
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import com.kachat.app.viewmodels.BroadcastViewModel
import com.kachat.app.viewmodels.WalletViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastListScreen(
    navController: NavController,
    onBack: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel()
) {
    val channels by broadcastViewModel.joinedChannels.collectAsState()
    val popularTabEnabled by broadcastViewModel.popularTabEnabled.collectAsState()
    val showKnsAvatarsEnabled by broadcastViewModel.showKnsAvatarsEnabled.collectAsState()
    val autoAvatarSearchEnabled by broadcastViewModel.autoAvatarSearchEnabled.collectAsState()
    val hiddenSenderAddresses by broadcastViewModel.hiddenSenderAddresses.collectAsState()
    val joinState by broadcastViewModel.joinChannelState.collectAsState()
    var showJoinDialog by remember { mutableStateOf(false) }
    var showBroadcastSettingsDialog by remember { mutableStateOf(false) }
    var showAutoAvatarWarningDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    var channelToLeave by remember { mutableStateOf<String?>(null) }
    var retentionSettingsChannelName by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(joinState.status) {
        if (joinState.status == BroadcastViewModel.JoinChannelStatus.SUCCESS) {
            showJoinDialog = false
        }
    }

    // Don't leave the user stuck on a tab that just got hidden.
    LaunchedEffect(popularTabEnabled) {
        if (!popularTabEnabled) selectedTab = 0
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Broadcasts", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                actions = {
                    IconButton(onClick = { showBroadcastSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, "Broadcast Settings", tint = KaspaTeal)
                    }
                    IconButton(onClick = {
                        channelInput = ""
                        broadcastViewModel.resetJoinChannelState()
                        showJoinDialog = true
                    }) {
                        Icon(Icons.Default.Add, "Join Channel", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (popularTabEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Channels" to 0, "Popular" to 1).forEach { (label, index) ->
                        val selected = selectedTab == index
                        Surface(
                            color = if (selected) KaspaTeal else Color(0xFF1C1C1E),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f).clickable { selectedTab = index }
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            if (selectedTab == 1 && popularTabEnabled) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(FeaturedBroadcastChannels.NAMES, key = { it }) { name ->
                        val alreadyJoined = channels.any { it.channelName == name }
                        Surface(
                            color = Color(0xFF1C1C1E),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!alreadyJoined) broadcastViewModel.joinChannel(name)
                                    navController.navigate("broadcast_channel/$name")
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("#$name", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    if (alreadyJoined) "Joined" else "Join",
                                    color = if (alreadyJoined) Color.Gray else KaspaTeal,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            } else if (channels.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "No broadcast channels yet",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Broadcasts are public, unencrypted channels — anyone who joins the same channel name sees the same messages.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(channels, key = { it.channelName }) { channel ->
                        Surface(
                            color = Color(0xFF1C1C1E),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate("broadcast_channel/${channel.channelName}") }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("#${channel.channelName}", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = {
                                    val newValue = !channel.alwaysListen
                                    broadcastViewModel.setAlwaysListen(channel.channelName, newValue)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (newValue) {
                                                "You will now listen for new chats as long as your app remains open"
                                            } else {
                                                "You will no longer see messages in this broadcast unless you are in the broadcast at the same time chats come in"
                                            }
                                        )
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (channel.alwaysListen) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = if (channel.alwaysListen) "Stop always listening" else "Always listen",
                                        tint = if (channel.alwaysListen) KaspaTeal else Color.Gray
                                    )
                                }
                                IconButton(onClick = {
                                    val newValue = !channel.notifyEnabled
                                    broadcastViewModel.setNotifyEnabled(channel.channelName, newValue)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (newValue) {
                                                "You'll get a notification for new messages in this broadcast as long as your app remains open"
                                            } else {
                                                "Notifications are off for this broadcast"
                                            }
                                        )
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (channel.notifyEnabled) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                                        contentDescription = if (channel.notifyEnabled) "Turn off notifications" else "Turn on notifications",
                                        tint = if (channel.notifyEnabled) KaspaTeal else Color.Gray
                                    )
                                }
                                IconButton(onClick = { retentionSettingsChannelName = channel.channelName }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Message retention settings",
                                        tint = Color.Gray
                                    )
                                }
                                TextButton(onClick = { channelToLeave = channel.channelName }) {
                                    Text("Leave", color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Join or Create a Channel", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Anyone who joins the same channel name can see and post messages there — there's no owner or invite.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        placeholder = { Text("channel-name", color = Color.DarkGray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = KaspaTeal,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (joinState.status == BroadcastViewModel.JoinChannelStatus.FAILED) {
                        Spacer(Modifier.height(8.dp))
                        Text(joinState.message ?: "Invalid channel name", color = Color(0xFFFF3B30), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                // Doesn't close the dialog itself — joinChannel() updates joinChannelState
                // asynchronously, so whether this succeeded isn't known yet at click time. The
                // LaunchedEffect above closes the dialog once SUCCESS actually arrives; on
                // FAILED it stays open showing the error text instead.
                TextButton(onClick = { broadcastViewModel.joinChannel(channelInput) }) {
                    Text("Join", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    channelToLeave?.let { channelName ->
        AlertDialog(
            onDismissRequest = { channelToLeave = null },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Leave #$channelName", color = Color.White) },
            text = {
                Text(
                    "Leaving this broadcast permanently deletes every message cached for it on this device. This cannot be undone — rejoining later starts with no history.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    broadcastViewModel.leaveChannel(channelName)
                    channelToLeave = null
                }) {
                    Text("Leave & Delete", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { channelToLeave = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    retentionSettingsChannelName?.let { channelName ->
        // Looked up live from `channels` (rather than captured at click time) so a stale snapshot
        // never overwrites a concurrent update; only used to seed the fields below, though, since
        // the fields themselves must survive unrelated recompositions (e.g. a new message arriving)
        // while the dialog is open without resetting whatever the user is mid-typing.
        val channel = channels.firstOrNull { it.channelName == channelName }
        if (channel != null) {
            val (initialAmount, initialUnit) = remember(channelName) { BroadcastRetention.toAmountAndUnit(channel.retentionMillis) }
            var amountText by remember(channelName) { mutableStateOf(initialAmount.toString()) }
            var selectedUnit by remember(channelName) { mutableStateOf(initialUnit) }
            var unitMenuExpanded by remember(channelName) { mutableStateOf(false) }

            val amount = amountText.toLongOrNull()
            val isValid = amount != null && amount in 1..selectedUnit.maxAmount

            AlertDialog(
                onDismissRequest = { retentionSettingsChannelName = null },
                containerColor = Color(0xFF1C1C1E),
                title = { Text("Message Retention for #$channelName", color = Color.White) },
                text = {
                    Column {
                        Text(
                            "How long messages in this broadcast stay cached on this device, up to a maximum of 3 days.",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { input -> amountText = input.filter { it.isDigit() }.take(9) },
                                singleLine = true,
                                isError = !isValid,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = KaspaTeal,
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { unitMenuExpanded = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Text(selectedUnit.label)
                                }
                                DropdownMenu(
                                    expanded = unitMenuExpanded,
                                    onDismissRequest = { unitMenuExpanded = false },
                                    modifier = Modifier.background(Color(0xFF2C2C2E))
                                ) {
                                    BroadcastRetention.Unit.entries.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text(unit.label, color = Color.White) },
                                            onClick = {
                                                // Re-clamp the typed amount to the new unit's cap rather than clearing it,
                                                // so switching e.g. seconds -> hours after typing 200 lands on the 72-hour max.
                                                val current = amountText.toLongOrNull()
                                                if (current != null && current > unit.maxAmount) {
                                                    amountText = unit.maxAmount.toString()
                                                }
                                                selectedUnit = unit
                                                unitMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Max: ${selectedUnit.maxAmount} ${selectedUnit.label}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Longer retention means more messages stay cached on your device — this can slow the app down over time, especially for busy rooms.",
                            color = Color(0xFFF39C12),
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = isValid,
                        onClick = {
                            broadcastViewModel.setRetentionMillis(channelName, amount!! * selectedUnit.millisPerUnit)
                            retentionSettingsChannelName = null
                        }
                    ) {
                        Text("Save", color = if (isValid) KaspaTeal else Color.Gray, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { retentionSettingsChannelName = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }
    }

    if (showBroadcastSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showBroadcastSettingsDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Broadcast Settings", color = Color.White) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Popular Tab", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Shows a tab of recommended broadcast rooms you can jump into.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = popularTabEnabled,
                            onCheckedChange = { broadcastViewModel.setPopularTabEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f))
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("KNS Profile Pictures", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Shows senders' KNS avatars in rooms. Off shows plain initials for everyone instead.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = showKnsAvatarsEnabled,
                            onCheckedChange = { broadcastViewModel.setShowKnsAvatarsEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f))
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Automatic Avatar Search", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Automatically looks up a sender's KNS avatar as soon as their message appears. Off by default — see warning if you enable it.",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = autoAvatarSearchEnabled,
                            onCheckedChange = { enabling ->
                                if (enabling) {
                                    showAutoAvatarWarningDialog = true
                                } else {
                                    broadcastViewModel.setAutoAvatarSearchEnabled(false)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                    SettingsNavigationItem(
                        "Hidden Broadcast Room Users",
                        Icons.Default.VisibilityOff,
                        hiddenSenderAddresses.size.toString(),
                        onClick = {
                            showBroadcastSettingsDialog = false
                            navController.navigate("hidden_broadcast_users")
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBroadcastSettingsDialog = false }) {
                    Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAutoAvatarWarningDialog) {
        AlertDialog(
            onDismissRequest = { showAutoAvatarWarningDialog = false },
            containerColor = Color(0xFF1C1C1E),
            title = { Text("Enable Automatic Avatar Search?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "A sender's avatar picture can be set to any web address they choose, and this app would fetch it the moment their message appears on your screen — before you tap anything. " +
                        "That means anyone who posts in a room you view can learn that you viewed it, roughly when, and your IP address, just from you opening the channel.\n\n" +
                        "Only enable this if you're comfortable with senders in a room potentially detecting when you're viewing it.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    broadcastViewModel.setAutoAvatarSearchEnabled(true)
                    showAutoAvatarWarningDialog = false
                }) {
                    Text("Enable Anyway", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoAvatarWarningDialog = false }) {
                    Text("Cancel", color = KaspaTeal)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BroadcastChannelScreen(
    channelName: String,
    onBack: () -> Unit,
    navController: NavController,
    broadcastViewModel: BroadcastViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val messages by broadcastViewModel.getMessages(channelName).collectAsState(initial = emptyList())
    val myAddress by walletViewModel.address.collectAsState()
    val sendState by broadcastViewModel.sendBroadcastState.collectAsState()
    val voiceRecordingState by broadcastViewModel.voiceRecordingState.collectAsState()
    val messageText by broadcastViewModel.messageText.collectAsState()
    val estimatedFee by broadcastViewModel.estimatedFeeSompi.collectAsState()
    val estimateFeesEnabled by broadcastViewModel.estimateFeesEnabled.collectAsState()
    val senderProfiles by broadcastViewModel.senderProfiles.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val showKnsAvatarsEnabled by broadcastViewModel.showKnsAvatarsEnabled.collectAsState()
    val replyingTo by broadcastViewModel.replyingTo.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Swipe-left-to-reveal-timestamps (iMessage-style): dragging left across the whole message
    // list shifts every message row left by the same amount, uncovering a per-message time in the
    // strip of space that opens up on the right; releasing snaps everything back. revealOffsetPx
    // is negative-or-zero (never allowed to shift right past its resting position).
    val revealOffsetPx = remember { Animatable(0f) }
    val maxRevealOffsetPx = with(LocalDensity.current) { 64.dp.toPx() }

    val micContext = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) broadcastViewModel.startVoiceRecording(channelName)
    }
    val startVoiceRecordingIfPermitted = {
        if (broadcastViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                broadcastViewModel.startVoiceRecording(channelName)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Only show the "jump to latest" button once the last message isn't even partially
    // visible — a real, deliberate scroll away from the bottom to read history — not just a
    // transient viewport shrink. Tolerates a 1-item gap for the same reason as 1:1 chat's
    // equivalent check (see MessageBubble's screen in Screens.kt).
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex < messages.lastIndex - 1
        }
    }

    // Fee preview needs real UTXOs/fee-rate data, not just whatever was last fetched (which
    // could be empty/stale) — refresh once on entry, matching how 1:1 chats always have this
    // available rather than gating it behind a separate "payment mode" (broadcasts have none).
    LaunchedEffect(Unit) {
        broadcastViewModel.refreshUtxos()
    }

    // Live messages appear while this screen is open even if this channel isn't marked
    // always-listen — bounded to exactly as long as this composable is on screen.
    DisposableEffect(channelName) {
        broadcastViewModel.startLiveViewing(channelName)
        onDispose { broadcastViewModel.stopLiveViewing() }
    }

    LaunchedEffect(myAddress) {
        myAddress?.let { broadcastViewModel.ensureSenderProfileFetched(it) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("#$channelName", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(Color.Black).navigationBarsPadding().imePadding().padding(8.dp)) {
                if (sendState.status == BroadcastViewModel.SendBroadcastStatus.FAILED) {
                    Text(
                        sendState.message ?: "Failed to send",
                        color = Color(0xFFFF3B30),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
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
                                "Replying to ${contactAliases[reply.senderAddress] ?: senderKnsNames[reply.senderAddress] ?: reply.senderAddress.takeLast(10)}",
                                color = KaspaTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                                    ?: MessageReply.parseOrNull(reply.content)?.text
                                    ?: reply.content,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { broadcastViewModel.cancelReply() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = Color.Gray)
                        }
                    }
                }
                if (voiceRecordingState.status == BroadcastViewModel.VoiceRecordingStatus.RECORDING) {
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
                        IconButton(onClick = { broadcastViewModel.cancelVoiceRecording() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel recording", tint = Color(0xFFFF3B30))
                        }
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
                        Text(
                            text = "Recording... ${formatRecordingElapsed(voiceRecordingState.elapsedMs)}",
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { broadcastViewModel.stopAndSendVoiceRecording(channelName) },
                            modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
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
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { broadcastViewModel.setMessageText(it) },
                            placeholder = { Text("Message #$channelName", color = Color.DarkGray) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = KaspaTeal,
                                unfocusedBorderColor = Color.Gray
                            )
                        )
                        val sending = sendState.status == BroadcastViewModel.SendBroadcastStatus.SENDING
                        if (messageText.isEmpty()) {
                            IconButton(onClick = { startVoiceRecordingIfPermitted() }) {
                                Icon(Icons.Default.Mic, "Record voice message", tint = KaspaTeal)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (!sending && messageText.isNotBlank()) {
                                        broadcastViewModel.sendBroadcast(channelName, messageText)
                                        broadcastViewModel.setMessageText("")
                                    }
                                },
                                enabled = !sending
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (sending) Color.Gray else KaspaTeal)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
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
        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No messages yet — be the first to post in #$channelName",
                    color = Color.Gray,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    val showDateDivider = index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)
                    if (showDateDivider) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    ChatTimeFormat.formatDateDivider(message.blockTimestamp),
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    val isMine = message.senderAddress == myAddress
                    val replyContent = remember(message.content) { MessageReply.parseOrNull(message.content) }
                    val displayContent = replyContent?.text ?: message.content
                    val voiceContent = remember(displayContent) { VoiceMessage.parseOrNull(displayContent) }
                    var showMenu by remember { mutableStateOf(false) }
                    val clipboardManager = LocalClipboardManager.current

                    LaunchedEffect(message.senderAddress) {
                        broadcastViewModel.ensureSenderProfileFetched(message.senderAddress)
                    }

                    var showAvatarMenu by remember { mutableStateOf(false) }

                    val avatar: @Composable () -> Unit = {
                        Box {
                            ContactAvatar(
                                imageUrl = if (showKnsAvatarsEnabled) senderProfiles[message.senderAddress] else null,
                                fallbackText = message.senderAddress.takeLast(8),
                                size = 32.dp,
                                modifier = Modifier.clickable { showAvatarMenu = true }
                            )
                            DropdownMenu(
                                expanded = showAvatarMenu,
                                onDismissRequest = { showAvatarMenu = false },
                                modifier = Modifier.background(Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("View Profile", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = { Icon(Icons.Default.Person, null, tint = KaspaTeal) },
                                    onClick = {
                                        broadcastViewModel.openSenderProfile(message.senderAddress) { address ->
                                            navController.navigate("chat_info/$address?fromBroadcast=true")
                                        }
                                        showAvatarMenu = false
                                    }
                                )
                                if (!isMine) {
                                    DropdownMenuItem(
                                        text = { Text("Open Chat", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = KaspaTeal) },
                                        onClick = {
                                            broadcastViewModel.openSenderProfile(message.senderAddress) { address ->
                                                navController.navigate("chat/$address")
                                            }
                                            showAvatarMenu = false
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Copy Address", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal) },
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(message.senderAddress))
                                        showAvatarMenu = false
                                    }
                                )
                                if (!isMine) {
                                    DropdownMenuItem(
                                        text = { Text("Pay in Kaspa", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = { Icon(Icons.Default.CurrencyExchange, null, tint = KaspaTeal) },
                                        onClick = {
                                            broadcastViewModel.openSenderProfile(message.senderAddress) { address ->
                                                navController.navigate("chat/$address?paymentMode=true")
                                            }
                                            showAvatarMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Hide User", color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = { Icon(Icons.Default.VisibilityOff, null, tint = Color(0xFFFF3B30)) },
                                        onClick = {
                                            broadcastViewModel.hideSender(message.senderAddress)
                                            showAvatarMenu = false
                                        }
                                    )
                                }
                            }
                        }
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
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Bottom
                        ) {
                        if (!isMine) {
                            avatar()
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                            Text(
                                contactAliases[message.senderAddress] ?: senderKnsNames[message.senderAddress] ?: message.senderAddress.takeLast(10),
                                color = KaspaTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = if (isMine) 0.dp else 14.dp,
                                    end = if (isMine) 14.dp else 0.dp,
                                    bottom = 2.dp
                                )
                            )
                            if (replyContent != null) {
                                Surface(
                                    color = Color(0xFF2C2C2E),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 240.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            contactAliases[replyContent.replyToSender] ?: senderKnsNames[replyContent.replyToSender] ?: replyContent.replyToSender.takeLast(10),
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
                                if (voiceContent != null) {
                                    AudioBubble(
                                        voiceContent,
                                        isSent = isMine,
                                        onLongPress = { showMenu = true },
                                        onDoubleClick = { broadcastViewModel.startReplyTo(message) }
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .background(
                                                if (isMine) KaspaTeal else Color(0xFF1C1C1E),
                                                RoundedCornerShape(16.dp)
                                            )
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = { showMenu = true },
                                                onDoubleClick = { broadcastViewModel.startReplyTo(message) }
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                            .widthIn(max = 280.dp)
                                    ) {
                                        Text(
                                            displayContent,
                                            color = if (isMine) Color.Black else Color.White
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Copy Message", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = KaspaTeal) },
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(displayContent))
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
                                    if (isMine && message.deliveryStatus == "failed") {
                                        DropdownMenuItem(
                                            text = { Text("Retry Send", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                            leadingIcon = { Icon(Icons.Default.Refresh, null, tint = KaspaTeal) },
                                            onClick = {
                                                broadcastViewModel.retryBroadcast(message)
                                                showMenu = false
                                            }
                                        )
                                    }
                                }

                                // A small corner badge rather than a row below the bubble —
                                // stacking it as a separate row would grow the Column past the
                                // bubble's own height, throwing off the avatar's bottom-alignment
                                // in the outer Row (the exact bug the old always-visible timestamp
                                // row caused, see git history).
                                if (isMine) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 4.dp, y = 4.dp)
                                            .size(14.dp)
                                            .background(Color.Black, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
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
                        }
                        if (isMine) {
                            Spacer(Modifier.width(8.dp))
                            avatar()
                        }
                        }
                    }
                }
            }
        }

        if (showScrollToBottom && messages.isNotEmpty()) {
            IconButton(
                onClick = {
                    coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
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

/** Manages senders hidden from every broadcast room (set via "Hide User" on an avatar) — reachable from the main Settings tab, underneath Archived Chats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenBroadcastUsersScreen(
    onBack: () -> Unit,
    broadcastViewModel: BroadcastViewModel = hiltViewModel()
) {
    val hiddenSenderAddresses by broadcastViewModel.hiddenSenderAddresses.collectAsState()
    val contactAliases by broadcastViewModel.contactAliases.collectAsState()
    val senderKnsNames by broadcastViewModel.senderKnsNames.collectAsState()

    // Same alias -> KNS name -> short address fallback used inside a broadcast room — a hidden
    // user's name here should read the same as it would if they weren't hidden. KNS names aren't
    // fetched anywhere else for these addresses (no message list is rendering them once hidden),
    // so this screen has to kick that lookup off itself.
    LaunchedEffect(hiddenSenderAddresses) {
        hiddenSenderAddresses.forEach { broadcastViewModel.ensureSenderProfileFetched(it) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Hidden Broadcast Room Users", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        if (hiddenSenderAddresses.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No hidden users",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Users you hide from a broadcast room (via their avatar menu) show up here, and never appear or get cached in any room.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(hiddenSenderAddresses.toList(), key = { it }) { address ->
                    Surface(
                        color = Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                contactAliases[address] ?: senderKnsNames[address] ?: address.takeLast(10),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { broadcastViewModel.unhideSender(address) }) {
                                Text("Unhide", color = KaspaTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
