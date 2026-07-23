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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
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
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.models.Conversation
import com.kachat.app.models.GroupMember
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
import com.kachat.app.repository.GroupConversation
import com.kachat.app.repository.GroupMessage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Chats tab — conversation list.
 * Phase 4 will wire this up to ChatService / ChatViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    navController: NavController, 
    walletViewModel: WalletViewModel = hiltViewModel(),
    connectionViewModel: ConnectionViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val balance by walletViewModel.fullBalance.collectAsState()
    val dotColorHex by connectionViewModel.dotColorHex.collectAsState()
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val groupConversations by chatViewModel.groupConversations.collectAsState()
    val isRefreshing by chatViewModel.isRefreshing.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    // Selection is scoped to whichever tab it was started on - switching tabs mid-select would
    // either strand a selection the visible list can't act on, or blend Chats and Group Chats
    // selections together, so the other tab is blocked while editing (matches iOS).
    val isOnGroupsTab = pagerState.currentPage == 1
    val tabCoroutineScope = rememberCoroutineScope()

    // Matches on whatever's already shown per row — display name/alias, KNS domain, the raw
    // address (so pasting/typing part of an address you recognize still finds it), and the last
    // message preview text (reply/voice-aware, same as what's rendered) — not just the name.
    val filteredConversations = remember(conversations, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { convo ->
                val contactLabel = convo.contact.alias ?: com.kachat.app.util.KaspaAddress.shortDisplay(convo.contact.id)
                listOfNotNull(
                    convo.contact.alias,
                    convo.contact.knsName,
                    convo.contact.id,
                    messagePreviewText(convo.lastMessage, contactLabel)
                ).any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    // Mirrors filteredConversations above for the Group Chats tab: group name, each member's
    // display-name-or-address, and the last message preview text.
    val filteredGroupConversations = remember(groupConversations, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            groupConversations
        } else {
            groupConversations.filter { convo ->
                val members = parseGroupMembers(convo.group)
                listOfNotNull(convo.group.name, groupMessagePreviewText(convo.lastMessage, members))
                    .any { it.contains(query, ignoreCase = true) } ||
                    members.any { member ->
                        (member.displayName?.contains(query, ignoreCase = true) == true) ||
                            member.address.contains(query, ignoreCase = true)
                    }
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
        containerColor = LocalAppColors.current.background,
        topBar = {
            Column(
                modifier = Modifier
                    .background(LocalAppColors.current.background)
                    .statusBarsPadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))

                    TopStatusBar(
                        balance = balance,
                        onStatusClick = { navController.navigate("connection_status") },
                        dotColorHex = dotColorHex,
                        showAddButton = false,
                        showEditButton = if (isOnGroupsTab) groupConversations.isNotEmpty() else conversations.isNotEmpty(),
                        isEditing = isSelectionMode,
                        onEditClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) {
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                            }
                        },
                        selectAllLabel = if (isOnGroupsTab) {
                            if (selectedGroupIds.size == filteredGroupConversations.size && filteredGroupConversations.isNotEmpty()) "Deselect All" else "Select All"
                        } else {
                            if (selectedContactIds.size == filteredConversations.size && filteredConversations.isNotEmpty()) "Deselect All" else "Select All"
                        },
                        onSelectAllClick = {
                            if (isOnGroupsTab) {
                                selectedGroupIds = if (selectedGroupIds.size == filteredGroupConversations.size) {
                                    emptySet()
                                } else {
                                    filteredGroupConversations.map { it.group.groupId }.toSet()
                                }
                            } else {
                                selectedContactIds = if (selectedContactIds.size == filteredConversations.size) {
                                    emptySet()
                                } else {
                                    filteredConversations.map { it.contact.id }.toSet()
                                }
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
                        placeholder = { Text("Search chats", color = LocalAppColors.current.textSecondary) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = LocalAppColors.current.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
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
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val chatsUnreadCount = conversations.sumOf { it.unreadCount }
                val groupsUnreadCount = groupConversations.sumOf { it.unreadCount }
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = LocalAppColors.current.background,
                    contentColor = KaspaTeal
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            if (!isSelectionMode) tabCoroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        text = {
                            TabBadge(count = chatsUnreadCount) {
                                Text(
                                    "Chats",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelectionMode && isOnGroupsTab) LocalContentColor.current.copy(alpha = 0.25f) else LocalContentColor.current
                                )
                            }
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            if (!isSelectionMode) tabCoroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        text = {
                            TabBadge(count = groupsUnreadCount) {
                                Text(
                                    "Group Chats",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelectionMode && !isOnGroupsTab) LocalContentColor.current.copy(alpha = 0.25f) else LocalContentColor.current
                                )
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            // Same style/placement as Portfolio's add-transaction FAB (see PortfolioScreen.kt) —
            // sits above the app-wide floating tab bar for free, since this screen's own content
            // region is already reserved above it before this Scaffold is even composed.
            FloatingActionButton(
                onClick = { navController.navigate("create_chat") },
                containerColor = KaspaTeal,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.PersonAddAlt1, "Create chat", modifier = Modifier.size(28.dp))
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                Column(modifier = Modifier.background(LocalAppColors.current.background).navigationBarsPadding()) {
                    HorizontalDivider(color = LocalAppColors.current.textPrimary.copy(alpha = 0.1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isOnGroupsTab) {
                                    chatViewModel.markGroupsAsRead(selectedGroupIds)
                                } else {
                                    chatViewModel.markContactsAsRead(selectedContactIds)
                                }
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                            },
                            enabled = if (isOnGroupsTab) selectedGroupIds.isNotEmpty() else selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailRead, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Mark as Read", color = LocalAppColors.current.textPrimary, fontSize = 13.sp)
                        }
                        Button(
                            onClick = {
                                if (isOnGroupsTab) {
                                    chatViewModel.markGroupsAsUnread(selectedGroupIds)
                                } else {
                                    chatViewModel.markContactsAsUnread(selectedContactIds)
                                }
                                isSelectionMode = false
                                selectedContactIds = emptySet()
                                selectedGroupIds = emptySet()
                            },
                            enabled = if (isOnGroupsTab) selectedGroupIds.isNotEmpty() else selectedContactIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.surfaceVariant),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.MarkEmailUnread, null, tint = KaspaTeal, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Mark as Unread", color = LocalAppColors.current.textPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        // Tap-only (userScrollEnabled = false), not swipeable - a draggable pager here would
        // fight the row-level swipe-to-delete/mark-read gestures on both the Chats and Group
        // Chats lists. Tab taps still drive it via pagerState.animateScrollToPage above.
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
        when (page) {
            1 -> GroupListBody(
                navController = navController,
                groupConversations = filteredGroupConversations,
                hasAnyGroups = groupConversations.isNotEmpty(),
                searchQuery = searchQuery,
                onDeleteGroup = { chatViewModel.deleteGroupChat(it) },
                isSelectionMode = isSelectionMode,
                selectedGroupIds = selectedGroupIds,
                onToggleGroupSelected = { groupId ->
                    selectedGroupIds = if (groupId in selectedGroupIds) {
                        selectedGroupIds - groupId
                    } else {
                        selectedGroupIds + groupId
                    }
                },
                onMarkGroupRead = { chatViewModel.markGroupsAsRead(listOf(it)) },
                onMarkGroupUnread = { chatViewModel.markGroupsAsUnread(listOf(it)) }
            )
            else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullRefreshState)
        ) {
            if (conversations.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Shown here too (not just in the real conversation list below) so a
                    // brand-new account with zero 1:1 chats doesn't lose access to Broadcasts
                    // until their first conversation exists.
                    if ("broadcasts" !in hiddenTabs) {
                        BroadcastsEntryRow(onClick = { navController.navigate("broadcasts") })
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 100.dp)
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
                                color = LocalAppColors.current.textPrimary
                            )
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Start a new chat by adding a contact",
                            style = MaterialTheme.typography.bodyLarge,
                            color = LocalAppColors.current.textSecondary,
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
                            color = LocalAppColors.current.textPrimary
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No chats match \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalAppColors.current.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Swiping never deletes on its own — it only stages a confirmation below, since
                // unlike the old archive (reversible, one tap to undo) a delete permanently wipes
                // local message history and a mis-swipe would be unrecoverable.
                var contactToDelete by remember { mutableStateOf<String?>(null) }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Restored to its original placement: a row inside the Chats list itself (so
                    // it reads as "just another chat"), not a standalone element above the tabs -
                    // only shown while not searching, matching iOS.
                    if ("broadcasts" !in hiddenTabs && searchQuery.isBlank()) {
                        item {
                            BroadcastsEntryRow(onClick = { navController.navigate("broadcasts") })
                        }
                    }
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
                                modifier = Modifier.fillMaxWidth().background(LocalAppColors.current.background)
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
                    item {
                        val chatCount = conversations.size
                        Text(
                            text = "$chatCount ${if (chatCount == 1) "chat" else "chats"}",
                            color = LocalAppColors.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                }

                contactToDelete?.let { contactId ->
                    val label = filteredConversations.find { it.contact.id == contactId }
                        ?.contact?.let { it.alias ?: com.kachat.app.util.KaspaAddress.shortDisplay(it.id) } ?: "this chat"
                    AlertDialog(
                        onDismissRequest = { contactToDelete = null },
                        containerColor = LocalAppColors.current.surface,
                        title = { Text("Delete Chat with $label", color = LocalAppColors.current.textPrimary) },
                        text = {
                            Text(
                                "This permanently deletes every message with them on this device. This cannot be undone; messaging them again starts a brand new conversation.",
                                color = LocalAppColors.current.textSecondary
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
                                Text("Cancel", color = LocalAppColors.current.textSecondary)
                            }
                        }
                    )
                }

            }

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = LocalAppColors.current.surface,
                contentColor = KaspaTeal
            )

        }
        }
        }
    }
}

/** Small unread-count badge for the Chats/Group Chats tab labels - hidden entirely when count is 0. */
@Composable
private fun TabBadge(count: Int, content: @Composable () -> Unit) {
    BadgedBox(
        badge = {
            if (count > 0) {
                Badge(containerColor = Color(0xFFFF3B30)) {
                    Text(if (count > 99) "99+" else count.toString())
                }
            }
        }
    ) {
        content()
    }
}

/** The "Broadcasts" row inside the Chats list - shown both in the real conversation list and the empty state, so it's reachable regardless of whether the user has any 1:1 chats yet. */
@Composable
private fun BroadcastsEntryRow(onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(LocalAppColors.current.surface),
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
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 72.dp),
            color = Color.DarkGray.copy(alpha = 0.5f)
        )
    }
}

