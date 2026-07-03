package com.kachat.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kachat.app.services.SyncForegroundService
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — required by Hilt for dependency injection.
 * All singleton services are initialized here via Hilt modules in the `di` package.
 */
@HiltAndroidApp
class KaChatApplication : Application() {

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
