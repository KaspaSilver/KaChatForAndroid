package com.kachat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kachat.app.R
import com.kachat.app.ui.theme.KaspaBlue
import com.kachat.app.ui.theme.KaspaSubtext
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ConnectionViewModel
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.models.Conversation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState

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
    val balance by walletViewModel.balance.collectAsState()
    val connStatus by connectionViewModel.status.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val isRefreshing by chatViewModel.isRefreshing.collectAsState()
    
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { chatViewModel.refreshChats() }
    )

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

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Color(0xFF1C1C1E), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Search chats",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
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
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.contact.id }) { convo ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                if (it == SwipeToDismissBoxValue.EndToStart) {
                                    chatViewModel.archiveChat(convo.contact.id)
                                    true
                                } else false
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                val color = when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.EndToStart -> Color(0xFF1C1C1E)
                                    else -> Color.Transparent
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(color)
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    // Only show icon if we are actually swiping
                                    if (dismissState.progress > 0.1f && dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Default.Archive,
                                                contentDescription = "Archive",
                                                tint = Color.White
                                            )
                                            Text("Archive", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        ) {
                            Column(modifier = Modifier.background(Color.Black)) {
                                ConversationRow(convo) {
                                    navController.navigate("chat/${convo.contact.id}")
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

@Composable
private fun ConversationRow(convo: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar placeholder
        Surface(
            modifier = Modifier.size(48.dp).clip(CircleShape),
            color = Color(0xFF1C1C1E)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = (convo.contact.alias ?: convo.contact.id.takeLast(8)).take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = KaspaTeal,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = convo.contact.alias ?: convo.contact.id.takeLast(8),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = convo.lastMessage?.plaintextBody ?: "No messages yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Placeholder data class — replace with Room entity in Phase 4
data class ConversationPreview(
    val contactId: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String
)
