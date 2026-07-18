package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Settings > Customization > Menu — which bottom-nav tabs show up, and in what set (order is
 * still controlled separately, by press-and-hold-drag on the bar itself). Settings/Chats/Profile
 * are permanently on (a wallet with no way back to its own settings, chat list, or profile isn't
 * useful), Portfolio/Swap can be hidden, and Cold Storage is the one opt-in extra tab — see
 * [WalletViewModel.coldStorageTabEnabled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuVisibilityScreen(
    navController: NavController,
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val hiddenTabs by walletViewModel.hiddenTabs.collectAsState()
    val coldStorageTabEnabled by walletViewModel.coldStorageTabEnabled.collectAsState()

    Scaffold(
        containerColor = LocalAppColors.current.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Menu", color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = KaspaTeal)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Choose which tabs appear in your bottom menu.",
                color = LocalAppColors.current.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Surface(
                color = LocalAppColors.current.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    MenuVisibilityRow(icon = Icons.Default.Settings, label = "Settings", checked = true, locked = true)
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    MenuVisibilityRow(
                        icon = Icons.Default.PieChart,
                        label = "Portfolio",
                        checked = "portfolio" !in hiddenTabs,
                        locked = false,
                        onToggle = { checked -> walletViewModel.setTabHidden("portfolio", !checked) }
                    )
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    MenuVisibilityRow(icon = Icons.Default.Forum, label = "Chats", checked = true, locked = true)
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    MenuVisibilityRow(
                        icon = Icons.Default.SwapHoriz,
                        label = "Swap",
                        checked = "swap" !in hiddenTabs,
                        locked = false,
                        onToggle = { checked -> walletViewModel.setTabHidden("swap", !checked) }
                    )
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    MenuVisibilityRow(icon = Icons.Default.AccountCircle, label = "Profile", checked = true, locked = true)
                    HorizontalDivider(color = LocalAppColors.current.divider)
                    MenuVisibilityRow(
                        icon = Icons.Default.RssFeed,
                        label = "Broadcasts",
                        checked = "broadcasts" !in hiddenTabs,
                        locked = false,
                        onToggle = { checked -> walletViewModel.setTabHidden("broadcasts", !checked) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "More Menus",
                color = LocalAppColors.current.textSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Surface(
                color = LocalAppColors.current.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MenuVisibilityRow(
                    icon = Icons.Default.Lock,
                    label = "Cold Storage",
                    checked = coldStorageTabEnabled,
                    locked = false,
                    onToggle = { checked -> walletViewModel.setColdStorageTabEnabled(checked) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "When on, Cold Storage becomes its own tab and moves out of Portfolio's \"Cold Storage Devices\" row.",
                color = LocalAppColors.current.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun MenuVisibilityRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onToggle(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = KaspaTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = LocalAppColors.current.textPrimary, fontWeight = FontWeight.Bold)
            if (locked) {
                Text("Always shown", color = LocalAppColors.current.textSecondary, fontSize = 12.sp)
            }
        }
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (checked) "Shown" else "Hidden",
            tint = if (checked) (if (locked) LocalAppColors.current.textSecondary else KaspaTeal) else LocalAppColors.current.textSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}
