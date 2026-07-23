package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.models.GroupMember
import com.kachat.app.repository.ChatRepository
import com.kachat.app.repository.GroupMessage
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChatTimeFormat
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import com.kachat.app.viewmodels.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Parses a [com.kachat.app.models.GroupEntity]'s stored roster JSON, or empty on failure - shared by every screen below instead of each re-implementing the same try/catch. */
fun parseGroupMembers(group: com.kachat.app.models.GroupEntity): List<GroupMember> {
    return try {
        val type = object : com.google.gson.reflect.TypeToken<List<GroupMember>>() {}.type
        com.google.gson.Gson().fromJson<List<GroupMember>>(group.membersJson, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * `@mention` support for group chat - no protocol/wire-format change: a mention is embedded in
 * the plaintext as `@{fullKaspaAddress}` (unambiguous - real addresses always carry a
 * `kaspa:`/`kaspatest:` prefix, so this can't collide with someone typing a literal "@word"),
 * swapped for the mentioned member's resolved display name only at render time. Mirrors iOS's
 * `GroupMentionCodec` exactly.
 */
object GroupMentionCodec {
    fun encodeForSending(text: String, members: List<GroupMember>, resolveDisplayName: (String) -> String): String {
        var result = text
        // Longest name first, so e.g. "@Alice2" doesn't get partially clobbered by a "@Alice" replacement first.
        for (member in members.sortedByDescending { resolveDisplayName(it.address).length }) {
            val name = resolveDisplayName(member.address)
            if (name.isBlank()) continue
            result = result.replace("@$name", "@${member.address}")
        }
        return result
    }

    fun decodeForDisplay(text: String, members: List<GroupMember>, resolveDisplayName: (String) -> String): String {
        var result = text
        for (member in members) {
            val name = resolveDisplayName(member.address)
            if (name.isBlank()) continue
            result = result.replace("@${member.address}", "@$name")
        }
        return result
    }
}

/**
 * Group chat thread — mirrors 1:1 chat's look (avatars, "+" send-mode menu, photo/audio bubbles
 * via the same [ImageBubble]/[AudioBubble]/[ImageMessage]/[VoiceMessage] components 1:1 chat
 * uses) with one deliberate difference: no in-thread payments (the group protocol has no
 * shared-wallet/escrow concept, same reason broadcast rooms don't support them - "Pay in Kaspa"
 * isn't in the "+" menu here). Kotlin/Compose port of iOS KaChat's `GroupChatDetailView.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatThreadScreen(
    navController: NavController,
    groupId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: com.kachat.app.viewmodels.WalletViewModel = hiltViewModel(),
    settingsViewModel: com.kachat.app.viewmodels.SettingsViewModel = hiltViewModel()
) {
    val myAddress by walletViewModel.address.collectAsState()
    val myKnsProfile by walletViewModel.knsProfile.collectAsState()
    val groups by chatViewModel.groups.collectAsState()
    val group = groups.firstOrNull { it.groupId == groupId }
    val messages by chatViewModel.getGroupMessages(groupId).collectAsState(initial = emptyList())
    val groupReplyingTo by chatViewModel.groupReplyingTo.collectAsState()
    val contactAvatarsByAddress by chatViewModel.contactAvatarsByAddress.collectAsState()
    val contactAliasesByAddress by chatViewModel.contactAliasesByAddress.collectAsState()
    val pendingPhotoUri by chatViewModel.groupPendingPhotoUri.collectAsState()
    val voiceRecordingState by chatViewModel.groupVoiceRecordingState.collectAsState()
    val showFeeEstimate by settingsViewModel.showFeeEstimate.collectAsState()
    val estimatedFeeRaw by chatViewModel.groupEstimatedFeeSompi.collectAsState()
    val estimatedFee = if (showFeeEstimate) estimatedFeeRaw else null
    val networkFeeRate by chatViewModel.networkFeeRate.collectAsState()
    val feeRateOverride by chatViewModel.feeRateOverride.collectAsState()
    var draft by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showComposerMenu by remember { mutableStateOf(false) }
    var composerMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var showMentionPicker by remember { mutableStateOf(false) }
    val groupMembers = remember(group?.membersJson) { group?.let(::parseGroupMembers) ?: emptyList() }
    val resolveDisplayName: (String) -> String = { address ->
        contactAliasesByAddress[address]?.takeIf { it.isNotBlank() }
            ?: groupMembers.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
            ?: address.takeLast(10)
    }
    var showFeeEditor by remember { mutableStateOf(false) }
    var feeEditorInput by remember { mutableStateOf("") }
    val effectiveRate = feeRateOverride?.toDouble() ?: networkFeeRate
    val openFeeEditor: (Long) -> Unit = { currentFeeSompi ->
        feeEditorInput = "%.8f".format(java.util.Locale.US, currentFeeSompi / 100_000_000.0)
        showFeeEditor = true
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    val jumpToReply: (String) -> Unit = { targetId ->
        val index = messages.indexOfFirst { it.txId == targetId }
        if (index >= 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(index)
                highlightedMessageId = targetId
                delay(1200)
                if (highlightedMessageId == targetId) highlightedMessageId = null
            }
        }
    }

    // Swipe-left-to-reveal-timestamps (iMessage-style) — same implementation as 1:1/broadcast
    // rooms (see ChatThreadScreen in Screens.kt), kept in sync with it.
    val revealOffsetPx = remember { Animatable(0f) }
    val maxRevealOffsetPx = with(LocalDensity.current) { 64.dp.toPx() }

    LaunchedEffect(Unit) {
        chatViewModel.refreshUtxos()
        chatViewModel.markGroupRead(groupId)
    }
    DisposableEffect(groupId) {
        chatViewModel.setActiveGroup(groupId)
        onDispose { chatViewModel.setActiveGroup(null) }
    }
    LaunchedEffect(draft) {
        chatViewModel.setGroupMessageText(draft)
    }

    val micContext = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chatViewModel.startGroupVoiceRecording(groupId)
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) chatViewModel.setGroupPendingPhoto(uri)
    }
    val startVoiceRecordingIfPermitted = {
        if (chatViewModel.voiceRecordingSupported) {
            if (ContextCompat.checkSelfPermission(micContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                chatViewModel.startGroupVoiceRecording(groupId)
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // KNS name/avatar for each member isn't fetched automatically - the roster's own
    // `displayName` is a one-time snapshot from add/join time, so refresh live contact
    // alias/avatar the same way the chat list does on appear (see contactAliasesByAddress/
    // contactAvatarsByAddress above, which this populates).
    LaunchedEffect(group?.groupId) {
        val addresses = group?.let(::parseGroupMembers)?.map { it.address } ?: return@LaunchedEffect
        chatViewModel.refreshKnsProfilesForGroupMembers(addresses)
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        group?.name ?: "Group",
                        color = LocalAppColors.current.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LocalAppColors.current.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("group_chat_info/$groupId") }) {
                        Icon(Icons.Default.Info, contentDescription = "Group Info", tint = LocalAppColors.current.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(LocalAppColors.current.background).imePadding().navigationBarsPadding()) {
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFF3B30),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (pendingPhotoUri != null) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            groupFeePill(estimatedFee, onClick = { openFeeEditor(estimatedFee ?: 0L) })
                            groupPhotoPreviewRow(
                                pendingPhotoUri = pendingPhotoUri,
                                onCancel = { chatViewModel.cancelGroupPendingPhoto() },
                                onSend = { chatViewModel.sendPendingGroupPhoto(groupId) }
                            )
                        }
                    } else if (voiceRecordingState.status == ChatViewModel.VoiceRecordingStatus.RECORDING) {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            groupFeePill(estimatedFee, onClick = { openFeeEditor(estimatedFee ?: 0L) })
                            groupRecordingRow(
                                elapsedMs = voiceRecordingState.elapsedMs,
                                onCancel = { chatViewModel.cancelGroupVoiceRecording() },
                                onSend = { chatViewModel.stopAndSendGroupVoiceRecording(groupId) }
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                        groupReplyingTo?.let { reply ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .background(LocalAppColors.current.surface, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val replyName = if (reply.senderAddress == myAddress) {
                                        "yourself"
                                    } else {
                                        val address = reply.senderAddress
                                        val member = group?.let(::parseGroupMembers)?.firstOrNull { it.address == address }
                                        contactAliasesByAddress[address]
                                            ?: member?.displayName?.takeIf { it.isNotBlank() }
                                            ?: address?.takeLast(10)
                                            ?: "Unknown"
                                    }
                                    Text(
                                        "Replying to $replyName",
                                        color = KaspaTeal,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        GroupMentionCodec.decodeForDisplay(
                                            VoiceMessage.parseOrNull(reply.content)?.let { "🎤 Audio message" }
                                                ?: ImageMessage.parseOrNull(reply.content)?.let { "📷 Photo" }
                                                ?: MessageReply.parseOrNull(reply.content)?.text
                                                ?: reply.content,
                                            groupMembers,
                                            resolveDisplayName
                                        ),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { chatViewModel.cancelGroupReply() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = LocalAppColors.current.textSecondary)
                                }
                            }
                        }
                        if (estimatedFee != null && draft.isNotEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                groupFeePill(
                                    estimatedFee,
                                    modifier = Modifier.align(Alignment.Center),
                                    onClick = { openFeeEditor(estimatedFee ?: 0L) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            TextField(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = { Text("Message", color = Color.DarkGray) },
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = LocalAppColors.current.surface,
                                    unfocusedContainerColor = LocalAppColors.current.surface,
                                    focusedTextColor = LocalAppColors.current.textPrimary,
                                    unfocusedTextColor = LocalAppColors.current.textPrimary,
                                    cursorColor = KaspaTeal,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                maxLines = 5
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (draft.isEmpty()) {
                                Box(
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        composerMenuAnchor = coords.positionInWindow()
                                    }
                                ) {
                                    ChatActionButton(Icons.Default.Add, onClick = { showComposerMenu = true })
                                }
                                if (showComposerMenu) {
                                    CenteredOptionsMenu(onDismissRequest = { showComposerMenu = false }, anchor = composerMenuAnchor) {
                                        PopupMenuRow(Icons.Default.Image, "Photo") {
                                            showComposerMenu = false
                                            photoPickerLauncher.launch("image/*")
                                        }
                                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                        PopupMenuRow(Icons.Default.Mic, "Audio Message") {
                                            showComposerMenu = false
                                            startVoiceRecordingIfPermitted()
                                        }
                                        if (groupMembers.any { it.address != myAddress }) {
                                            HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                                            PopupMenuRow(Icons.Default.Email, "Mention Someone") {
                                                showComposerMenu = false
                                                showMentionPicker = true
                                            }
                                        }
                                    }
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        val text = GroupMentionCodec.encodeForSending(draft.trim(), groupMembers, resolveDisplayName)
                                        if (text.isEmpty()) return@IconButton
                                        draft = ""
                                        errorMessage = null
                                        chatViewModel.sendGroupMessage(text, groupId) { error -> errorMessage = error }
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.txId }) { index, message ->
                    if (index == 0 || !ChatTimeFormat.isSameDay(messages[index - 1].blockTimestamp, message.blockTimestamp)) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Surface(color = LocalAppColors.current.surface, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    ChatTimeFormat.formatDateDivider(message.blockTimestamp),
                                    color = LocalAppColors.current.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    GroupMessageBubble(
                        message = message,
                        group = group,
                        avatarUrl = message.senderAddress?.let { contactAvatarsByAddress[it] },
                        liveAlias = message.senderAddress?.let { contactAliasesByAddress[it] },
                        myAddress = myAddress,
                        myAvatarUrl = myKnsProfile?.avatarUrl,
                        navController = navController,
                        onRetry = { chatViewModel.retryGroupMessage(groupId, message.content) },
                        onReply = { chatViewModel.startGroupReplyTo(message) },
                        onJumpToReply = jumpToReply,
                        isHighlighted = message.txId == highlightedMessageId,
                        resolveMentionName = resolveDisplayName,
                        isMuted = message.senderAddress?.let { chatViewModel.isGroupMemberMuted(groupId, it) } ?: false,
                        onMute = { address -> chatViewModel.muteGroupMember(groupId, address) },
                        onUnmute = { address -> chatViewModel.unmuteGroupMember(groupId, address) },
                        onHide = { address -> chatViewModel.hideGroupMember(groupId, address) },
                        revealOffsetPx = revealOffsetPx,
                        maxRevealOffsetPx = maxRevealOffsetPx
                    )
                }
            }
        }
    }

    if (showMentionPicker) {
        AlertDialog(
            onDismissRequest = { showMentionPicker = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Mention", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    groupMembers.filter { it.address != myAddress }.forEach { member ->
                        Text(
                            resolveDisplayName(member.address),
                            color = LocalAppColors.current.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val separator = if (draft.isEmpty() || draft.endsWith(" ")) "" else " "
                                    draft = "$draft$separator@${resolveDisplayName(member.address)} "
                                    showMentionPicker = false
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMentionPicker = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
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
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
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
                TextButton(onClick = {
                    val kas = feeEditorInput.toDoubleOrNull()
                    val currentFeeSompi = estimatedFeeRaw ?: 0L
                    if (kas != null && kas > 0 && currentFeeSompi > 0 && effectiveRate > 0) {
                        val impliedMass = currentFeeSompi / effectiveRate
                        val desiredFeeSompi = Math.round(kas * 100_000_000.0)
                        chatViewModel.setFeeRateOverride(kotlin.math.ceil(desiredFeeSompi / impliedMass).toLong())
                    } else {
                        chatViewModel.setFeeRateOverride(null)
                    }
                    showFeeEditor = false
                }) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { chatViewModel.setFeeRateOverride(null); showFeeEditor = false }) {
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

/** "fee: N KAS" pill above the composer, matching 1:1/broadcast's identical display - tappable to adjust, same "Adjust Network Fee" dialog as 1:1/broadcast (see [GroupChatThreadScreen]'s showFeeEditor state). */
@Composable
private fun groupFeePill(feeSompi: Long?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (feeSompi == null) return
    Surface(
        color = LocalAppColors.current.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(bottom = 8.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    ) {
        Text(
            text = "fee: ${ChatRepository.formatKas(feeSompi)} KAS",
            color = KaspaTeal,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textDecoration = if (onClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun groupPhotoPreviewRow(pendingPhotoUri: android.net.Uri?, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Delete, contentDescription = "Cancel photo", tint = Color(0xFFFF3B30))
        }
        val thumbnailContext = LocalContext.current
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
        Text("Photo", color = LocalAppColors.current.textPrimary, modifier = Modifier.weight(1f))
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(32.dp).background(KaspaTeal, CircleShape)
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

@Composable
private fun groupRecordingRow(elapsedMs: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Delete, contentDescription = "Cancel recording", tint = Color(0xFFFF3B30))
        }
        Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFFF3B30), modifier = Modifier.size(18.dp))
        Text(
            text = "Recording... ${formatRecordingElapsed(elapsedMs)}",
            color = LocalAppColors.current.textPrimary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onSend,
            modifier = Modifier.size(40.dp).background(KaspaTeal, CircleShape)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessage,
    group: com.kachat.app.models.GroupEntity?,
    avatarUrl: String?,
    liveAlias: String?,
    myAddress: String?,
    myAvatarUrl: String?,
    navController: NavController,
    onRetry: () -> Unit,
    onReply: () -> Unit = {},
    /** Tapping the reply quote (if any) jumps to and highlights the original message. */
    onJumpToReply: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    resolveMentionName: (String) -> String = { it.takeLast(10) },
    isMuted: Boolean = false,
    onMute: (String) -> Unit = {},
    onUnmute: (String) -> Unit = {},
    onHide: (String) -> Unit = {},
    revealOffsetPx: Animatable<Float, AnimationVector1D>,
    maxRevealOffsetPx: Float
) {
    val isSent = message.isOutgoing
    // Prefers the live contact alias (kept current by refreshKnsProfilesForGroupMembers, e.g. a
    // KNS name resolved after the member was added) over the roster's own `displayName`, which is
    // only ever a one-time snapshot taken at add/join time and never updated afterward.
    val senderName = remember(message.senderAddress, group, liveAlias) {
        val address = message.senderAddress ?: return@remember "Unknown"
        if (!liveAlias.isNullOrBlank()) return@remember liveAlias
        val member = group?.let(::parseGroupMembers)?.firstOrNull { it.address == address }
        member?.displayName?.takeIf { it.isNotBlank() } ?: address.takeLast(10)
    }
    val replyContent = remember(message.content) { MessageReply.parseOrNull(message.content) }
    val groupMembersForMentions = remember(group?.membersJson) { group?.let(::parseGroupMembers) ?: emptyList() }
    val displayContent = remember(replyContent, message.content, groupMembersForMentions) {
        GroupMentionCodec.decodeForDisplay(replyContent?.text ?: message.content, groupMembersForMentions, resolveMentionName)
    }
    val replySenderName = remember(replyContent, group, myAddress) {
        val reply = replyContent ?: return@remember null
        if (reply.replyToSender == myAddress) return@remember "You"
        val member = group?.let(::parseGroupMembers)?.firstOrNull { it.address == reply.replyToSender }
        member?.displayName?.takeIf { it.isNotBlank() } ?: reply.replyToSender.takeLast(10)
    }
    val voiceContent = remember(displayContent) { VoiceMessage.parseOrNull(displayContent) }
    val imageContent = remember(displayContent) { if (voiceContent == null) ImageMessage.parseOrNull(displayContent) else null }
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var showMenu by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    val canRetry = isSent && message.deliveryStatus == "failed"
    val highlightColor by animateColorAsState(
        if (isHighlighted) KaspaTeal.copy(alpha = 0.18f) else Color.Transparent,
        label = "messageHighlight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightColor, RoundedCornerShape(12.dp))
    ) {
        Text(
            text = remember(message.blockTimestamp) { ChatTimeFormat.formatMessageTime(message.blockTimestamp) },
            color = LocalAppColors.current.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier
                .align(if (isSent) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 12.dp)
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
            groupAvatarButton(
                address = message.senderAddress,
                avatarUrl = avatarUrl,
                fallbackText = senderName,
                navController = navController,
                isMuted = isMuted,
                onMute = onMute,
                onUnmute = onUnmute,
                onHide = onHide
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isSent) Alignment.End else Alignment.Start,
            modifier = Modifier.onGloballyPositioned { coords ->
                menuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
            }
        ) {
            // Always shown now (own messages say "You"), matching broadcast rooms - previously
            // only incoming messages got a name label at all, so an outgoing message had no
            // sender indicator next to its avatar.
            Text(
                text = if (isSent) "You" else senderName,
                color = KaspaTeal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 2.dp)
            )

            if (replyContent != null) {
                Surface(
                    color = LocalAppColors.current.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .widthIn(max = 240.dp)
                        .clickable { onJumpToReply(replyContent.replyToId) }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            replySenderName ?: replyContent.replyToSender.takeLast(10),
                            color = KaspaTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            GroupMentionCodec.decodeForDisplay(replyContent.replyToPreview, groupMembersForMentions, resolveMentionName),
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when {
                voiceContent != null -> AudioBubble(voiceContent = voiceContent, isSent = isSent, onLongPress = { showMenu = true })
                imageContent != null -> ImageBubble(imageContent = imageContent, isSent = isSent, onLongPress = { showMenu = true }, senderDisplayName = senderName)
                else -> Surface(
                    color = if (isSent) KaspaTeal else LocalAppColors.current.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                ) {
                    Text(
                        text = displayContent,
                        color = if (isSent) Color.Black else LocalAppColors.current.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (isSent) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    when (message.deliveryStatus) {
                        "failed" -> Icon(Icons.Default.Error, contentDescription = "Failed to send", tint = Color(0xFFFF3B30), modifier = Modifier.size(12.dp))
                        "pending" -> Icon(Icons.Default.Schedule, contentDescription = "Sending", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(12.dp))
                        else -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(12.dp))
                    }
                }
            }

            if (showMenu) {
                CenteredOptionsMenu(onDismissRequest = { showMenu = false }, anchor = menuAnchor) {
                    PopupMenuRow(Icons.AutoMirrored.Filled.Reply, "Reply") {
                        onReply()
                        showMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.ContentCopy, "Copy Message") {
                        clipboardManager.setText(AnnotatedString(displayContent))
                        showMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.Tag, "View in Explorer") {
                        uriHandler.openUri(com.kachat.app.models.KaspaExplorer.default.txUrl(message.txId))
                        showMenu = false
                    }
                    if (canRetry) {
                        HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                        PopupMenuRow(Icons.Default.Refresh, "Retry Send") {
                            onRetry()
                            showMenu = false
                        }
                    }
                }
            }
        }

        if (isSent) {
            Spacer(modifier = Modifier.width(8.dp))
            groupAvatarButton(address = myAddress, avatarUrl = myAvatarUrl, fallbackText = "You", navController = navController, isOwnMessage = true)
        }
    }
    }
}

/**
 * Avatar with the same View Profile / Open Chat / Pay in Kaspa / Copy Address menu
 * [BroadcastScreens.kt]'s avatar `CenteredOptionsMenu` offers for a tapped sender - group
 * members are always saved contacts, so this navigates straight to the existing chat/chat_info
 * routes instead of Broadcast's "create a contact for this anonymous sender first" step.
 */
@Composable
private fun groupAvatarButton(
    address: String?,
    avatarUrl: String?,
    fallbackText: String,
    navController: NavController,
    isOwnMessage: Boolean = false,
    isMuted: Boolean = false,
    onMute: (String) -> Unit = {},
    onUnmute: (String) -> Unit = {},
    onHide: (String) -> Unit = {}
) {
    if (address == null) {
        ContactAvatar(imageUrl = avatarUrl, fallbackText = fallbackText, size = 32.dp)
        return
    }
    var showAvatarMenu by remember { mutableStateOf(false) }
    var avatarMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            avatarMenuAnchor = coords.positionInWindow() + Offset(0f, coords.size.height.toFloat())
        }
    ) {
        ContactAvatar(
            imageUrl = avatarUrl,
            fallbackText = fallbackText,
            size = 32.dp,
            modifier = Modifier.clickable { showAvatarMenu = true }
        )
        if (showAvatarMenu) {
            CenteredOptionsMenu(onDismissRequest = { showAvatarMenu = false }, anchor = avatarMenuAnchor) {
                PopupMenuRow(Icons.Default.Person, "View Profile") {
                    navController.navigate("chat_info/$address?fromBroadcast=true")
                    showAvatarMenu = false
                }
                if (!isOwnMessage) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.AutoMirrored.Filled.Chat, "Open Chat") {
                        navController.navigate("chat/$address")
                        showAvatarMenu = false
                    }
                }
                HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                PopupMenuRow(Icons.Default.ContentCopy, "Copy Address") {
                    clipboardManager.setText(AnnotatedString(address))
                    showAvatarMenu = false
                }
                if (!isOwnMessage) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(painterResource(com.kachat.app.R.drawable.ic_kaspa_logo), "Pay in Kaspa", iconTint = Color.Unspecified) {
                        navController.navigate("chat/$address?paymentMode=true")
                        showAvatarMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        if (isMuted) "Unmute User" else "Mute User"
                    ) {
                        if (isMuted) onUnmute(address) else onMute(address)
                        showAvatarMenu = false
                    }
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.08f))
                    PopupMenuRow(Icons.Default.VisibilityOff, "Hide User", labelColor = Color(0xFFFF3B30), iconTint = Color(0xFFFF3B30)) {
                        onHide(address)
                        showAvatarMenu = false
                    }
                }
            }
        }
    }
}

