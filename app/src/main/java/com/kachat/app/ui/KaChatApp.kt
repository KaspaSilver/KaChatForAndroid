package com.kachat.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import kotlin.math.roundToInt

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Settings  : Screen("settings",  "Settings",  Icons.Default.Settings)
    object Chats     : Screen("chats",     "Chats",     Icons.Default.Forum)
    object Portfolio : Screen("portfolio", "Portfolio", Icons.Default.PieChart)
    object Profile   : Screen("profile",   "Profile",   Icons.Default.AccountCircle)
    object Swap      : Screen("swap",      "Swap",      Icons.Default.SwapHoriz)
}

// All top-level tabs, in the app's default order.
val bottomNavItems = listOf(
    Screen.Settings,
    Screen.Portfolio,
    Screen.Chats,
    Screen.Swap,
    Screen.Profile
)

/**
 * Maps persisted route strings (from AppSettingsRepository.tabOrder) back to [Screen] objects,
 * in that order. Any route no longer recognized (e.g. a tab removed in a future update) is
 * dropped, and any [Screen] missing from the persisted list (e.g. a tab added since the user
 * last reordered) is appended at the end — so neither a stale persisted value nor an app update
 * can leave a tab permanently missing or crash on an unknown route.
 */
private fun resolveTabOrder(routes: List<String>): List<Screen> {
    val byRoute = bottomNavItems.associateBy { it.route }
    val resolved = routes.mapNotNull { byRoute[it] }
    val missing = bottomNavItems.filter { it !in resolved }
    return resolved + missing
}

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