/**
 * Group Chats tab content embedded in `ChatsScreen`'s pager - list of joined groups with their
 * latest message, matching the 1:1 Chats page's row/footer/empty-state shape. Owns its own
 * delete-confirmation dialog - previously nested inside the 1:1 conversation list's `else`
 * branch, which meant it silently couldn't render whenever there were zero 1:1 chats; now
 * self-contained regardless of what the Chats page shows.
 */
@Composable
fun GroupListBody(
    navController: NavController,
    groupConversations: List<GroupConversation>,
    /** Whether the account has any groups at all, before search filtering - distinguishes a
     *  genuinely empty account from a search that just matched nothing. */
    hasAnyGroups: Boolean = groupConversations.isNotEmpty(),
    searchQuery: String = "",
    onDeleteGroup: (String) -> Unit,
    isSelectionMode: Boolean = false,
    selectedGroupIds: Set<String> = emptySet(),
    onToggleGroupSelected: (String) -> Unit = {},
    onMarkGroupRead: (String) -> Unit = {},
    onMarkGroupUnread: (String) -> Unit = {}
) {
    var groupToDelete by remember { mutableStateOf<String?>(null) }

    if (groupConversations.isEmpty() && hasAnyGroups && searchQuery.isNotBlank()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
        ) {
            Text(
                text = "No Matching Groups",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary
                )
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No groups match \"$searchQuery\"",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalAppColors.current.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    } else if (groupConversations.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(bottom = 100.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                tint = LocalAppColors.current.textSecondary,
                modifier = Modifier.size(60.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "No Group Chats Yet",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary
                )
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Start a group from the add-chat button",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalAppColors.current.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(groupConversations, key = { it.group.groupId }) { convo ->
                SwipeActionRow(
                    enabled = !isSelectionMode,
                    leadingIcon = if (convo.unreadCount > 0) Icons.Default.MarkEmailRead else Icons.Default.MarkEmailUnread,
                    leadingLabel = if (convo.unreadCount > 0) "Read" else "Unread",
                    leadingColor = KaspaTeal,
                    onLeadingClick = {
                        if (convo.unreadCount > 0) {
                            onMarkGroupRead(convo.group.groupId)
                        } else {
                            onMarkGroupUnread(convo.group.groupId)
                        }
                    },
                    trailingIcon = Icons.Default.Delete,
                    trailingLabel = "Delete",
                    trailingColor = Color(0xFFFF3B30),
                    onTrailingClick = { groupToDelete = convo.group.groupId }
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LocalAppColors.current.background)
                                .clickable {
                                    if (isSelectionMode) {
                                        onToggleGroupSelected(convo.group.groupId)
                                    } else {
                                        navController.navigate("group_chat/${convo.group.groupId}")
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelectionMode) {
                                Icon(
                                    imageVector = if (convo.group.groupId in selectedGroupIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = "Select group",
                                    tint = if (convo.group.groupId in selectedGroupIds) KaspaTeal else Color.Gray,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(LocalAppColors.current.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = KaspaTeal,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = convo.group.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LocalAppColors.current.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = groupMessagePreviewText(convo.lastMessage, parseGroupMembers(convo.group)) ?: "No messages yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray,
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
                                        color = LocalAppColors.current.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp),
                            color = Color.DarkGray.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            item {
                val groupCount = groupConversations.size
                Text(
                    text = "$groupCount ${if (groupCount == 1) "group" else "groups"}",
                    color = LocalAppColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
            }
        }
    }

    groupToDelete?.let { groupId ->
        val groupName = groupConversations.firstOrNull { it.group.groupId == groupId }?.group?.name ?: "this group"
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Delete \"$groupName\"", color = LocalAppColors.current.textPrimary) },
            text = {
                Text(
                    "This removes the group and its messages from this device. This cannot be undone, and other members won't be notified.",
                    color = LocalAppColors.current.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(groupId)
                    groupToDelete = null
                }) {
                    Text("Delete", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("Cancel", color = LocalAppColors.current.textSecondary)
                }
            }
        )
    }
}

/** Mirrors [messagePreviewText] for group messages. Resolves `@{address}` mentions back to a
 *  display name using the roster's own (possibly stale) `displayName` snapshot rather than a
 *  live contact/KNS lookup - not worth threading that all the way down for a one-line preview. */
private fun groupMessagePreviewText(message: GroupMessage?, members: List<GroupMember> = emptyList()): String? {
    val body = message?.content ?: return null
    val resolve: (String) -> String = { address ->
        members.firstOrNull { it.address == address }?.displayName?.takeIf { it.isNotBlank() } ?: address.takeLast(10)
    }
    val replyContent = MessageReply.parseOrNull(body)
    if (replyContent != null) {
        return "Replied to \"${GroupMentionCodec.decodeForDisplay(replyContent.replyToPreview, members, resolve)}\""
    }
    if (VoiceMessage.parseOrNull(body) != null) return "🎤 Audio message"
    if (ImageMessage.parseOrNull(body) != null) return "📷 Photo"
    return GroupMentionCodec.decodeForDisplay(body, members, resolve)
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
    if (com.kachat.app.util.ChessMessage.parseOrNull(body) != null) return "♟️ Chess game"
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
fun SwipeActionRow(
    enabled: Boolean = true,
    // Matches the content's own corner radius so the leading/trailing action color underneath
    // gets clipped to the same rounded shape — otherwise its sharp corners peek out past the
    // content's rounded ones even at rest (offsetX == 0), showing as a stray sliver of color.
    cornerRadius: Dp = 0.dp,
    leadingIcon: ImageVector? = null,
    leadingLabel: String? = null,
    leadingColor: Color = Color.Transparent,
    onLeadingClick: () -> Unit = {},
    trailingIcon: ImageVector,
    trailingLabel: String,
    trailingColor: Color,
    onTrailingClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionWidthDp = 88.dp
    val actionWidthPx = with(density) { actionWidthDp.toPx() }
    val hasLeading = leadingIcon != null
    var offsetX by remember { mutableStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-actionWidthPx, if (hasLeading) actionWidthPx else 0f)
    }

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(cornerRadius))) {
        Row(modifier = Modifier.matchParentSize()) {
            if (hasLeading) {
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
                        Icon(leadingIcon!!, contentDescription = leadingLabel, tint = Color.Black)
                        Text(leadingLabel ?: "", color = Color.Black, style = MaterialTheme.typography.bodySmall)
                    }
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
                    Icon(trailingIcon, contentDescription = trailingLabel, tint = LocalAppColors.current.textPrimary)
                    Text(trailingLabel, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
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
            val contactLabel = convo.contact.alias ?: com.kachat.app.util.KaspaAddress.shortDisplay(convo.contact.id)
            Text(
                text = contactLabel,
                style = MaterialTheme.typography.titleMedium,
                color = LocalAppColors.current.textPrimary,
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
                    color = LocalAppColors.current.textPrimary,
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
    backgroundColor: Color = LocalAppColors.current.surface,
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
