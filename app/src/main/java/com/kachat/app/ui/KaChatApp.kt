package com.kachat.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kachat.app.ui.screens.*
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ChatViewModel

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Chats    : Screen("chats",    "Chats",    Icons.Default.Forum)
    object Profile  : Screen("profile",  "Profile",  Icons.Default.AccountCircle)
}

// All top-level tabs
val bottomNavItems = listOf(
    Screen.Settings,
    Screen.Chats,
    Screen.Profile
)

/**
 * Root composable: bottom nav + NavHost.
 * Wallet onboarding is shown instead when no wallet exists.
 */
@Composable
fun KaChatApp(
    walletViewModel: WalletViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingChannelName: String? = null,
    onPendingChannelHandled: () -> Unit = {}
) {
    val isLoggedIn by walletViewModel.isLoggedIn.collectAsState()
    val mnemonic by walletViewModel.mnemonic.collectAsState()

    if (!isLoggedIn || mnemonic != null) {
        OnboardingScreen(walletViewModel)
    } else {
        MainShell(
            walletViewModel = walletViewModel,
            pendingContactId = pendingContactId,
            onPendingContactHandled = onPendingContactHandled,
            pendingChannelName = pendingChannelName,
            onPendingChannelHandled = onPendingChannelHandled
        )
    }
}

