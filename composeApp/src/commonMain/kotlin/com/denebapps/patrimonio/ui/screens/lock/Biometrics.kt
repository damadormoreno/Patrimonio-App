package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.runtime.Composable

enum class BiometricKind { FINGERPRINT, FACE, OTHER }

/** The platform's biometric prompt (fingerprint, Face ID…). */
interface BiometricAuthenticator {
    val kind: BiometricKind

    /** How it is called in a sentence: "huella", "Face ID"… */
    val label: String

    /** Shows the prompt; true once the user is recognised, false if they cancel or it fails. */
    suspend fun authenticate(reason: String): Boolean
}

/** Null when the phone has no biometrics set up. Checked again each time the app comes back. */
@Composable
expect fun rememberBiometricAuthenticator(): BiometricAuthenticator?
