package com.denebapps.patrimonio.data.auth

import com.denebapps.patrimonio.domain.repository.AccountProvider
import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.GoogleProfile
import com.denebapps.patrimonio.domain.repository.Reauthentication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Firebase email/password account kept in [store]. ID tokens last an hour: [idToken] refreshes them a
 * few minutes early, one refresh at a time, and signs out when Firebase no longer accepts the refresh
 * token (password changed, account deleted elsewhere).
 */
class AuthRepositoryImpl(
    private val api: FirebaseAuthApi,
    private val store: AuthSessionStore,
    private val clock: Clock,
) : AuthRepository {
    private val refreshMutex = Mutex()

    override fun observeUser(): Flow<AccountUser?> = store.observe()
        .map { session -> session?.let { AccountUser(it.uid, it.email, it.provider) } }
        .distinctUntilChanged()

    override suspend fun signUp(email: String, password: String) {
        val trimmed = email.trim()
        save(api.signUp(trimmed, password), trimmed)
    }

    override suspend fun signIn(email: String, password: String) {
        val trimmed = email.trim()
        save(api.signIn(trimmed, password), trimmed)
    }

    override suspend fun signInWithGoogle(idToken: String): GoogleProfile {
        val signIn = api.signInWithIdp(idToken)
        save(signIn.tokens, signIn.tokens.email.orEmpty(), AccountProvider.GOOGLE)
        return signIn.profile
    }

    override suspend fun sendPasswordReset(email: String) {
        api.sendPasswordReset(email.trim())
    }

    override suspend fun signOut() {
        store.clear()
    }

    override suspend fun deleteAccount(reauthentication: Reauthentication, beforeDelete: suspend () -> Unit) {
        val session = store.observe().first() ?: throw AuthException(AuthError.NOT_SIGNED_IN)
        val fresh = when (reauthentication) {
            is Reauthentication.Password -> api.signIn(session.email, reauthentication.password)
            is Reauthentication.Google -> api.signInWithIdp(reauthentication.idToken).tokens
        }
        // Another Google account picked in the dialog proves nothing about this one.
        if (fresh.uid != session.uid) throw AuthException(AuthError.WRONG_CREDENTIALS)
        beforeDelete()
        api.deleteAccount(fresh.idToken)
        store.clear()
    }

    override suspend fun idToken(): String = refreshMutex.withLock {
        val session = store.observe().first() ?: throw AuthException(AuthError.NOT_SIGNED_IN)
        if (session.expiresAtEpochMs - nowMs() > REFRESH_MARGIN_MS) return session.idToken
        val tokens = try {
            api.refresh(session.refreshToken)
        } catch (e: AuthException) {
            if (e.error == AuthError.SESSION_EXPIRED) store.clear()
            throw e
        }
        save(tokens, session.email, session.provider)
        tokens.idToken
    }

    private suspend fun save(tokens: AuthTokens, email: String, provider: AccountProvider = AccountProvider.PASSWORD) {
        store.save(
            AuthSession(
                uid = tokens.uid,
                email = tokens.email ?: email,
                idToken = tokens.idToken,
                refreshToken = tokens.refreshToken,
                expiresAtEpochMs = nowMs() + tokens.expiresInSeconds * 1_000,
                provider = provider,
            ),
        )
    }

    private fun nowMs() = clock.now().toEpochMilliseconds()

    private companion object {
        const val REFRESH_MARGIN_MS = 5 * 60 * 1_000L
    }
}
