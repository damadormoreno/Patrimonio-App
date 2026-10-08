package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.Flow

/** The signed-in user of the optional cloud account. [uid] is the Firebase user id. */
data class AccountUser(val uid: String, val email: String)

/**
 * Optional account (email and password) for the cloud backup. The app works fully without it, so
 * every method is opt-in. Failures are [AuthException]s with a [AuthError] the UI can explain.
 */
interface AuthRepository {
    /** The signed-in user, or null; survives app restarts until [signOut]. */
    fun observeUser(): Flow<AccountUser?>

    suspend fun signUp(email: String, password: String)

    suspend fun signIn(email: String, password: String)

    /** Sends the "reset your password" email. */
    suspend fun sendPasswordReset(email: String)

    suspend fun signOut()

    /** Deletes the account for good. Firebase wants a recent sign-in, so it signs in again with
     *  [password] first; the local data stays on the device. */
    suspend fun deleteAccount(password: String)

    /** A valid ID token for the cloud backup, refreshed when close to expiring.
     *  @throws AuthException with [AuthError.NOT_SIGNED_IN] when nobody is signed in. */
    suspend fun idToken(): String
}

enum class AuthError {
    INVALID_EMAIL,
    EMAIL_IN_USE,
    WEAK_PASSWORD,
    WRONG_CREDENTIALS,
    TOO_MANY_ATTEMPTS,
    NOT_SIGNED_IN,
    SESSION_EXPIRED,
    NETWORK,
    UNKNOWN,
}

class AuthException(val error: AuthError, cause: Throwable? = null) :
    Exception("Auth failed: $error", cause)
