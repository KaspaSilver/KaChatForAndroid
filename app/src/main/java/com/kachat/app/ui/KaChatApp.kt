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
import androidx.compose.material.icons.filled.Lock
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
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel
import com.kachat.app.viewmodels.ChatViewModel
import kotlin.math.roundToInt

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Settings    : Screen("settings",     "Settings",     Icons.Default.Settings)
    object Chats       : Screen("chats",        "Chats",        Icons.Default.Forum)
    object Portfolio   : Screen("portfolio",    "Portfolio",    Icons.Default.PieChart)
    object Profile     : Screen("profile",      "Profile",      Icons.Default.AccountCircle)
    object Swap        : Screen("swap",         "Swap",         Icons.Default.SwapHoriz)
    // Labeled "Storage" (not "Cold Storage") and always in the default tab set, matching iOS's
    // AppTab.coldStorage — hideable like Portfolio/Swap via Settings > Customization > Menu, but
    // no longer a separate opt-in reached through Portfolio's old "Cold Storage Devices" row.
    object ColdStorage : Screen("cold_storage", "Storage",      Icons.Default.Lock)
}

/** Route strings for tabs that can never be hidden — see [resolveTabOrder]. */
val ALWAYS_VISIBLE_TAB_ROUTES = setOf(Screen.Chats.route, Screen.Profile.route)

// All top-level tabs, in the app's default order (matches iOS's AppTab.defaultOrder). Settings
// isn't a tab at all (matches iOS) — it's reached one tap in from Profile's gear icon instead,
// see ProfileScreen.
val bottomNavItems = listOf(
    Screen.Portfolio,
    Screen.ColdStorage,
    Screen.Chats,
    Screen.Swap,
    Screen.Profile
)

/**
 * Maps persisted route strings (from AppSettingsRepository.tabOrder) back to [Screen] objects,
 * in that order. Any route no longer recognized (e.g. a tab removed in a future update) is
 * dropped, and any [Screen] missing from the persisted list (e.g. a tab added since the user
 * last reordered) is appended at the end — so neither a stale persisted value nor an app update
 * can leave a tab permanently missing or crash on an unknown route. [hiddenTabs] then filters out
 * anything the user unchecked in Settings > Customization > Menu (never applied to
 * [ALWAYS_VISIBLE_TAB_ROUTES]).
 */
