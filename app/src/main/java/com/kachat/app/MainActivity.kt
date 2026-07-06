package com.kachat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kachat.app.services.NotificationHelper
import com.kachat.app.ui.theme.KaChatTheme
import com.kachat.app.ui.KaChatApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity — all navigation is handled in Compose via NavHost.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingContactId by mutableStateOf<String?>(null)
    private var pendingChannelName by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingContactId = intent?.getStringExtra(NotificationHelper.EXTRA_CONTACT_ID)
        pendingChannelName = intent?.getStringExtra(NotificationHelper.EXTRA_CHANNEL_NAME)
        setContent {
            KaChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    KaChatApp(
                        pendingContactId = pendingContactId,
                        onPendingContactHandled = { pendingContactId = null },
                        pendingChannelName = pendingChannelName,
                        onPendingChannelHandled = { pendingChannelName = null }
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
    }
}
