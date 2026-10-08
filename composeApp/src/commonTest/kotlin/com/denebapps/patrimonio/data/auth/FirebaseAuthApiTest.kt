package com.denebapps.patrimonio.data.auth

import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirebaseAuthApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FirebaseAuthApi {
        val engine = MockEngine { request ->
            requests += request
            this.handler(request)
        }
        // Same setup as the app's client: ContentNegotiation must not re-encode the JSON bodies.
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return FirebaseAuthApi(client, apiKey = "test-key")
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    @Test
    fun `sign in posts the credentials with the API key and returns the tokens`() = runTest {
        val api = api {
            respondJson(
                """{"localId":"uid-1","email":"ana@example.com","idToken":"id-1",""" +
                    """"refreshToken":"refresh-1","expiresIn":"3600","registered":true}""",
            )
        }

        val tokens = api.signIn("ana@example.com", "secreto")

        assertEquals(AuthTokens("uid-1", "ana@example.com", "id-1", "refresh-1", 3600), tokens)
        val request = requests.single()
        assertEquals(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=test-key",
            request.url.toString(),
        )
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals("ana@example.com", body.getValue("email").jsonPrimitive.content)
        assertEquals("secreto", body.getValue("password").jsonPrimitive.content)
        assertEquals("true", body.getValue("returnSecureToken").jsonPrimitive.content)
    }

    @Test
    fun `refresh sends a form to the secure token endpoint`() = runTest {
        val api = api {
            respondJson(
                """{"user_id":"uid-1","id_token":"id-2","refresh_token":"refresh-2",""" +
                    """"expires_in":"3600","token_type":"Bearer"}""",
            )
        }

        val tokens = api.refresh("refresh-1")

        assertEquals(AuthTokens("uid-1", null, "id-2", "refresh-2", 3600), tokens)
        val request = requests.single()
        assertEquals("https://securetoken.googleapis.com/v1/token?key=test-key", request.url.toString())
        val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("grant_type=refresh_token" in form, form)
        assertTrue("refresh_token=refresh-1" in form, form)
    }

    @Test
    fun `password reset and account deletion hit their endpoints`() = runTest {
        val api = api { respondJson("{}") }

        api.sendPasswordReset("ana@example.com")
        api.deleteAccount("id-1")

        assertEquals(
            listOf("accounts:sendOobCode", "accounts:delete"),
            requests.map { it.url.encodedPath.substringAfterLast('/') },
        )
        assertTrue("PASSWORD_RESET" in (requests[0].body as TextContent).text)
    }

    @Test
    fun `Firebase error codes become typed auth errors`() = runTest {
        val api = api {
            respondJson("""{"error":{"code":400,"message":"EMAIL_EXISTS","errors":[]}}""", HttpStatusCode.BadRequest)
        }

        val error = assertFailsWith<AuthException> { api.signUp("ana@example.com", "secreto") }

        assertEquals(AuthError.EMAIL_IN_USE, error.error)
    }

    @Test
    fun `codes with details and unknown codes map too`() {
        assertEquals(AuthError.WEAK_PASSWORD, authErrorFor("WEAK_PASSWORD : Password should be at least 6 characters"))
        assertEquals(AuthError.WRONG_CREDENTIALS, authErrorFor("INVALID_LOGIN_CREDENTIALS"))
        assertEquals(AuthError.SESSION_EXPIRED, authErrorFor("TOKEN_EXPIRED"))
        assertEquals(AuthError.TOO_MANY_ATTEMPTS, authErrorFor("TOO_MANY_ATTEMPTS_TRY_LATER : Too many attempts"))
        assertEquals(AuthError.UNKNOWN, authErrorFor("SOMETHING_NEW"))
    }

    @Test
    fun `no answer at all is a network error`() = runTest {
        val api = api { throw IllegalStateException("offline") }

        val error = assertFailsWith<AuthException> { api.signIn("ana@example.com", "secreto") }

        assertEquals(AuthError.NETWORK, error.error)
    }
}