/**
 * Group membership. Kept intentionally minimal, mirroring iOS KaChat's `GroupChatInfoView` —
 * member add/remove UI is a natural next step once this is in front of you. No invite-link/join
 * flow - every member is added directly by the admin (see `GroupRepository`'s class doc for why
 * the invite beacon was removed). Tapping a member opens their normal 1:1 "User Info" screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatInfoScreen(
    navController: NavController,
    groupId: String,
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val groups by chatViewModel.groups.collectAsState()
    val group = groups.firstOrNull { it.groupId == groupId }
    val contactAvatarsByAddress by chatViewModel.contactAvatarsByAddress.collectAsState()
    val contactAliasesByAddress by chatViewModel.contactAliasesByAddress.collectAsState()
    val groupMentionsOnly by chatViewModel.groupMentionsOnly.collectAsState()
    val members = remember(group?.membersJson) {
        group?.let(::parseGroupMembers) ?: emptyList()
    }

    LaunchedEffect(group?.groupId) {
        val addresses = group?.let(::parseGroupMembers)?.map { it.address } ?: return@LaunchedEffect
        chatViewModel.refreshKnsProfilesForGroupMembers(addresses)
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showHiddenUsers by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Group Info", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LocalAppColors.current.textPrimary)
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
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Members (${members.size})",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
            ) {
                members.forEachIndexed { index, member ->
                    val memberLabel = contactAliasesByAddress[member.address]?.takeIf { it.isNotBlank() }
                        ?: member.displayName?.takeIf { it.isNotBlank() }
                        ?: member.address.takeLast(10)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("chat_info/${member.address}?fromBroadcast=true") }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(imageUrl = contactAvatarsByAddress[member.address], fallbackText = memberLabel, size = 32.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = memberLabel, color = LocalAppColors.current.textPrimary)
                        }
                        if (member.isAdmin) {
                            Text("Admin", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
                        }
                    }
                    if (index < members.size - 1) {
                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .clickable { showHiddenUsers = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = LocalAppColors.current.textPrimary)
                Spacer(Modifier.width(12.dp))
                Text("Hidden Users", color = LocalAppColors.current.textPrimary)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only Notify if I'm Mentioned", color = LocalAppColors.current.textPrimary)
                    Text(
                        "Other messages still show up in the chat, just silently.",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = groupId in groupMentionsOnly,
                    onCheckedChange = { chatViewModel.setGroupMentionsOnly(groupId, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = KaspaTeal, checkedTrackColor = KaspaTeal.copy(alpha = 0.5f))
                )
            }

            if (group?.isAdmin == true) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LocalAppColors.current.surface)
                        .clickable {
                            renameText = group.name
                            renameError = null
                            showRename = true
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = LocalAppColors.current.textPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text("Rename Group", color = LocalAppColors.current.textPrimary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LocalAppColors.current.surface)
                    .clickable { showDeleteConfirmation = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF3B30))
                Spacer(Modifier.width(12.dp))
                Text("Delete Group", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Rename Group", color = LocalAppColors.current.textPrimary) },
            text = {
                Column {
                    Text(
                        "Every member will see the new name.",
                        color = LocalAppColors.current.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Group name") },
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
                TextButton(onClick = {
                    val trimmed = renameText.trim()
                    if (trimmed.isEmpty()) return@TextButton
                    showRename = false
                    chatViewModel.renameGroup(groupId, trimmed) { error -> renameError = error }
                }) {
                    Text("Save", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }

    if (renameError != null) {
        AlertDialog(
            onDismissRequest = { renameError = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Couldn't Rename Group", color = LocalAppColors.current.textPrimary) },
            text = { Text(renameError ?: "", color = LocalAppColors.current.textSecondary) },
            confirmButton = {
                TextButton(onClick = { renameError = null }) {
                    Text("OK", color = KaspaTeal)
                }
            }
        )
    }

    if (showHiddenUsers) {
        val hiddenMembers by chatViewModel.groupHiddenMembers.collectAsState()
        val hiddenAddresses = hiddenMembers.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2 && parts[0] == groupId) parts[1] else null
        }
        AlertDialog(
            onDismissRequest = { showHiddenUsers = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Hidden Users", color = LocalAppColors.current.textPrimary) },
            text = {
                if (hiddenAddresses.isEmpty()) {
                    Text("No hidden users in this group.", color = LocalAppColors.current.textSecondary)
                } else {
                    Column {
                        hiddenAddresses.forEach { address ->
                            val label = contactAliasesByAddress[address]?.takeIf { it.isNotBlank() }
                                ?: members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() }
                                ?: address.takeLast(10)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = LocalAppColors.current.textPrimary)
                                TextButton(onClick = { chatViewModel.unhideGroupMember(groupId, address) }) {
                                    Text("Unhide", color = KaspaTeal)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHiddenUsers = false }) {
                    Text("Done", color = KaspaTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete \"${group?.name ?: "this group"}\"", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This removes the group and its messages from this device. This cannot be undone, and other members won't be notified.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteGroupChat(groupId)
                    showDeleteConfirmation = false
                    navController.popBackStack("chats", inclusive = false)
                }) {
                    Text("Delete", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}
