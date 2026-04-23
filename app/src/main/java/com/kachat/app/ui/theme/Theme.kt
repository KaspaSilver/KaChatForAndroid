package com.kachat.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// KaChat brand colors — inspired by Kaspa's blue/teal palette
val KaspaBlue    = Color(0xFF71D2C1) // Updated to match iOS teal/cyan
val KaspaTeal    = Color(0xFF71D2C1) // Updated to match iOS teal/cyan
val KaspaDark    = Color(0xFF000000) // True black background for iOS look
val KaspaNavy    = Color(0xFF121212) // Slightly lighter black for surfaces
val KaspaCard    = Color(0xFF1E1E1E) // For "Saved Accounts" card style
val KaspaBorder  = Color(0xFF333333)
val KaspaText    = Color(0xFFFFFFFF)
val KaspaSubtext = Color(0xFFAAAAAA)
val KaspaError   = Color(0xFFFC8181)

private val DarkColorScheme = darkColorScheme(
    primary          = KaspaTeal,
    onPrimary        = Color.Black, // Text on primary button is black/white depending on contrast, iOS uses white usually but the teal is light
    primaryContainer = Color(0xFF1A3A5C),
    secondary        = KaspaTeal,
    onSecondary      = Color.Black,
    background       = KaspaDark,
    onBackground     = KaspaText,
    surface          = KaspaNavy,
    onSurface        = KaspaText,
    surfaceVariant   = KaspaCard,
    outline          = KaspaBorder,
    error            = KaspaError,
)

private val LightColorScheme = lightColorScheme(
    primary          = Color(0xFF0077CC),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD0E8FF),
    secondary        = Color(0xFF009E7A),
    onSecondary      = Color.White,
    background       = Color(0xFFF8FAFC),
    onBackground     = Color(0xFF1A202C),
    surface          = Color.White,
    onSurface        = Color(0xFF1A202C),
    surfaceVariant   = Color(0xFFF1F5F9),
    outline          = Color(0xFFCBD5E0),
    error            = Color(0xFFE53E3E),
)

@Composable
fun KaChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // disabled — we use brand colors
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Dynamic color available on Android 12+, but we opt out to keep brand identity
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = KaChatTypography,
        content = content
    )
}
