package com.kachat.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — required by Hilt for dependency injection.
 * All singleton services are initialized here via Hilt modules in the `di` package.
 */
@HiltAndroidApp
class KaChatApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Future: initialize logging, crash reporting, etc.
    }
}
