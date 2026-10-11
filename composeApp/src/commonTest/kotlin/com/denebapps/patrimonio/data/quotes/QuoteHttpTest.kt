package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuoteHttpTest {
    @Test
    fun `sends the browser User-Agent header`() = runTest {
        var userAgent: String? = null
        val http = quoteHttp { request ->
            userAgent = request.headers[HttpHeaders.UserAgent]
            respondJson("{}")
        }

        http.get("https://example.test/x")

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Safari/537.36",
            userAgent,
        )
    }

    @Test
    fun `retries on 429 and 5xx with linear backoff and then succeeds`() = runTest {
        val statuses = ArrayDeque(listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.BadGateway))
        val delays = mutableListOf<Long>()
        var calls = 0
        val http = quoteHttp(delays) {
            calls++
            val status = statuses.removeFirstOrNull()
            if (status != null) respondJson("", status) else respondJson("""{"ok":true}""")
        }

        val body = http.get("https://example.test/x")

        assertEquals("""{"ok":true}""", body)
        assertEquals(3, calls)
        assertEquals(listOf(1_500L, 3_000L), delays)
    }

    @Test
    fun `gives up after three attempts with a domain exception`() = runTest {
        var calls = 0
        val delays = mutableListOf<Long>()
        val http = quoteHttp(delays) {
            calls++
            respondJson("", HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<QuoteUnavailableException> { http.get("https://example.test/x") }

        assertEquals(3, calls)
        assertEquals(listOf(1_500L, 3_000L), delays)
    }

    @Test
    fun `does not retry a 404`() = runTest {
        var calls = 0
        val http = quoteHttp {
            calls++
            respondJson("{}", HttpStatusCode.NotFound)
        }

        assertFailsWith<QuoteUnavailableException> { http.get("https://example.test/x") }

        assertEquals(1, calls)
    }

    @Test
    fun `retries network errors and wraps the last one`() = runTest {
        var calls = 0
        val http = quoteHttp {
            calls++
            throw IllegalStateException("socket closed")
        }

        val failure = assertFailsWith<QuoteUnavailableException> { http.get("https://example.test/x") }

        assertEquals(3, calls)
        assertEquals("socket closed", failure.cause?.message)
    }

    @Test
    fun `recovers when a network error is followed by a good answer`() = runTest {
        var calls = 0
        val http = quoteHttp {
            calls++
            if (calls == 1) throw IllegalStateException("reset") else respondJson("ok")
        }

        assertEquals("ok", http.get("https://example.test/x"))
        assertEquals(2, calls)
    }
}
