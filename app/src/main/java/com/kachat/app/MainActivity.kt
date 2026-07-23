package com.kachat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.kachat.app.services.NotificationHelper
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.ui.KaChatApp
import com.kachat.app.viewmodels.WalletViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity — all navigation is handled in Compose via NavHost.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private var pendingContactId by mutableStateOf<String?>(null)
    private var pendingChannelName by mutableStateOf<String?>(null)
    private var pendingGroupId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingContactId = intent?.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
        pendingChannelName = intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME)
        pendingGroupId = intent?.getStringExtra(NotificationHelper.EXTRA_GROUP_ID)
        setContent {
            val walletViewModel: WalletViewModel = hiltViewModel()
            val darkModeEnabled by walletViewModel.darkModeEnabled.collectAsState()
            KaChatTheme(darkTheme = darkModeEnabled) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KaChatApp(
                        pendingContactId = pendingContactId,
                        onPendingContactHandled = { pendingContactId = null },
                        pendingChannelName = pendingChannelName,
                        onPendingChannelHandled = { pendingChannelName = null },
                        pendingGroupId = pendingGroupId,
                        onPendingGroupHandled = { pendingGroupId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingContactId = intent.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
        pendingChannelName = intent.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME)
        pendingGroupId = intent.getStringExtra(NotificationHelper.EXTRA_GROUP_ID)
    }
}
