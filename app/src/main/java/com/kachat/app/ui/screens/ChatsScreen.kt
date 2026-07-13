package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.MessageReply
import com.kachat.app.util.VoiceMessage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import kotlin.math.roundToInt

/**
 * Chats tab — conversation list.
 * Phase 4 will wire this up to ChatService / ChatViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ChatsScreen(
    navController: NavController, 
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val isRefreshing by chatViewModel.isRefreshing.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Matches on whatever's already shown per row — display name/alias, KNS domain, the raw
    // address (so pasting/typing part of an address you recognize still finds it), and the last
    // message preview text (reply/voice-aware, same as what's rendered) — not just the name.
    val filteredConversations = remember(conversations, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { convo ->
                val contactLabel = convo.contact.alias ?: convo.contact.id.takeLast(8)
                listOfNotNull(
                    convo.contact.alias,
                    convo.contact.knsName,
                    convo.contact.id,
                    messagePreviewText(convo.lastMessage, contactLabel)
                ).any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { chatViewModel.refreshChats() }
    )

    // Balance only updates reactively while this screen is actively composed —
    // refresh it fresh every time you land on/return to the Chats tab, since a
    // send that happened while on a different screen won't otherwise be reflected
    // until something explicitly asks the network for the current balance again.
    LaunchedEffect(Unit) {
        walletViewModel.refreshBalance()
    }

    // Auto-rename any chat to their KNS domain if they have one, every time the chat
    // list appears — matches iOS's fetchKNSDomainsForAllContacts.
    LaunchedEffect(Unit) {
        chatViewModel.refreshKnsNamesForAllContacts()
    }

    // Auto-link/autocreate system contacts, same trigger point — matches iOS's
    // SystemContactsService refresh running on every app foreground.
    LaunchedEffect(Unit) {
        chatViewModel.syncSystemContacts()
    }

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nothing to do either way — notifications just won't show if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color.Black)
                    .statusBarsPadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))

                    TopStatusBar(
                        balance = balance,
                        onStatusClick = { navController.navigate("connection_status") },
                        onAddClick = { navController.navigate("create_chat") },
                        dotColorHex = dotColorHex,
                        showEditButton = conversations.isNotEmpty(),
                        isEditing = isSelectionMode,
                        onEditClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) selectedContactIds = emptySet()
                        },
                        selectAllLabel = if (selectedContactIds.size == filteredConversations.size && filteredConversations.isNotEmpty()) {
                            "Deselect All"
                        } else {
                            "Select All"
                        },
                        onSelectAllClick = {
                            selectedContactIds = if (selectedContactIds.size == filteredConversations.size) {
                                emptySet()
                            } else {
                                filteredConversations.map { it.contact.id }.toSet()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Search Bar
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        placeholder = { Text("Search chats", color = Color.Gray) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1C1C1E),
                            unfocusedContainerColor = Color(0xFF1C1C1E),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = KaspaTeal,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Outside the padded inner Column above — sharing the same unpadded layout
                // context as ConversationRow below (in the un-padded LazyColumn) so this row's
                // avatar size/position and divider match the real chat rows exactly, pixel for
                // pixel, rather than sitting inset an extra 16dp from the wrapping Column's own
                // horizontal padding.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("broadcasts") }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1C1C1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RssFeed,
                            contentDescription = null,
                            tint = KaspaTeal,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Broadcasts",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = Color.DarkGray.copy(alpha = 0.5f)
                )
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                Column(modifier = Modifier.background(Color.Black).navigationBarsPadding()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                chatViewModel.markContactsAsRead(selectedContactIds)
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                            },
                            enabled = selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailRead, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Mark as Read", color = Color.White, fontSize = 13.sp)
                        }
                        Button(
                            onClick = {
                                chatViewModel.markContactsAsUnread(selectedContactIds)
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                            },
                            enabled = selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailUnread, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Mark as Unread", color = Color.White, fontSize = 13.sp)
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
                .pullRefresh(pullRefreshState)
        ) {
            if (conversations.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_kachat_logo),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        alpha = 0.5f // Dimmed logo like in screenshot
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "No Conversations Yet",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Start a new chat by adding a contact",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { navController.navigate("create_chat") },
                        colors = ButtonDefaults.buttonColors(containerColor = KaspaTeal),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp).padding(horizontal = 24.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PersonAddAlt1,
                                contentDescription = null,
                                tint = Color.Black
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Add Contact",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else if (filteredConversations.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
                ) {
                    Text(
                        text = "No Matching Chats",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No chats match \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Swiping never deletes on its own — it only stages a confirmation below, since
                // unlike the old archive (reversible, one tap to undo) a delete permanently wipes
                // local message history and a mis-swipe would be unrecoverable.
                var contactToDelete by remember { mutableStateOf<String?>(null) }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredConversations, key = { it.contact.id }) { convo ->
                        SwipeActionRow(
                            enabled = !isSelectionMode,
                            leadingIcon = if (convo.unreadCount > 0) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
                            leadingLabel = if (convo.unreadCount > 0) "Read" else "Unread",
                            leadingColor = KaspaTeal,
                            onLeadingClick = {
                                if (convo.unreadCount > 0) {
                                    chatViewModel.markAsRead(convo.contact.id)
                                } else {
                                    chatViewModel.markAsUnread(convo.contact.id)
                                }
                            },
                            trailingIcon = Icons.Default.Delete,
                            trailingLabel = "Delete",
                            trailingColor = Color(0xFFFF3B30),
                            onTrailingClick = { contactToDelete = convo.contact.id }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().background(Color.Black)
                            ) {
                                if (isSelectionMode) {
                                    Icon(
                                        imageVector = if (convo.contact.id in selectedContactIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = "Select chat",
                                        tint = if (convo.contact.id in selectedContactIds) KaspaTeal else Color.Gray,
                                        modifier = Modifier.padding(start = 16.dp).size(22.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    ConversationRow(convo) {
                                        if (isSelectionMode) {
                                            selectedContactIds = if (convo.contact.id in selectedContactIds) {
                                                selectedContactIds - convo.contact.id
                                            } else {
                                                selectedContactIds + convo.contact.id
                                            }
                                        } else {
                                            navController.navigate("chat/${convo.contact.id}")
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 72.dp),
                                        color = Color.DarkGray.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }

                contactToDelete?.let { contactId ->
                    val label = filteredConversations.find { it.contact.id == contactId }
                        ?.contact?.let { it.alias ?: it.id.takeLast(8) } ?: "this chat"
                    AlertDialog(
                        onDismissRequest = { contactToDelete = null },
                        containerColor = Color(0xFF1C1C1E),
                        title = { Text("Delete Chat with $label", color = Color.White) },
                        text = {
                            Text(
                                "This permanently deletes every message with them on this device. This cannot be undone — messaging them again starts a brand new conversation.",
                                color = Color.Gray
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                chatViewModel.deleteChat(contactId)
                                contactToDelete = null
                            }) {
                                Text("Delete", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { contactToDelete = null }) {
                                Text("Cancel", color = Color.Gray)
                            }
                        }
                    )
                }
            }
            
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = Color(0xFF1C1C1E),
                contentColor = KaspaTeal
            )
        }
    }
}

/**
 * A one-line preview of a message body, for the chat list and anywhere else a raw body would
 * otherwise leak the audio-message or reply JSON blob to the user. [contactLabel] names the other
 * party, used when they're the one who sent a reply ("Alice replied to ..." vs "You replied to ...").
 */
