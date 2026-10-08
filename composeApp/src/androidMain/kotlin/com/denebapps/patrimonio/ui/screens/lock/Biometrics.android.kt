package com.denebapps.patrimonio.ui.screens.lock

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.denebapps.patrimonio.data.platform.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
actual fun rememberBiometricAuthenticator(): BiometricAuthenticator? {
    val activity = LocalContext.current.findActivity() ?: return null
    // Re-checked when the app comes back: a fingerprint may have been added in the system settings.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(activity, lifecycleState) {
        val available = BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (available) AndroidBiometricAuthenticator(activity) else null
    }
}

/** Weak biometrics are enough: nothing is decrypted with them, they only open the app instead of the PIN. */
private class AndroidBiometricAuthenticator(private val activity: FragmentActivity) : BiometricAuthenticator {
    override val kind = when {
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> BiometricKind.FINGERPRINT
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FACE) -> BiometricKind.FACE
        else -> BiometricKind.OTHER
    }

    override val label = when (kind) {
        BiometricKind.FINGERPRINT -> "huella"
        BiometricKind.FACE -> "tu cara"
        BiometricKind.OTHER -> "biometría"
    }

    override suspend fun authenticate(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            // A finger that does not match keeps the prompt open; only an error or a cancel ends it.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    AppLogger.error("Biometrics", "Prompt error $errorCode: $errString")
                }
                if (continuation.isActive) continuation.resume(false)
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Patrimonio")
            .setSubtitle(reason)
            .setNegativeButtonText("Usar PIN")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}

private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
