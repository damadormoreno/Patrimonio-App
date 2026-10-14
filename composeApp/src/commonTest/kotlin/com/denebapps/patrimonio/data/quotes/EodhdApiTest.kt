package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EodhdApiTest {
    private val key = "test-key-123"
    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6")

    private fun api(requests: MutableList<HttpRequestData> = mutableListOf(), handler: MockHandler) = EodhdApi(
        mockClient { request ->
            requests += request
            handler(request)
        },
        FixedClock(FIXED_NOW),
    )

    private fun eur(scaled: Long) = Price(scaled, Currency.EUR)

    @Test
    fun `search asks for the query with the key and json format and maps the symbols`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respondJson(SEARCH_JSON) }

        val symbols = api.search(key, "IE00BD0NCM55")

        val request = requests.single()
        assertEquals("/api/search/IE00BD0NCM55", request.url.encodedPath)
        assertEquals("eodhd.com", request.url.host)
        assertEquals(key, request.url.parameters["api_token"])
        assertEquals("json", request.url.parameters["fmt"])
        assertEquals(
            EodhdSymbol(
                code = "IE00BD0NCM55",
                exchange = "EUFUND",
                name = "iShares Developed World Index Fund",
                type = "FUND",
                currency = "EUR",
                isin = "IE00BD0NCM55",
                primary = false,
            ),
            symbols.first(),
        )
        assertEquals("IE00BD0NCM55.EUFUND", symbols.first().symbol)
        // The row without exchange is dropped; a row without ISIN or currency is kept with nulls.
        assertEquals(listOf("IE00BD0NCM55.EUFUND", "IWDA.AS"), symbols.map { it.symbol })
        assertNull(symbols.last().isin)
        assertNull(symbols.last().currency)
        assertTrue(symbols.last().primary)
    }

    @Test
    fun `search encodes the query as a path segment and answers an empty list when nothing matches`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respondJson("[]") }

        val symbols = api.search(key, "msci world")

        assertTrue(symbols.isEmpty())
        assertEquals("/api/search/msci%20world", requests.single().url.encodedPath)
    }

    @Test
    fun `history sends from and maps the close, not the adjusted close, ascending without duplicate days`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respondJson(EOD_JSON) }

        val points = api.history(key, "IWDA.AS", "EUR", LocalDate(2026, 10, 1))

        val request = requests.single()
        assertEquals("/api/eod/IWDA.AS", request.url.encodedPath)
        assertEquals(key, request.url.parameters["api_token"])
        assertEquals("json", request.url.parameters["fmt"])
        assertEquals("2026-10-01", request.url.parameters["from"])
        // 2026-10-11 has no close and is skipped; 2026-10-12 appears twice and the later row wins.
        assertEquals(
            listOf(
                PricePoint(LocalDate(2026, 10, 9), eur(29_500_000)),
                PricePoint(LocalDate(2026, 10, 12), eur(29_686_000)),
            ),
            points,
        )
    }

    @Test
    fun `history without from does not send the parameter`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respondJson(EOD_JSON) }

        api.history(key, "IWDA.AS", "EUR", null)

        assertFalse(requests.single().url.parameters.contains("from"))
    }

    @Test
    fun `history answers an empty list for an empty window`() = runTest {
        val api = api { respondJson("[]") }

        assertTrue(api.history(key, "IWDA.AS", "EUR", LocalDate(2026, 10, 1)).isEmpty())
    }

    @Test
    fun `history converts pence to pounds for GBX and GBp`() = runTest {
        val api = api { respondJson("""[{"date":"2026-10-12","close":1234.5},{"date":"2026-10-13","close":1000}]""") }

        val fromGbx = api.history(key, "VUSA.LSE", "GBX", null)
        val fromGbp = api.history(key, "VUSA.LSE", "GBp", null)

        val expected = listOf(
            PricePoint(LocalDate(2026, 10, 12), Price(12_345_000, Currency.GBP)),
            PricePoint(LocalDate(2026, 10, 13), Price(10_000_000, Currency.GBP)),
        )
        assertEquals(expected, fromGbx)
        assertEquals(expected, fromGbp)
    }

    @Test
    fun `history keeps a plain GBP price as pounds`() = runTest {
        val api = api { respondJson("""[{"date":"2026-10-12","close":12.345}]""") }

        assertEquals(Price(12_345_000, Currency.GBP), api.history(key, "X.LSE", "GBP", null).single().price)
    }

    @Test
    fun `history rejects a currency the app does not support`() = runTest {
        val api = api { respondJson(EOD_JSON) }

        assertFailsWith<QuoteUnavailableException> { api.history(key, "NESN.SW", "CHF", null) }
        assertFailsWith<QuoteUnavailableException> { api.history(key, "NESN.SW", null, null) }
    }

    @Test
    fun `history rejects a payload that is not a list of rows`() = runTest {
        val api = api { respondJson("""{"message":"surprise"}""") }

        assertFailsWith<QuoteUnavailableException> { api.history(key, "IWDA.AS", "EUR", null) }
    }

    @Test
    fun `latestQuote reads a fourteen day window and returns its last row for the requested ref`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respondJson(EOD_JSON) }

        val quote = api.latestQuote(key, "IWDA.AS", "EUR", fundRef)

        assertEquals("2025-10-02", requests.single().url.parameters["from"])
        assertEquals("/api/eod/IWDA.AS", requests.single().url.encodedPath)
        assertEquals(Quote(fundRef, eur(29_686_000), LocalDate(2026, 10, 12)), quote)
    }

    @Test
    fun `latestQuote fails when the window has no close`() = runTest {
        val api = api { respondJson("[]") }

        val failure = assertFailsWith<QuoteUnavailableException> { api.latestQuote(key, "IWDA.AS", "EUR", fundRef) }

        assertFalse(failure is EodhdQuotaExceededException || failure is EodhdInvalidKeyException)
    }

    @Test
    fun `usage reads the calls made today and the daily limit from the user endpoint`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respondJson(USER_JSON) }

        val usage = api.usage(key)

        assertEquals("/api/user", requests.single().url.encodedPath)
        assertEquals(key, requests.single().url.parameters["api_token"])
        assertEquals(EodhdUsage(apiRequests = 6, dailyRateLimit = 20, subscriptionType = "free"), usage)
    }

    @Test
    fun `usage accepts numbers sent as strings and rejects an answer without the counters`() = runTest {
        val lenient = api {
            respondJson("""{"subscriptionType":"free","apiRequests":"7","dailyRateLimit":"20"}""")
        }
        val broken = api { respondJson("""{"subscriptionType":"free"}""") }

        assertEquals(EodhdUsage(7, 20, "free"), lenient.usage(key))
        assertFailsWith<QuoteUnavailableException> { broken.usage(key) }
    }

    @Test
    fun `a 401 is an invalid key`() = runTest {
        val api = api { respondJson("Unauthenticated", HttpStatusCode.Unauthorized) }

        val failure = assertFailsWith<EodhdInvalidKeyException> { api.usage(key) }

        assertIs<QuoteUnavailableException>(failure)
        assertEquals("EODHD /user HTTP 401", failure.message)
    }

    @Test
    fun `a 402 and a 429 are an exhausted quota`() = runTest {
        for (status in listOf(HttpStatusCode.PaymentRequired, HttpStatusCode.TooManyRequests)) {
            val api = api { respondJson("limit", status) }

            val failure = assertFailsWith<EodhdQuotaExceededException> { api.history(key, "IWDA.AS", "EUR", null) }

            assertEquals("EODHD /eod/IWDA.AS HTTP ${status.value}", failure.message)
        }
    }

    @Test
    fun `a 404, a 403 and a server error are plain unavailable failures`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            val api = api { respondJson("nope", status) }

            val failure = assertFailsWith<QuoteUnavailableException> { api.search(key, "XYZ") }

            assertEquals(QuoteUnavailableException::class, failure::class)
            assertEquals("EODHD /search/XYZ HTTP ${status.value}", failure.message)
        }
    }

    @Test
    fun `no failure is retried`() = runTest {
        for (status in listOf(
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.PaymentRequired,
            HttpStatusCode.BadGateway,
        )) {
            val requests = mutableListOf<HttpRequestData>()
            val api = api(requests) { respondJson("x", status) }

            assertFailsWith<QuoteUnavailableException> { api.history(key, "IWDA.AS", "EUR", null) }

            assertEquals(1, requests.size, "HTTP ${status.value} must not be retried")
        }
    }

    @Test
    fun `a network failure is not retried and neither its message nor its cause carry the key`() = runTest {
        var calls = 0
        val api = api {
            calls++
            error("Connect timeout has expired [url=https://eodhd.com/api/eod/IWDA.AS?api_token=$key&fmt=json]")
        }

        val failure = assertFailsWith<QuoteUnavailableException> { api.history(key, "IWDA.AS", "EUR", null) }

        assertEquals(1, calls)
        assertEquals("EODHD /eod/IWDA.AS network error (IllegalStateException)", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `the key never appears in the message or the cause of any failure`() = runTest {
        val failures = mutableListOf<Throwable>()
        val statuses = listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.PaymentRequired,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.NotFound,
            HttpStatusCode.InternalServerError,
        )
        for (status in statuses) {
            failures += assertFailsWith<QuoteUnavailableException> {
                api { respondJson("api_token=$key", status) }.history(key, "IWDA.AS", "EUR", null)
            }
        }
        failures += assertFailsWith<QuoteUnavailableException> {
            api { respondJson("not json api_token=$key") }.history(key, "IWDA.AS", "EUR", null)
        }
        failures += assertFailsWith<QuoteUnavailableException> {
            api { error("api_token=$key") }.latestQuote(key, "IWDA.AS", "EUR", fundRef)
        }
        failures += assertFailsWith<QuoteUnavailableException> {
            api { error("api_token=$key") }.search(key, "IWDA")
        }
        failures += assertFailsWith<QuoteUnavailableException> {
            api { error("api_token=$key") }.usage(key)
        }

        assertTrue(failures.size >= 9)
        for (failure in failures) {
            var current: Throwable? = failure
            while (current != null) {
                assertFalse(current.message.orEmpty().contains(key), "key leaked in: ${current.message}")
                assertFalse(current.toString().contains(key), "key leaked in: $current")
                current = current.cause
            }
        }
        assertNotNull(failures.firstOrNull { it is EodhdInvalidKeyException })
        assertNotNull(failures.firstOrNull { it is EodhdQuotaExceededException })
    }

    private companion object {
        val SEARCH_JSON = """
            [
              {"Code":"IE00BD0NCM55","Exchange":"EUFUND","Name":"iShares Developed World Index Fund","Type":"FUND",
               "Country":"Unknown","Currency":"EUR","ISIN":"IE00BD0NCM55","isPrimary":false,
               "previousClose":29.686,"previousCloseDate":"2026-10-12"},
              {"Code":"NOEXCHANGE","Name":"Broken row","Type":"FUND"},
              {"Code":"IWDA","Exchange":"AS","Name":"iShares Core MSCI World","Type":"ETF","isPrimary":true}
            ]
        """.trimIndent()

        val EOD_JSON = """
            [
              {"date":"2026-10-09","open":29.4,"high":29.6,"low":29.3,"close":29.5,"adjusted_close":29.1,"volume":0},
              {"date":"2026-10-11","open":29.4,"high":29.6,"low":29.3,"close":null,"adjusted_close":null,"volume":0},
              {"date":"2026-10-12","open":29.4,"high":29.6,"low":29.3,"close":29.0,"adjusted_close":29.0,"volume":0},
              {"date":"2026-10-12","open":29.4,"high":29.7,"low":29.3,"close":29.686,"adjusted_close":29.2,"volume":0}
            ]
        """.trimIndent()

        val USER_JSON = """
            {"name":"Someone","email":"someone@example.com","subscriptionType":"free","paymentMethod":"None",
             "apiRequests":6,"apiRequestsDate":"2026-10-14","dailyRateLimit":20,"extraLimit":500,
             "inplanLimit":0,"inplanRequests":0}
        """.trimIndent()
    }
}