@OptIn(ExperimentalLayoutApi::class)
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

    // Press-and-hold a tab, then drag to reorder — the persisted order (WalletViewModel.tabOrder)
    // is only written on drag end; localTabOrder is the live, possibly-mid-drag copy the Row
    // actually renders from, reconciled back to the persisted order whenever it changes and no
    // drag is in progress (so a fresh install / another device's order still applies normally).
    val persistedTabOrder by walletViewModel.tabOrder.collectAsState()
    var draggedScreen by remember { mutableStateOf<Screen?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var tabItemWidthPx by remember { mutableStateOf(0f) }
    var localTabOrder by remember { mutableStateOf(resolveTabOrder(persistedTabOrder)) }
    LaunchedEffect(persistedTabOrder) {
        if (draggedScreen == null) {
            localTabOrder = resolveTabOrder(persistedTabOrder)
        }
    }

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
                        localTabOrder.forEach { screen ->
                            // Keyed by route (not position) so a tab's drag gesture/animation
                            // state stays attached to the same logical tab as the list reorders,
                            // rather than to whichever position happens to render it.
                            key(screen.route) {
                                val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                                val isDragging = draggedScreen == screen

                                Box(
                                    modifier = Modifier
                                        .height(64.dp)
                                        .weight(1f)
                                        .offset { IntOffset((if (isDragging) dragOffsetX else 0f).roundToInt(), 0) }
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .onSizeChanged { tabItemWidthPx = it.width.toFloat() }
                                        .clip(RoundedCornerShape(32.dp))
                                        // Long-press then drag to reorder. Keyed on the route (stable
                                        // across reorders) so this gesture detector isn't restarted
                                        // mid-drag when localTabOrder itself changes — every state read
                                        // inside the callbacks below is a live Compose State read, so
                                        // there's no stale-closure risk from not re-keying on the list.
                                        .pointerInput(screen.route) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggedScreen = screen
                                                    dragOffsetX = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetX += dragAmount.x
                                                    if (tabItemWidthPx <= 0f) return@detectDragGesturesAfterLongPress
                                                    val current = localTabOrder.indexOf(screen)
                                                    val slotShift = (dragOffsetX / tabItemWidthPx).roundToInt()
                                                    if (slotShift == 0) return@detectDragGesturesAfterLongPress
                                                    val target = (current + slotShift).coerceIn(0, localTabOrder.lastIndex)
                                                    if (target != current) {
                                                        localTabOrder = localTabOrder.toMutableList().apply {
                                                            add(target, removeAt(current))
                                                        }
                                                        // Keeps the item moving continuously under the
                                                        // finger instead of jumping after each slot swap.
                                                        dragOffsetX -= slotShift * tabItemWidthPx
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggedScreen = null
                                                    dragOffsetX = 0f
                                                    walletViewModel.setTabOrder(localTabOrder.map { it.route })
                                                },
                                                onDragCancel = {
                                                    draggedScreen = null
                                                    dragOffsetX = 0f
                                                }
                                            )
                                        }
                                        .clickable {
                                            // Already there — do nothing. Without this guard, re-tapping the
                                            // active tab still ran the popBackStack/navigate logic below, which
                                            // for a tab with nothing "pushed" above it falls through to
                                            // navigate() and lands back on the graph's start destination
                                            // (Chats) instead of staying put.
                                            if (selected) return@clickable

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
            startDestination = Screen.Chats.route,
            // NavHost's own default is a 700ms crossfade — noticeably sluggish for something
            // that happens on every single tab switch/screen push. A short, snappy fade reads
            // as instant without the jarring hard-cut of no animation at all.
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
            // The four bottom-tab destinations get an instant swap, overriding the NavHost-level
            // 150ms fade above just for these routes — that fade is a good fit for pushed detail
            // screens, but a tab bar's own instant selected-tint feedback reads as sluggish when
            // paired with any fade on the content behind it, however short.
            composable(
                Screen.Settings.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    SettingsScreen(navController, walletViewModel)
                }
            }
            composable(
                Screen.Chats.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ChatsScreen(navController, walletViewModel, chatViewModel = chatViewModel)
                }
            }
            composable(
                Screen.Portfolio.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    PortfolioScreen(navController = navController)
                }
            }
            composable(
                Screen.Profile.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ProfileScreen(
                        viewModel = walletViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.Swap.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    SwapScreen()
                }
            }

            composable("portfolio_transactions") { backStackEntry ->
                // Shares the Portfolio tab's own PortfolioViewModel instance rather than a fresh
                // one, so adding/editing/deleting a transaction here is immediately reflected in
                // the summary card and charts back on Portfolio — see PortfolioTransactionsScreen's
                // doc comment.
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(Screen.Portfolio.route)
                }
                PortfolioTransactionsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel(parentEntry)
                )
            }

            composable("seed_phrase") {
                SeedPhraseScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("cold_storage") {
                ColdStorageListScreen(navController = navController)
            }

            composable(
                "cold_storage_detail/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                ColdStorageDetailScreen(
                    accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                    navController = navController
                )
            }

            composable(
                "cold_storage_tx_history/{address}",
                arguments = listOf(navArgument("address") { type = NavType.StringType })
            ) { backStackEntry ->
                ColdStorageTxHistoryScreen(
                    address = backStackEntry.arguments?.getString("address") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "cold_storage_hidden/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                // Shares ColdStorageDetailScreen's own ViewModel instance (the only screen this
                // one is ever reached from) rather than getting a fresh one scoped to this
                // destination — a fresh instance would need its own full gap-limit rescan just to
                // reconstruct a list the detail screen already has loaded, showing an empty state
                // the whole time that rescan is in flight.
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("cold_storage_detail/{accountId}")
                }
                ColdStorageHiddenAddressesScreen(
                    accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                    navController = navController,
                    viewModel = hiltViewModel(parentEntry)
                )
            }

            composable("manage_addresses") {
                ManageAddressesScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable("edit_kns_profile") {
                EditKnsProfileScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToDomains = { navController.navigate("kns_domains") }
                )
            }

            composable("kns_domains") {
                KnsDomainsScreen(
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

            composable("photo_quality_settings") {
                PhotoQualitySettingsScreen(
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
                // this screen's own message-compose bar rather than overlapping it. But only while
                // the tab bar is actually visible: once the keyboard opens, the tab bar goes behind
                // it anyway, and this screen's own bottomBar already applies imePadding() to clear
                // the keyboard itself — reserving both at once left a dead black gap between the
                // message input and the keyboard.
                val imeVisible = WindowInsets.isImeVisible
                Box(modifier = Modifier.padding(bottom = if (imeVisible) 0.dp else innerPadding.calculateBottomPadding())) {
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