@Composable
fun MainShell(
    walletViewModel: WalletViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    pendingContactId: String? = null,
    onPendingContactHandled: () -> Unit = {},
    pendingChannelName: String? = null,
    onPendingChannelHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // The broadcasts list isn't one of the three real tabs, but it's still a top-level browsing
    // screen (like Chats) rather than a "pushed" detail screen — the bottom nav should stay put
    // and stay functional there, not disappear the way it does for chat/chat_info/etc. A broadcast
    // room itself gets the same treatment (unlike a 1:1 chat thread) — it's still just browsing/
    // participating in a public room, one tap away from Chats/Settings/Profile, not a private
    // conversation you'd want to maximize screen space for.
    val onTabRoute = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.route == dest.route } || dest.route == "broadcasts" || dest.route == "broadcast_channel/{channelName}"
    } == true

    // Tapping a notification for a message/handshake/payment jumps straight to that
    // conversation, matching what a real chat app does — otherwise you'd land on the
    // chat list and have to go find it yourself.
    LaunchedEffect(pendingContactId) {
        if (pendingContactId != null) {
            navController.navigate("chat/$pendingContactId")
            onPendingContactHandled()
        }
    }

    // Same idea for a notify-enabled broadcast channel's new message notification.
    LaunchedEffect(pendingChannelName) {
        if (pendingChannelName != null) {
            navController.navigate("broadcast_channel/$pendingChannelName")
            onPendingChannelHandled()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            // Only show the floating tab bar on the top-level tab destinations —
            // "pushed" detail screens (chat thread, settings sub-screens, etc.) fill
            // the whole screen with their own Scaffold and must not have this
            // overlaid on top of them (it was blocking the chat input entirely).
            if (onTabRoute) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // navigationBarsPadding() first so the 24dp visual margin sits above
                        // the system nav bar (gesture pill or 3-button bar) rather than being
                        // eaten by it — its height isn't the same on every device, so a fixed
                        // dp value alone left the tab bar sitting under/behind it on some phones.
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Row(
                        modifier = Modifier
                            .height(80.dp)
                            .fillMaxWidth()
                            .background(Color(0xFF1C1C1E), RoundedCornerShape(40.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        bottomNavItems.forEach { screen ->
                            val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true

                            Box(
                                modifier = Modifier
                                    .height(64.dp)
                                    .weight(1f)
                                    .clip(RoundedCornerShape(32.dp))
                                    .clickable {
                                        // Tapping back to the graph's start destination (Chats) from a
                                        // "pushed" screen like Broadcasts via navigate()+popUpTo alone is
                                        // silently a no-op in Navigation Compose — popBackStack to the
                                        // route directly first (it's already on the back stack) actually
                                        // pops it. Only fall back to navigate() when the tab isn't already
                                        // present on the stack (first visit to a peer tab).
                                        val poppedToExisting = navController.popBackStack(
                                            route = screen.route,
                                            inclusive = false,
                                            saveState = true
                                        )
                                        if (!poppedToExisting) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.label,
                                        tint = if (selected) KaspaTeal else Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = screen.label,
                                        color = if (selected) KaspaTeal else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Only the bottom-tab destinations (Settings/Chats/Profile) sit inside this
        // shell's floating nav bar and need innerPadding reserved beneath them.
        // "Pushed" detail screens (chat thread, settings sub-screens, etc.) fill the
        // whole screen with their own Scaffold — applying innerPadding to the NavHost
        // as a whole left permanent dead space at the bottom of every one of those,
        // which became a visible gap once a Scaffold there also added imePadding().
        NavHost(
            navController = navController,
            startDestination = Screen.Chats.route
        ) {
            composable(Screen.Settings.route) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    SettingsScreen(navController, walletViewModel)
                }
            }
            composable(Screen.Chats.route) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
                }
            }
            composable(Screen.Profile.route) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ProfileScreen(
                        viewModel = walletViewModel,
                        navController = navController,
                        onNavigateToSeed = { navController.navigate("seed_phrase") }
                    )
                }
            }

            composable("seed_phrase") {
                SeedPhraseScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("edit_kns_profile") {
                EditKnsProfileScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("connection_settings") {
                ConnectionSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("connection_status") {
                ConnectionStatusScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("notification_settings") {
                NotificationSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("hidden_broadcast_users") {
                HiddenBroadcastUsersScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("broadcasts") {
                // Now a tab-like route (see onTabRoute above) with the floating bottom nav
                // visible over it, same bottom-padding treatment as Chats/Settings/Profile so
                // that overlay doesn't cover the last row of content.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    BroadcastListScreen(
                        navController = navController,
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            composable("broadcast_channel/{channelName}") { backStackEntry ->
                val channelName = backStackEntry.arguments?.getString("channelName") ?: return@composable
                // Same bottom-padding treatment as "broadcasts" — the floating tab bar sits below
                // this screen's own message-compose bar rather than overlapping it.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    BroadcastChannelScreen(
                        channelName = channelName,
                        onBack = { navController.popBackStack() },
                        navController = navController
                    )
                }
            }

            composable("create_chat") {
                CreateChatScreen(
                    onBack = { navController.popBackStack() },
                    onChatCreated = { address ->
                        navController.navigate("chat/$address") {
                            popUpTo(Screen.Chats.route)
                        }
                    },
                    chatViewModel = chatViewModel
                )
            }

            // Chat thread — navigated to from ChatsScreen. paymentMode is an optional query-style
            // arg (defaults false) so a "Pay in Kaspa" shortcut elsewhere (e.g. a broadcast
            // sender's avatar menu) can land the user straight in payment-entry mode.
            composable(
                "chat/{contactId}?paymentMode={paymentMode}",
                arguments = listOf(navArgument("paymentMode") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val startInPaymentMode = backStackEntry.arguments?.getBoolean("paymentMode") ?: false
                ChatThreadScreen(
                    navController = navController,
                    contactId = contactId,
                    chatViewModel = chatViewModel,
                    startInPaymentMode = startInPaymentMode
                )
            }

            composable(
                "chat_info/{contactId}?fromBroadcast={fromBroadcast}",
                arguments = listOf(navArgument("fromBroadcast") { type = NavType.BoolType; defaultValue = false })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val fromBroadcast = backStackEntry.arguments?.getBoolean("fromBroadcast") ?: false
                ChatInfoScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() },
                    fromBroadcast = fromBroadcast
                )
            }
        }
    }
}
