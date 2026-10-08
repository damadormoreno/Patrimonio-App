package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.Flow

/** The signed-in user of the optional cloud account. [uid] is the Firebase user id. */
data class AccountUser(val uid: String, val email: String, val provider: AccountProvider = AccountProvider.PASSWORD)

/** How the account signs in, which decides how it proves itself again (to delete it). */
enum class AccountProvider { PASSWORD, GOOGLE }

/** What Google shares on sign-in, to fill an empty profile. */
data class GoogleProfile(val firstName: String?, val lastName: String?, val photoUrl: String?)

/** A fresh proof of identity: Firebase wants a recent sign-in before deleting an account. */
sealed interface Reauthentication {
    data class Password(val password: String) : Reauthentication

    /** A new ID token from the Google account picker, for a [AccountProvider.GOOGLE] account. */
    data class Google(val idToken: String) : Reauthentication
}

/**
 * Optional account (email and password) for the cloud backup. The app works fully without it, so
 * every method is opt-in. Failures are [AuthException]s with a [AuthError] the UI can explain.
 */
interface AuthRepository {
    /** The signed-in user, or null; survives app restarts until [signOut]. */
    fun observeUser(): Flow<AccountUser?>

    suspend fun signUp(email: String, password: String)

    suspend fun signIn(email: String, password: String)

    /** Signs in, creating the account the first time, with an ID token from the Google account picker. */
    suspend fun signInWithGoogle(idToken: String): GoogleProfile

    /** Sends the "reset your password" email. */
    suspend fun sendPasswordReset(email: String)

    suspend fun signOut()

    /** Deletes the account for good. Firebase wants a recent sign-in, so it signs in again with
     *  [reauthentication] first (it must be the same account); then runs [beforeDelete] (while the account
     *  still exists, so it can clean up its cloud data) and deletes the account unless that throws. The local
     *  data stays on the device. */
    suspend fun deleteAccount(reauthentication: Reauthentication, beforeDelete: suspend () -> Unit = {})

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

    /** The sign-in method is not set up in Firebase (or Google refused the app). */
    NOT_AVAILABLE,
    UNKNOWN,
}

class AuthException(val error: AuthError, cause: Throwable? = null) :
    Exception("Auth failed: $error", cause)
