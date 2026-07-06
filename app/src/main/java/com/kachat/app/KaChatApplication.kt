package com.kachat.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kachat.app.services.BroadcastScanningService
import com.kachat.app.services.SyncForegroundService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class — required by Hilt for dependency injection.
 * All singleton services are initialized here via Hilt modules in the `di` package.
 */
@HiltAndroidApp
class KaChatApplication : Application() {

    // @Singleton instances are otherwise only created lazily the first time something actually
    // requests them — field-injecting this here forces it to exist from app startup, so its
    // self-observing "start/stop scanning based on the setting" logic (see its init block) runs
    // for the app's whole lifetime rather than only after the user happens to open a broadcast
    // screen.
    @Inject
    lateinit var broadcastScanningService: BroadcastScanningService

    override fun onCreate() {
        super.onCreate()

        // Only run the (notification-requiring) foreground sync service while the app
        // is actually backgrounded — while it's in the foreground, ChatRepository's poll
        // loop already runs unthrottled and a persistent notification would just be
        // redundant noise on top of what the user is already looking at.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                ContextCompat.startForegroundService(
                    this@KaChatApplication,
                    Intent(this@KaChatApplication, SyncForegroundService::class.java)
                )
            }

            override fun onStart(owner: LifecycleOwner) {
                stopService(Intent(this@KaChatApplication, SyncForegroundService::class.java))
            }
        })
    }
}
