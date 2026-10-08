package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberBiometricAuthenticator(): BiometricAuthenticator? {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(lifecycleState) {
        val context = LAContext()
        if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) {
            IosBiometricAuthenticator(
                when (context.biometryType) {
                    LABiometryTypeFaceID -> BiometricKind.FACE
                    LABiometryTypeTouchID -> BiometricKind.FINGERPRINT
                    else -> BiometricKind.OTHER
                },
            )
        } else {
            null
        }
    }
}

private class IosBiometricAuthenticator(override val kind: BiometricKind) : BiometricAuthenticator {
    override val label = when (kind) {
        BiometricKind.FACE -> "Face ID"
        BiometricKind.FINGERPRINT -> "Touch ID"
        BiometricKind.OTHER -> "biometría"
    }

    override suspend fun authenticate(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        val context = LAContext().apply { localizedFallbackTitle = "Usar PIN" }
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, reason) { success, _ ->
            if (continuation.isActive) continuation.resume(success)
        }
        continuation.invokeOnCancellation { context.invalidate() }
    }
}