private fun messagePreviewText(message: MessageEntity?, contactLabel: String): String? {
    val body = message?.plaintextBody ?: return null
    val replyContent = MessageReply.parseOrNull(body)
    if (replyContent != null) {
        val who = if (message.direction == "sent") "You" else contactLabel
        return "$who replied to \"${replyContent.replyToPreview}\""
    }
    if (VoiceMessage.parseOrNull(body) != null) return "🎤 Audio message"
    if (ImageMessage.parseOrNull(body) != null) return "📷 Photo"
    return body
}

/**
 * A row that reveals a leading and/or trailing action button as you drag it open — like iOS's
 * `.swipeActions`, the drag only *reveals* the button; the action itself only runs when you tap
 * the revealed button, never just from completing the drag motion (unlike Material3's
 * `SwipeToDismissBox`, whose `confirmValueChange` fires as soon as the swipe crosses its
 * threshold, with no separate tap step).
 */
@Composable
private fun SwipeActionRow(
    enabled: Boolean = true,
    leadingIcon: ImageVector,
    leadingLabel: String,
    leadingColor: Color,
    onLeadingClick: () -> Unit,
    trailingIcon: ImageVector,
    trailingLabel: String,
    trailingColor: Color,
    onTrailingClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionWidthDp = 88.dp
    val actionWidthPx = with(density) { actionWidthDp.toPx() }
    var offsetX by remember { mutableStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-actionWidthPx, actionWidthPx)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .width(actionWidthDp)
                    .fillMaxHeight()
                    .background(leadingColor)
                    .clickable(enabled = offsetX > 1f) {
                        onLeadingClick()
                        offsetX = 0f
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(leadingIcon, contentDescription = leadingLabel, tint = Color.Black)
                    Text(leadingLabel, color = Color.Black, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(actionWidthDp)
                    .fillMaxHeight()
                    .background(trailingColor)
                    .clickable(enabled = offsetX < -1f) {
                        onTrailingClick()
                        offsetX = 0f
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(trailingIcon, contentDescription = trailingLabel, tint = Color.White)
                    Text(trailingLabel, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .then(
                    if (enabled) {
                        Modifier.draggable(
                            state = draggableState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = {
                                val target = when {
                                    offsetX > actionWidthPx / 2 -> actionWidthPx
                                    offsetX < -actionWidthPx / 2 -> -actionWidthPx
                                    else -> 0f
                                }
                                animate(initialValue = offsetX, targetValue = target) { value, _ -> offsetX = value }
                            }
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
            // While revealed, tapping the row itself closes it rather than firing its normal
            // click/select action underneath — matches the reference apps' swipe-action rows.
            if (kotlin.math.abs(offsetX) > 1f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            offsetX = 0f
                        }
                )
            }
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) offsetX = 0f
    }
}

@Composable
private fun ConversationRow(convo: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            imageUrl = convo.contact.knsAvatarUrl,
            fallbackText = convo.contact.alias ?: convo.contact.id.takeLast(8),
            size = 48.dp
        )

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            val contactLabel = convo.contact.alias ?: convo.contact.id.takeLast(8)
            Text(
                text = contactLabel,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    convo.contact.conversationStatus == "pending" -> "🤝 ${messagePreviewText(convo.lastMessage, contactLabel) ?: "Wants to connect"}"
                    else -> messagePreviewText(convo.lastMessage, contactLabel) ?: "No messages yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (convo.contact.conversationStatus == "pending") KaspaTeal else Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (convo.unreadCount > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                    .background(KaspaTeal, CircleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = convo.unreadCount.toString(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Contact avatar — shows the KNS profile photo when available, else falls back to initials, everywhere a contact is shown. */
@Composable
fun ContactAvatar(
    imageUrl: String?,
    fallbackText: String,
    size: Dp,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF1C1C1E),
    fontSize: TextUnit = 16.sp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { AvatarInitials(fallbackText, fontSize) },
                error = { AvatarInitials(fallbackText, fontSize) }
            )
        } else {
            AvatarInitials(fallbackText, fontSize)
        }
    }
}

@Composable
private fun AvatarInitials(text: String, fontSize: TextUnit) {
    Text(
        text = text.take(2).uppercase(),
        color = KaspaTeal,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize
    )
}

// Placeholder data class — replace with Room entity in Phase 4
data class ConversationPreview(
    val contactId: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String
)
