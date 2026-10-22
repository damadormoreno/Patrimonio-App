package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.runtime.Composable

/** Browsers have no biometric prompt the app can use to unlock itself. */
@Composable
actual fun rememberBiometricAuthenticator(): BiometricAuthenticator? = null
