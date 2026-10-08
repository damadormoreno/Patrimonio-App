package com.denebapps.patrimonio.ui.screens.account

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.denebapps.patrimonio.data.auth.FirebaseConfig
import com.denebapps.patrimonio.data.platform.AppLogger
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** Credential Manager's "Sign in with Google" sheet. The context is the activity: the sheet is shown over it. */
@Composable
actual fun rememberGoogleIdTokenRequester(): (suspend () -> GoogleIdTokenResult)? {
    val context = LocalContext.current
    return remember(context) {
        if (FirebaseConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            null
        } else {
            { requestGoogleIdToken(context) }
        }
    }
}

private suspend fun requestGoogleIdToken(context: Context): GoogleIdTokenResult {
    // The web client ID: Firebase checks the token was issued for it. The Android client (package + SHA-1
    // registered in Firebase) is matched by Google Play services on its own.
    val option = GetSignInWithGoogleOption.Builder(FirebaseConfig.GOOGLE_WEB_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    return try {
        val credential = CredentialManager.create(context).getCredential(context, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleIdTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            AppLogger.error(TAG, "Unexpected credential type ${credential.type}")
            GoogleIdTokenResult.Failed
        }
    } catch (e: GetCredentialCancellationException) {
        GoogleIdTokenResult.Cancelled
    } catch (e: NoCredentialException) {
        GoogleIdTokenResult.NoAccount
    } catch (e: GetCredentialException) {
        AppLogger.error(TAG, "Google sign-in failed", e)
        GoogleIdTokenResult.Failed
    } catch (e: GoogleIdTokenParsingException) {
        AppLogger.error(TAG, "Unreadable Google ID token", e)
        GoogleIdTokenResult.Failed
    }
}

private const val TAG = "GoogleSignIn"