private fun resolveTabOrder(routes: List<String>, hiddenTabs: Set<String>): List<Screen> {
    val byRoute = bottomNavItems.associateBy { it.route }
    val resolved = routes.mapNotNull { byRoute[it] }
    val missing = bottomNavItems.filter { it !in resolved }
    val ordered = resolved + missing
    return ordered.filter { it.route in ALWAYS_VISIBLE_TAB_ROUTES || it.route !in hiddenTabs }
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
    onPendingChannelHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {}
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
            onPendingChannelHandled = onPendingChannelHandled,
            pendingGroupId = pendingGroupId,
            onPendingGroupHandled = onPendingGroupHandled
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
    onPendingChannelHandled: () -> Unit = {},
    pendingGroupId: String? = null,
    onPendingGroupHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // The broadcasts list isn't one of the four real tabs, but it's still a top-level browsing
    // screen (like Chats) rather than a "pushed" detail screen — the bottom nav should stay put
    // and stay functional there, not disappear the way it does for chat/chat_info/etc. A broadcast
    // room itself gets the same treatment (unlike a 1:1 chat thread) — it's still just browsing/
    // participating in a public room, one tap away from Chats/Profile, not a private conversation
    // you'd want to maximize screen space for.
    val onTabRoute = currentDestination?.hierarchy?.any { dest ->
        bottomNavItems.any { it.route == dest.route } ||
            dest.route == "broadcasts" || dest.route == "broadcast_channel/{channelName}"
    } == true

    // Press-and-hold a tab, then drag to reorder — the persisted order (WalletViewModel.tabOrder)
    // is only written on drag end; localTabOrder is the live, possibly-mid-drag copy the Row
    // actually renders from, reconciled back to the persisted order whenever it changes and no
    // drag is in progress (so a fresh install / another device's order still applies normally).
    // Also reconciled on hiddenTabs changes, so toggling a tab in Settings > Customization > Menu
    // updates the bar immediately without needing to leave/reopen it.
    val persistedTabOrder by walletViewModel.tabOrder.collectAsState()
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val hideBottomBar by walletViewModel.hideBottomBar.collectAsState()
    var draggedScreen by remember { mutableStateOf<Screen?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var tabItemWidthPx by remember { mutableStateOf(0f) }
    var localTabOrder by remember { mutableStateOf(resolveTabOrder(persistedTabOrder, hiddenTabs)) }
    LaunchedEffect(persistedTabOrder, hiddenTabs) {
        if (draggedScreen == null) {
            localTabOrder = resolveTabOrder(persistedTabOrder, hiddenTabs)
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

    // Same idea for a group chat notification.
    LaunchedEffect(pendingGroupId) {
        if (pendingGroupId != null) {
            navController.navigate("group_chat/$pendingGroupId")
            onPendingGroupHandled()
        }
    }

    Scaffold(
        containerColor = LocalAppColors.current.background,
        bottomBar = {
            // Only show the floating tab bar on the top-level tab destinations —
            // "pushed" detail screens (chat thread, settings sub-screens, etc.) fill
            // the whole screen with their own Scaffold and must not have this
            // overlaid on top of them (it was blocking the chat input entirely).
            if (onTabRoute && !hideBottomBar) {
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
                            .background(LocalAppColors.current.surface, RoundedCornerShape(40.dp))
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
                                            // Already there — let that screen know it was re-tapped (so it can
                                            // dismiss its own transient UI, e.g. a full-screen QR overlay) rather
                                            // than running the popBackStack/navigate logic below, which for a tab
                                            // with nothing "pushed" above it falls through to navigate() and
                                            // lands back on the graph's start destination (Chats) instead of
                                            // staying put.
                                            if (selected) {
                                                walletViewModel.notifyTabReselected(screen.route)
                                                return@clickable
                                            }

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
                                            tint = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = screen.label,
                                            color = if (selected) KaspaTeal else LocalAppColors.current.textPrimary,
                                            // Longer labels ("Cold Storage") don't fit at 10sp once there are
                                            // enough tabs that each weight(1f) slot narrows below their natural
                                            // width — shrink just those instead of letting them clip/wrap and
                                            // get cut off by the fixed-height Box.
                                            fontSize = if (screen.label.length > 9) 8.sp else 10.sp,
                                            maxLines = 1,
                                            softWrap = false,
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
            // Settings isn't a bottom-tab destination (matches iOS - reached one tap in from
            // Profile's gear icon), so it gets the normal NavHost-level fade like any other
            // pushed detail screen, not the instant tab-swap treatment below.
            composable(Screen.Settings.route) {
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    SettingsScreen(navController, walletViewModel)
                }
            }
            // The four bottom-tab destinations get an instant swap, overriding the NavHost-level
            // 150ms fade above just for these routes — that fade is a good fit for pushed detail
            // screens, but a tab bar's own instant selected-tint feedback reads as sluggish when
            // paired with any fade on the content behind it, however short.
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
                    SwapScreen(navController = navController)
                }
            }

            composable(
                "portfolio_transactions?prefillType={prefillType}&prefillAmountKas={prefillAmountKas}&prefillFiatValue={prefillFiatValue}&prefillTimestamp={prefillTimestamp}&prefillNotes={prefillNotes}&prefillSwapId={prefillSwapId}",
                arguments = listOf(
                    navArgument("prefillType") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillAmountKas") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillFiatValue") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillTimestamp") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillNotes") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("prefillSwapId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                // Shares the Portfolio tab's own PortfolioViewModel instance rather than a fresh
                // one, so adding/editing/deleting a transaction here is immediately reflected in
                // the summary card and charts back on Portfolio — see PortfolioTransactionsScreen's
                // doc comment. That only works if the Portfolio tab's own back stack entry already
                // exists (getBackStackEntry throws otherwise) — true when reached from Portfolio's
                // own "View All" button, but NOT when reached from Swap's "Add to Portfolio" if the
                // user never opened the Portfolio tab this session, so fall back to a fresh instance.
                val parentEntry = remember(backStackEntry) {
                    try {
                        navController.getBackStackEntry(Screen.Portfolio.route)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                val args = backStackEntry.arguments
                PortfolioTransactionsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = if (parentEntry != null) hiltViewModel(parentEntry) else hiltViewModel(),
                    prefillType = args?.getString("prefillType"),
                    prefillAmountKas = args?.getString("prefillAmountKas")?.toDoubleOrNull(),
                    prefillFiatValue = args?.getString("prefillFiatValue")?.toDoubleOrNull(),
                    prefillTimestampMillis = args?.getString("prefillTimestamp")?.toLongOrNull(),
                    prefillNotes = args?.getString("prefillNotes")?.let { android.net.Uri.decode(it) },
                    prefillSwapId = args?.getString("prefillSwapId")
                )
            }

            composable("seed_phrase") {
                SeedPhraseScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Screen.ColdStorage.route,
                // One of the five real bottom tabs now (see bottomNavItems), so it gets the same
                // instant tab-swap treatment as Portfolio/Chats/Swap/Profile above, not the
                // NavHost-level fade.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                // Reserve room beneath the screen's own FAB the same way every other tab screen
                // does, or the "Scan" button sits underneath/behind the floating nav bar instead
                // of above it.
                Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                    ColdStorageListScreen(navController = navController, walletViewModel = walletViewModel)
                }
            }

            composable(
                "cold_storage_detail/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                // Shares the "cold_storage" list screen's own ViewModel instance (always on the
                // back stack — this screen is only ever reached by tapping a row there) rather
                // than a fresh one scoped to this destination. A fresh instance's deleteAccount()
                // updated its own _accounts flow only, leaving the list screen's copy stale until
                // something else happened to recompose it — deleting an account looked like it
                // hadn't taken effect until you left and came back.
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("cold_storage")
                }
                ColdStorageDetailScreen(
                    accountId = backStackEntry.arguments?.getString("accountId") ?: "",
                    navController = navController,
                    viewModel = hiltViewModel(parentEntry)
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
                    onBack = { navController.popBackStack() },
                    // Reuses Cold Storage's transaction-history screen/route — its data fetch
                    // (ColdStorageAddressDiscovery.getTransactionHistory) is keyed purely by
                    // address string, nothing cold-storage-specific about it, so it works
                    // identically for a regular spending address.
                    onNavigateToTxHistory = { address -> navController.navigate("cold_storage_tx_history/$address") },
                    onNavigateToHidden = { navController.navigate("manage_addresses_hidden") }
                )
            }

            composable(
                "manage_addresses_pick/{target}",
                arguments = listOf(navArgument("target") { type = NavType.StringType })
            ) { backStackEntry ->
                val target = backStackEntry.arguments?.getString("target") ?: "from"
                ManageAddressesScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToHidden = { navController.navigate("manage_addresses_hidden_pick/$target") },
                    onAddressPicked = { entry ->
                        val swapEntry = navController.getBackStackEntry(Screen.Swap.route)
                        if (target == "to") {
                            swapEntry.savedStateHandle.set("picked_to_index", entry.index)
                        } else {
                            swapEntry.savedStateHandle.set("picked_from_index", entry.index)
                            swapEntry.savedStateHandle.set("picked_from_balance", entry.balanceSompi)
                        }
                        navController.popBackStack(Screen.Swap.route, false)
                    }
                )
            }

            composable(
                "manage_addresses_hidden_pick/{target}",
                arguments = listOf(navArgument("target") { type = NavType.StringType })
            ) { backStackEntry ->
                val target = backStackEntry.arguments?.getString("target") ?: "from"
                ManageAddressesHiddenScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onAddressPicked = { entry ->
                        val swapEntry = navController.getBackStackEntry(Screen.Swap.route)
                        if (target == "to") {
                            swapEntry.savedStateHandle.set("picked_to_index", entry.index)
                        } else {
                            swapEntry.savedStateHandle.set("picked_from_index", entry.index)
                            swapEntry.savedStateHandle.set("picked_from_balance", entry.balanceSompi)
                        }
                        navController.popBackStack(Screen.Swap.route, false)
                    }
                )
            }

            composable("manage_addresses_hidden") {
                ManageAddressesHiddenScreen(
                    viewModel = walletViewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToTxHistory = { address -> navController.navigate("cold_storage_tx_history/$address") }
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

            composable("settings_menu") {
                MenuVisibilityScreen(navController = navController)
            }

            composable("connection_settings") {
                ConnectionSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("kaspa_explorer_settings") {
                KaspaExplorerSettingsScreen(
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
                    onGroupCreated = { groupId ->
                        navController.navigate("group_chat/$groupId") {
                            popUpTo(Screen.Chats.route)
                        }
                    },
                    chatViewModel = chatViewModel
                )
            }

            composable("group_chat/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupChatThreadScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel)
            }

            composable("group_chat_info/{groupId}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupChatInfoScreen(navController = navController, groupId = groupId, chatViewModel = chatViewModel)
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

            composable("chess_game/{contactId}/{gameId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                val gameId = backStackEntry.arguments?.getString("gameId") ?: return@composable
                ChessGameScreen(
                    navController = navController,
                    contactId = contactId,
                    gameId = gameId,
                    chatViewModel = chatViewModel
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
                    fromBroadcast = fromBroadcast,
                    onNavigateToPhotoSettings = { id -> navController.navigate("contact_photo_settings/$id") },
                    onNavigateToNotificationSettings = { id -> navController.navigate("contact_notification_settings/$id") },
                    onNavigateToDomainSettings = { id -> navController.navigate("contact_domain_settings/$id") }
                )
            }

            composable(
                "contact_photo_settings/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactPhotoSettingsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "contact_notification_settings/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactNotificationSettingsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                "contact_domain_settings/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType })
            ) { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                ContactDomainSettingsScreen(
                    contactId = contactId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
