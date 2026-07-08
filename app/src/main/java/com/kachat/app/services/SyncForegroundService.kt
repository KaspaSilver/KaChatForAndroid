package com.kachat.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kachat.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Exists purely to keep this process exempt from Android's background app freezer —
 * a plain background coroutine (like ChatRepository's poll loop) gets its threads
 * frozen within seconds of the app leaving the foreground otherwise, silently stopping
 * new-message/handshake/payment notifications from ever firing. This service does no
 * work of its own; ChatRepository's existing singleton-scoped poll loop keeps running
 * in the same process as long as a foreground service is active, so just holding the
 * required persistent notification here is enough. BroadcastScanningService's block-scanning
 * loop (when the user has opted in) benefits from the same process-priority protection for
 * free — the notification text below just reflects that it's also running.
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {

    @Inject
    lateinit var broadcastScanningService: BroadcastScanningService

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps checking for new messages while KaChat is in the background"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val contentText = if (broadcastScanningService.isRunning) {
            "Checking for new messages and broadcasts"
        } else {
            "Checking for new messages"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle("KaChat")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // API 35+ calls this to give a dataSync-type FGS a chance to wind down cleanly once its
    // execution-time budget for the current rolling 24h window is used up — without this
    // override the OS just force-stops the service outright instead. KaChatApplication's
    // onStop already retries starting it on every subsequent background transition (and
    // SyncWorker's periodic fallback covers sync in the meantime), so a clean stopSelf() here
    // is all that's needed.
    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "kachat_sync_service"
        private const val NOTIFICATION_ID = 9001
    }
}
