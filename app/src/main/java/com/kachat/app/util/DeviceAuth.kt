package com.kachat.app.util

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/**
 * Gates [onSuccess] behind whatever the device's own lock screen is set to (fingerprint/face
 * unlock or PIN/pattern/password) — used for the wallet's most sensitive actions (viewing the
 * seed phrase, unlocking a saved account after logout) so they can't be reached by anyone who
 * just picked up an already-unlocked phone. Falls straight through to [onSuccess] when the
 * device has no secure lock screen configured at all, since there's no credential to require.
 */
fun Context.authenticateWithDeviceCredential(
    title: String,
    subtitle: String? = null,
    onSuccess: () -> Unit,
    onFailure: () -> Unit = {}
) {
    val activity = findFragmentActivity()
    if (activity == null) {
        onSuccess()
        return
    }
    val allowedAuthenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(allowedAuthenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { subtitle?.let { setSubtitle(it) } }
        .setAllowedAuthenticators(allowedAuthenticators)
        .build()
    prompt.authenticate(promptInfo)
}
