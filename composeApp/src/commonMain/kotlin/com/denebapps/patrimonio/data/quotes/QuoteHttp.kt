package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shared transport of the quote adapters: sends the browser User-Agent the public endpoints expect and retries
 * transient failures (HTTP 429, HTTP 5xx and network errors, timeouts included) up to [MAX_ATTEMPTS] attempts in
 * total with linear backoff ([BACKOFF_MILLIS] times the number of failed attempts), like the reference app rumbo
 * (MIT, github.com/danidm98/rumbo) does. Any other HTTP status is final. Every failure surfaces as a
 * [QuoteUnavailableException]; transport exceptions never leak.
 *
 * Each request sets its own timeout (the shared client installs `HttpTimeout` without defaults). [delayMillis] is injectable so
 * tests do not wait.
 */
class QuoteHttp(
    private val client: HttpClient,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    /** Returns the body of a successful `GET` on [url], which is sent exactly as given. */
    suspend fun get(url: String): String {
        var lastFailure: TransientFailure? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) delayMillis(BACKOFF_MILLIS * (attempt - 1))
            try {
                return fetch(url)
            } catch (failure: TransientFailure) {
                lastFailure = failure
            }
        }
        val failure = checkNotNull(lastFailure)
        throw QuoteUnavailableException("${failure.message} after $MAX_ATTEMPTS attempts", failure.cause)
    }

    private suspend fun fetch(url: String): String {
        val (status, body) = try {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
            response.status to response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw TransientFailure(failure.message ?: "network error", failure)
        }
        return when {
            status.isSuccess() -> body
            status == HttpStatusCode.TooManyRequests || status.value >= SERVER_ERROR_FROM ->
                throw TransientFailure("HTTP ${status.value}", null)
            else -> throw QuoteUnavailableException("HTTP ${status.value} from the quote provider")
        }
    }

    private class TransientFailure(message: String, cause: Throwable?) : Exception(message, cause)

    companion object {
        /** Chrome User-Agent used by rumbo (`motor.py`); the public endpoints reject default client agents. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MILLIS = 1_500L
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val SERVER_ERROR_FROM = 500
    }
}
