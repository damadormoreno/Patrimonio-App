package com.denebapps.patrimonio.ui.screens.account

import androidx.compose.runtime.Composable

/** What the Google account picker gave back. */
sealed interface GoogleIdTokenResult {
    /** The Google ID token of the picked account, for Firebase. */
    data class Token(val idToken: String) : GoogleIdTokenResult

    /** The user closed the picker. */
    data object Cancelled : GoogleIdTokenResult

    /** The phone has no Google account to pick. */
    data object NoAccount : GoogleIdTokenResult

    data object Failed : GoogleIdTokenResult
}

/**
 * Opens the Google account picker and returns the ID token of the picked account. Null where Sign in with
 * Google is not offered: iOS (the App Store asks for Sign in with Apple next to it, guideline 4.8) and builds
 * without a Google client ID.
 */
@Composable
expect fun rememberGoogleIdTokenRequester(): (suspend () -> GoogleIdTokenResult)?
