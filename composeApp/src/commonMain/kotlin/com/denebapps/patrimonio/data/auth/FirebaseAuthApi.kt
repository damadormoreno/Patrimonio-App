package com.denebapps.patrimonio.data.auth

import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tokens returned when signing in or refreshing; [expiresInSeconds] counts from the response. */
data class AuthTokens(
    val uid: String,
    val email: String?,
    val idToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/**
 * Firebase Authentication over its REST API (Identity Toolkit and Secure Token). Every failure is an
 * [AuthException]: Firebase error codes map to an [AuthError], anything that never got an answer
 * (no connection, timeout, unreadable body) to [AuthError.NETWORK].
 */
class FirebaseAuthApi(private val httpClient: HttpClient, private val apiKey: String = FirebaseConfig.API_KEY) {
    suspend fun signUp(email: String, password: String): AuthTokens =
        accounts("signUp", EmailPasswordRequest(email, password)).decode<SignInResponse>().toTokens()

    suspend fun signIn(email: String, password: String): AuthTokens =
        accounts("signInWithPassword", EmailPasswordRequest(email, password)).decode<SignInResponse>().toTokens()

    suspend fun sendPasswordReset(email: String) {
        accounts("sendOobCode", OobCodeRequest(requestType = "PASSWORD_RESET", email = email))
    }

    suspend fun deleteAccount(idToken: String) {
        accounts("delete", IdTokenRequest(idToken))
    }

    suspend fun refresh(refreshToken: String): AuthTokens = call {
        httpClient.submitForm(
            url = SECURE_TOKEN_ENDPOINT,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        ) { parameter("key", apiKey) }
    }.decode<RefreshResponse>().let {
        AuthTokens(it.userId, email = null, it.idToken, it.refreshToken, it.expiresIn.toLong())
    }

    private suspend inline fun <reified T : Any> accounts(method: String, body: T): String = call {
        httpClient.post("$IDENTITY_TOOLKIT_ENDPOINT:$method") {
            parameter("key", apiKey)
            // OutgoingContent, so a client with ContentNegotiation sends it as is.
            setBody(TextContent(json.encodeToString(body), ContentType.Application.Json))
        }
    }

    /** Runs the request and returns the body of a successful response. */
    private suspend fun call(request: suspend () -> HttpResponse): String {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AuthException(AuthError.NETWORK, e)
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return text
        val code = runCatching { json.decodeFromString<ErrorResponse>(text).error.message }.getOrDefault("")
        throw AuthException(authErrorFor(code))
    }

    private inline fun <reified T> String.decode(): T = try {
        json.decodeFromString<T>(this)
    } catch (e: IllegalArgumentException) {
        throw AuthException(AuthError.NETWORK, e)
    }

    private companion object {
        const val IDENTITY_TOOLKIT_ENDPOINT = "https://identitytoolkit.googleapis.com/v1/accounts"
        const val SECURE_TOKEN_ENDPOINT = "https://securetoken.googleapis.com/v1/token"
        val json = Json {
            ignoreUnknownKeys = true
            // returnSecureToken is a default value but Firebase must receive it.
            encodeDefaults = true
        }
    }
}

/** Firebase sends codes like `EMAIL_EXISTS` or `WEAK_PASSWORD : Password should be at least 6 characters`. */
internal fun authErrorFor(message: String): AuthError = when (message.substringBefore(' ')) {
    "EMAIL_EXISTS" -> AuthError.EMAIL_IN_USE
    "INVALID_EMAIL", "MISSING_EMAIL" -> AuthError.INVALID_EMAIL
    "WEAK_PASSWORD", "MISSING_PASSWORD" -> AuthError.WEAK_PASSWORD
    "INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "USER_DISABLED" -> AuthError.WRONG_CREDENTIALS
    "TOO_MANY_ATTEMPTS_TRY_LATER" -> AuthError.TOO_MANY_ATTEMPTS
    "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "INVALID_ID_TOKEN", "USER_NOT_FOUND", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN" ->
        AuthError.SESSION_EXPIRED
    else -> AuthError.UNKNOWN
}

@Serializable
private data class EmailPasswordRequest(val email: String, val password: String, val returnSecureToken: Boolean = true)

@Serializable
private data class OobCodeRequest(val requestType: String, val email: String)

@Serializable
private data class IdTokenRequest(val idToken: String)

@Serializable
private data class SignInResponse(
    val localId: String,
    val email: String? = null,
    val idToken: String,
    val refreshToken: String,
    val expiresIn: String,
) {
    fun toTokens() = AuthTokens(localId, email, idToken, refreshToken, expiresIn.toLong())
}

@Serializable
private data class RefreshResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("id_token") val idToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: String,
)

@Serializable
private data class ErrorResponse(val error: ErrorBody)

@Serializable
private data class ErrorBody(val message: String = "")
