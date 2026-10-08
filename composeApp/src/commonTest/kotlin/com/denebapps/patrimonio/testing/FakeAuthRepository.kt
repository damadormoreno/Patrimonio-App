package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.repository.AccountProvider
import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.GoogleProfile
import com.denebapps.patrimonio.domain.repository.Reauthentication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [AuthRepository]. Set [failWith] to make the next calls throw that [AuthError]. */
class FakeAuthRepository(user: AccountUser? = null) : AuthRepository {
    private val userBacking = MutableStateFlow(user)
    val calls = mutableListOf<String>()
    var failWith: AuthError? = null
    val currentUser: AccountUser? get() = userBacking.value

    override fun observeUser(): Flow<AccountUser?> = userBacking

    override suspend fun signUp(email: String, password: String) {
        record("signUp:$email")
        userBacking.value = AccountUser("uid-${email.trim()}", email.trim())
    }

    override suspend fun signIn(email: String, password: String) {
        record("signIn:$email")
        userBacking.value = AccountUser("uid-${email.trim()}", email.trim())
    }

    /** The profile [signInWithGoogle] hands back. */
    var googleProfile = GoogleProfile(firstName = "Ana", lastName = "García", photoUrl = "https://photo/ana")

    override suspend fun signInWithGoogle(idToken: String): GoogleProfile {
        record("google:$idToken")
        userBacking.value = AccountUser("uid-google", "ana@gmail.com", AccountProvider.GOOGLE)
        return googleProfile
    }

    override suspend fun sendPasswordReset(email: String) {
        record("reset:$email")
    }

    override suspend fun signOut() {
        record("signOut")
        userBacking.value = null
    }

    override suspend fun deleteAccount(reauthentication: Reauthentication, beforeDelete: suspend () -> Unit) {
        record(
            when (reauthentication) {
                is Reauthentication.Password -> "delete:${reauthentication.password}"
                is Reauthentication.Google -> "delete:google:${reauthentication.idToken}"
            },
        )
        beforeDelete()
        userBacking.value = null
    }

    override suspend fun idToken(): String {
        record("idToken")
        return "token"
    }

    private fun record(call: String) {
        failWith?.let { throw AuthException(it) }
        calls += call
    }
}
