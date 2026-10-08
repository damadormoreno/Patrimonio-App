package com.denebapps.patrimonio.data.auth

import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class InMemoryAuthSessionStore(initial: AuthSession? = null) : AuthSessionStore {
    val session = MutableStateFlow(initial)

    override fun observe(): Flow<AuthSession?> = session

    override suspend fun save(session: AuthSession) {
        this.session.value = session
    }

    override suspend fun clear() {
        session.value = null
    }
}

private class MutableClock(var nowMs: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}

class AuthRepositoryImplTest {
    private val clock = MutableClock(1_000_000L)
    private val store = InMemoryAuthSessionStore()
    private val paths = mutableListOf<String>()

    /** Answers each Firebase call from [responses] by the last path segment ("accounts:signUp", "token"). */
    private fun repository(responses: Map<String, Pair<HttpStatusCode, String>>): AuthRepositoryImpl {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.substringAfterLast('/')
            paths += path
            val (status, body) = responses.getValue(path)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return AuthRepositoryImpl(FirebaseAuthApi(HttpClient(engine), apiKey = "k"), store, clock)
    }

    private fun signInBody(idToken: String = "id-1") =
        HttpStatusCode.OK to """{"localId":"uid-1","email":"ana@example.com","idToken":"$idToken",""" +
            """"refreshToken":"refresh-1","expiresIn":"3600"}"""

    private fun refreshBody() =
        HttpStatusCode.OK to """{"user_id":"uid-1","id_token":"id-2","refresh_token":"refresh-2","expires_in":"3600"}"""

    @Test
    fun `signing up keeps the session and exposes the user`() = runTest {
        val repository = repository(mapOf("accounts:signUp" to signInBody()))

        repository.signUp("  ana@example.com ", "secreto")

        assertEquals(AccountUser("uid-1", "ana@example.com"), repository.observeUser().first())
        assertEquals(1_000_000L + 3_600_000L, store.session.value?.expiresAtEpochMs)
    }

    @Test
    fun `a fresh token is returned as is and an expiring one is refreshed once`() = runTest {
        val repository = repository(
            mapOf(
                "accounts:signInWithPassword" to signInBody(),
                "token" to refreshBody(),
            ),
        )
        repository.signIn("ana@example.com", "secreto")

        assertEquals("id-1", repository.idToken())

        clock.nowMs += 3_600_000L - 60_000L // one minute before it expires
        assertEquals("id-2", repository.idToken())
        assertEquals("id-2", repository.idToken())
        assertEquals(listOf("accounts:signInWithPassword", "token"), paths)
        assertEquals("refresh-2", store.session.value?.refreshToken)
        assertEquals("ana@example.com", store.session.value?.email)
    }

    @Test
    fun `a rejected refresh token signs out`() = runTest {
        val repository = repository(
            mapOf(
                "accounts:signInWithPassword" to signInBody(),
                "token" to (HttpStatusCode.BadRequest to """{"error":{"message":"TOKEN_EXPIRED"}}"""),
            ),
        )
        repository.signIn("ana@example.com", "secreto")
        clock.nowMs += 3_600_000L

        val error = assertFailsWith<AuthException> { repository.idToken() }

        assertEquals(AuthError.SESSION_EXPIRED, error.error)
        assertNull(repository.observeUser().first())
    }

    @Test
    fun `deleting the account signs in again first and then clears the session`() = runTest {
        val repository = repository(
            mapOf(
                "accounts:signInWithPassword" to signInBody(idToken = "fresh"),
                "accounts:delete" to (HttpStatusCode.OK to "{}"),
            ),
        )
        repository.signIn("ana@example.com", "secreto")

        repository.deleteAccount("secreto") { paths += "beforeDelete" }

        assertEquals(
            listOf("accounts:signInWithPassword", "accounts:signInWithPassword", "beforeDelete", "accounts:delete"),
            paths,
        )
        assertNull(repository.observeUser().first())
    }

    @Test
    fun `a failing cleanup keeps the account and the session`() = runTest {
        val repository = repository(mapOf("accounts:signInWithPassword" to signInBody(idToken = "fresh")))
        repository.signIn("ana@example.com", "secreto")

        assertFailsWith<IllegalStateException> { repository.deleteAccount("secreto") { error("cloud down") } }

        assertEquals(listOf("accounts:signInWithPassword", "accounts:signInWithPassword"), paths)
        assertNotNull(repository.observeUser().first())
    }

    @Test
    fun `nothing to refresh or delete while signed out`() = runTest {
        val repository = repository(emptyMap())

        assertEquals(AuthError.NOT_SIGNED_IN, assertFailsWith<AuthException> { repository.idToken() }.error)
        assertEquals(
            AuthError.NOT_SIGNED_IN,
            assertFailsWith<AuthException> { repository.deleteAccount("secreto") }.error,
        )
        repository.signOut()
        assertEquals(emptyList(), paths)
    }
}
