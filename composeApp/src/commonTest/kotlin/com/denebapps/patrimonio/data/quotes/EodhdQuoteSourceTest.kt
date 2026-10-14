package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.datasources.CachedSymbol
import com.denebapps.patrimonio.data.datasources.DataSourceSettings
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.testing.FakeDataSourceSettingsStore
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EodhdQuoteSourceTest {
    private val key = "test-key-123"
    private val today = LocalDate(2025, 10, 16)
    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6")
    private val etfRef = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS")
    private val bitcoin = InstrumentRef(QuoteProvider.COINGECKO, "bitcoin")
    private val fundIdentity = InstrumentIdentity(isin = "IE00BD0NCM55", ticker = null, currencyCode = "EUR")
    private val etfIdentity = InstrumentIdentity(isin = null, ticker = "IWDA.AS", currencyCode = "EUR")

    private fun eur(scaled: Long) = Price(scaled, Currency.EUR)

    private val delegateQuote = Quote(fundRef, eur(10_000_000), LocalDate(2025, 10, 15))
    private val delegateHistory = listOf(PricePoint(LocalDate(2025, 10, 15), eur(10_000_000)))

    private val settings = FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key))
    private val delegate = ScriptedMarketData()
    private val server = EodhdServer()
    private val identities = mutableMapOf(fundRef to fundIdentity, etfRef to etfIdentity)

    private fun source() = EodhdQuoteSource(
        delegate = delegate,
        eodhd = server.api(),
        settings = settings,
        identities = InstrumentIdentityLookup { ref -> identities[ref] },
        clock = FixedClock(FIXED_NOW),
    )

    // --- pass-through -------------------------------------------------------------------------------------------

    @Test
    fun `without a key every call goes to the delegate untouched and EODHD is never asked`() = runTest {
        val settings = FakeDataSourceSettingsStore()
        val source = EodhdQuoteSource(
            delegate,
            server.api(),
            settings,
            InstrumentIdentityLookup { identities[it] },
            FixedClock(FIXED_NOW),
        )
        delegate.quote = delegateQuote
        delegate.history = delegateHistory

        assertSame(delegateQuote, source.latestQuote(fundRef))
        assertSame(delegateHistory, source.priceHistory(fundRef, null))
        delegate.quote = null
        val failure = assertFailsWith<QuoteUnavailableException> { source.latestQuote(fundRef) }

        assertSame(delegate.lastFailure, failure)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a blank key counts as no key`() = runTest {
        settings.setApiKey("   ")
        delegate.quote = null

        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `an invalid key flag and a quota exhausted today pass every call through`() = runTest {
        delegate.quote = null
        settings.markInvalidKey(key)
        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }
        assertTrue(server.requests.isEmpty())

        settings.setApiKey(key)
        settings.markQuotaExhausted(key, today)
        assertFailsWith<QuoteUnavailableException> { source().priceHistory(fundRef, null) }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a quota exhausted on an earlier day does not stop EODHD`() = runTest {
        settings.markQuotaExhausted(key, LocalDate(2025, 10, 15))
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON

        val quote = source().latestQuote(fundRef)

        assertEquals(eur(29_686_000), quote.price)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `a crypto never uses EODHD, whichever the mode and whatever fails`() = runTest {
        identities[bitcoin] = InstrumentIdentity(isin = "IE00BD0NCM55", ticker = "BTC", currencyCode = "EUR")
        delegate.quote = null
        delegate.history = null

        for (primary in listOf(false, true)) {
            settings.setEodhdPrimary(primary)
            assertFailsWith<QuoteUnavailableException> { source().latestQuote(bitcoin) }
            assertFailsWith<QuoteUnavailableException> { source().priceHistory(bitcoin, null) }
        }

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `an instrument that is not a holding, or has neither ISIN nor ticker, passes through`() = runTest {
        identities.remove(fundRef)
        delegate.quote = null
        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        identities[fundRef] = InstrumentIdentity(isin = null, ticker = null, currencyCode = "EUR")
        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        identities[fundRef] = InstrumentIdentity(isin = "not-an-isin", ticker = "  ", currencyCode = "EUR")
        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `search and fundamentals always go to the delegate`() = runTest {
        settings.setEodhdPrimary(true)
        val fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 1_800)
        delegate.fundamentals = fundamentals

        assertEquals(listOf<InstrumentCandidate>(), source().search("msci"))
        assertSame(fundamentals, source().fundamentals("IE00BD0NCM55", fundRef))

        assertEquals(listOf("msci"), delegate.searches)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a failure that is not an unavailable quote is not hidden by EODHD`() = runTest {
        delegate.failureToThrow = IllegalStateException("bug")

        assertFailsWith<IllegalStateException> { source().latestQuote(fundRef) }

        assertTrue(server.requests.isEmpty())
    }

    // --- fallback mode (the default) ----------------------------------------------------------------------------

    @Test
    fun `fallback mode does not call EODHD while the delegate answers`() = runTest {
        delegate.quote = delegateQuote
        delegate.history = delegateHistory

        assertSame(delegateQuote, source().latestQuote(fundRef))
        assertSame(delegateHistory, source().priceHistory(fundRef, LocalDate(2025, 10, 1)))

        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `fallback mode asks EODHD only after the delegate fails and answers for the same ref`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON

        val quote = source().latestQuote(fundRef)

        assertEquals(1, delegate.quoteCalls)
        assertEquals(Quote(fundRef, eur(29_686_000), LocalDate(2025, 10, 14)), quote)
        assertEquals(listOf("/api/search/IE00BD0NCM55", "/api/eod/IE00BD0NCM55.EUFUND"), server.paths)
        assertEquals(key, server.requests.first().url.parameters["api_token"])
    }

    @Test
    fun `fallback mode serves the history from EODHD with the requested start when the delegate fails`() = runTest {
        delegate.history = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON

        val points = source().priceHistory(fundRef, LocalDate(2025, 10, 1))

        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 13), eur(29_500_000)),
                PricePoint(LocalDate(2025, 10, 14), eur(29_686_000)),
            ),
            points,
        )
        assertEquals("2025-10-01", server.requests.last().url.parameters["from"])
    }

    @Test
    fun `when both fail the delegate's own exception is rethrown`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        server.eodStatus = HttpStatusCode.InternalServerError

        val failure = assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertSame(delegate.lastFailure, failure)
        assertEquals("delegate down", failure.message)
    }

    // --- primary mode -------------------------------------------------------------------------------------------

    @Test
    fun `primary mode asks EODHD first and does not touch the delegate when it answers`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.quote = delegateQuote
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON

        val quote = source().latestQuote(fundRef)

        assertEquals(eur(29_686_000), quote.price)
        assertEquals(0, delegate.quoteCalls)
    }

    @Test
    fun `primary mode falls back to the delegate when EODHD fails`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.quote = delegateQuote
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        server.eodStatus = HttpStatusCode.InternalServerError

        assertSame(delegateQuote, source().latestQuote(fundRef))
        assertEquals(1, delegate.quoteCalls)
    }

    @Test
    fun `primary mode falls back to the delegate for a history EODHD has none of`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.history = delegateHistory
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        server.eodAnswer = "[]"

        // An empty window since a date is a legitimate answer; an empty answer to "everything" is not.
        assertEquals(emptyList(), source().priceHistory(fundRef, LocalDate(2025, 10, 1)))
        assertSame(delegateHistory, source().priceHistory(fundRef, null))
    }

    // --- quota and key flags ------------------------------------------------------------------------------------

    @Test
    fun `an exhausted quota is remembered for the day and EODHD is skipped from then on`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        server.eodStatus = HttpStatusCode.PaymentRequired
        val source = source()

        val failure = assertFailsWith<QuoteUnavailableException> { source.latestQuote(fundRef) }

        assertSame(delegate.lastFailure, failure)
        assertEquals(today, settings.current().quotaExhaustedOn)
        assertFalse(settings.current().invalidKey)
        val calls = server.requests.size
        assertFailsWith<QuoteUnavailableException> { source.latestQuote(fundRef) }
        assertEquals(calls, server.requests.size)
    }

    @Test
    fun `a 429 is an exhausted quota too and primary mode then answers from the delegate`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.quote = delegateQuote
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        server.eodStatus = HttpStatusCode.TooManyRequests

        assertSame(delegateQuote, source().latestQuote(fundRef))

        assertEquals(today, settings.current().quotaExhaustedOn)
    }

    @Test
    fun `an exhausted quota during the search is remembered as well`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.quote = delegateQuote
        server.searchStatus = HttpStatusCode.PaymentRequired

        assertSame(delegateQuote, source().latestQuote(fundRef))

        assertEquals(today, settings.current().quotaExhaustedOn)
    }

    @Test
    fun `a refused key is flagged invalid and EODHD is skipped from then on`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.quote = delegateQuote
        server.searchStatus = HttpStatusCode.Unauthorized
        val source = source()

        assertSame(delegateQuote, source.latestQuote(fundRef))

        assertTrue(settings.current().invalidKey)
        assertNull(settings.current().quotaExhaustedOn)
        val calls = server.requests.size
        assertSame(delegateQuote, source.latestQuote(fundRef))
        assertEquals(calls, server.requests.size)
    }

    @Test
    fun `a refused key in fallback mode rethrows the delegate failure, not the EODHD one`() = runTest {
        delegate.quote = null
        server.searchStatus = HttpStatusCode.Unauthorized

        val failure = assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertSame(delegate.lastFailure, failure)
        assertFalse(failure is EodhdInvalidKeyException)
        assertTrue(settings.current().invalidKey)
    }

    @Test
    fun `a new key lifts the invalid flag and the quota day`() = runTest {
        settings.markInvalidKey(key)
        settings.markQuotaExhausted(key, today)

        settings.setApiKey("another-key")
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON

        assertEquals(eur(29_686_000), source().latestQuote(fundRef).price)
        assertEquals("another-key", server.requests.first().url.parameters["api_token"])
    }

    // --- symbol resolution and cache ----------------------------------------------------------------------------

    @Test
    fun `a resolved symbol is cached so the second price costs no search`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        val source = source()

        source.latestQuote(fundRef)
        source.latestQuote(fundRef)
        source.priceHistory(fundRef, null)

        assertEquals(1, server.searches.size)
        assertEquals(3, server.eods.size)
        assertEquals(listOf("ISIN:IE00BD0NCM55"), settings.cacheWrites)
        assertEquals(
            CachedSymbol("IE00BD0NCM55.EUFUND", "EUR", today),
            settings.cachedSymbol("ISIN:IE00BD0NCM55"),
        )
    }

    @Test
    fun `an instrument EODHD does not know is remembered and not searched again`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = "[]"
        val source = source()

        assertFailsWith<QuoteUnavailableException> { source.latestQuote(fundRef) }
        assertFailsWith<QuoteUnavailableException> { source.latestQuote(fundRef) }

        assertEquals(1, server.searches.size)
        assertTrue(server.eods.isEmpty())
        assertEquals(CachedSymbol(null, null, today), settings.cachedSymbol("ISIN:IE00BD0NCM55"))
    }

    @Test
    fun `a not on EODHD answer older than thirty days is searched again, a younger one is not`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = FUND_SEARCH_JSON
        settings.cacheSymbol("ISIN:IE00BD0NCM55", CachedSymbol(null, null, LocalDate(2025, 9, 17)))
        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }
        assertTrue(server.requests.isEmpty())

        settings.cacheSymbol("ISIN:IE00BD0NCM55", CachedSymbol(null, null, LocalDate(2025, 9, 16)))
        assertEquals(eur(29_686_000), source().latestQuote(fundRef).price)
        assertEquals(1, server.searches.size)
        assertEquals("IE00BD0NCM55.EUFUND", settings.cachedSymbol("ISIN:IE00BD0NCM55")?.symbol)
    }

    @Test
    fun `a failed search is not cached as a negative answer`() = runTest {
        delegate.quote = null
        server.searchStatus = HttpStatusCode.InternalServerError

        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertTrue(settings.cacheWrites.isEmpty())
    }

    @Test
    fun `the ISIN search keeps only the listings with that ISIN and prefers the holding's currency`() = runTest {
        delegate.quote = null
        identities[etfRef] = InstrumentIdentity(isin = "IE00B4L5Y983", ticker = "IWDA.AS", currencyCode = "EUR")
        server.searchAnswers["IE00B4L5Y983"] = ETF_SEARCH_JSON

        val quote = source().latestQuote(etfRef)

        assertEquals(etfRef, quote.ref)
        assertEquals("/api/eod/IWDA.AS", server.eods.single().url.encodedPath)
        assertEquals("ISIN:IE00B4L5Y983", settings.cacheWrites.single())
    }

    @Test
    fun `the fund listing is preferred to the others for a fund ISIN`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = """
            [{"Code":"IE00BD0NCM55","Exchange":"XETRA","Type":"ETF","Currency":"EUR","ISIN":"IE00BD0NCM55"},
             {"Code":"IE00BD0NCM55","Exchange":"EUFUND","Type":"FUND","Currency":"EUR","ISIN":"IE00BD0NCM55"}]
        """.trimIndent()

        source().latestQuote(fundRef)

        assertEquals("/api/eod/IE00BD0NCM55.EUFUND", server.eods.single().url.encodedPath)
    }

    @Test
    fun `a listing in another currency than the holding's is not a match`() = runTest {
        delegate.quote = null
        server.searchAnswers["IE00BD0NCM55"] = """
            [{"Code":"IE00BD0NCM55","Exchange":"EUFUND","Type":"FUND","Currency":"USD","ISIN":"IE00BD0NCM55"}]
        """.trimIndent()

        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertTrue(server.eods.isEmpty())
        assertNull(settings.cachedSymbol("ISIN:IE00BD0NCM55")?.symbol)
        assertEquals(1, settings.cacheWrites.size)
    }

    @Test
    fun `a London listing in pence matches a holding in pounds and is priced in pounds`() = runTest {
        val vusa = InstrumentRef(QuoteProvider.YAHOO, "VUSA.L")
        identities[vusa] = InstrumentIdentity(isin = "IE00B3XXRP09", ticker = "VUSA.L", currencyCode = "GBP")
        delegate.quote = null
        server.searchAnswers["IE00B3XXRP09"] = """
            [{"Code":"VUSA","Exchange":"LSE","Type":"ETF","Currency":"GBX","ISIN":"IE00B3XXRP09"}]
        """.trimIndent()
        server.eodAnswer = """[{"date":"2025-10-14","close":8123.5}]"""

        val quote = source().latestQuote(vusa)

        assertEquals(Price(81_235_000, Currency.GBP), quote.price)
        assertEquals("/api/eod/VUSA.LSE", server.eods.single().url.encodedPath)
        assertEquals("GBX", settings.cachedSymbol("ISIN:IE00B3XXRP09")?.currency)
    }

    @Test
    fun `a quote in another currency than the holding's is never returned`() = runTest {
        settings.setEodhdPrimary(true)
        delegate.quote = delegateQuote
        // The symbol is cached from an earlier holding quoted in USD; this holding is in EUR.
        settings.cacheSymbol("ISIN:IE00BD0NCM55", CachedSymbol("IE00BD0NCM55.EUFUND", "USD", today))
        server.forbidSearch = true

        assertSame(delegateQuote, source().latestQuote(fundRef))
    }

    @Test
    fun `a ticker resolves through its exchange suffix, searching for the bare code`() = runTest {
        delegate.quote = null
        server.searchAnswers["IWDA"] = TICKER_SEARCH_JSON

        val quote = source().latestQuote(etfRef)

        assertEquals(etfRef, quote.ref)
        assertEquals(listOf("/api/search/IWDA", "/api/eod/IWDA.AS"), server.paths)
        assertEquals("TICKER:IWDA.AS", settings.cacheWrites.single())
    }

    @Test
    fun `Yahoo suffixes map to the EODHD exchange codes`() = runTest {
        val expected = mapOf(
            "IWDA.AS" to ("IWDA" to "AS"),
            "SAP.DE" to ("SAP" to "XETRA"),
            "VUSA.L" to ("VUSA" to "LSE"),
            "SAN.MC" to ("SAN" to "MC"),
            "AAPL" to ("AAPL" to "US"),
            "BRK-B" to ("BRK-B" to "US"),
            "ODD.XX" to ("ODD" to "XX"),
        )
        for ((yahoo, parts) in expected) {
            assertEquals(parts, eodhdTickerParts(yahoo), yahoo)
        }
        assertNull(eodhdTickerParts("^GSPC"))
        assertNull(eodhdTickerParts("EURUSD=X"))
        assertNull(eodhdTickerParts("0P00012PP6.F"))
        assertNull(eodhdTickerParts(" "))
    }

    @Test
    fun `a plain US ticker resolves to its US listing`() = runTest {
        val apple = InstrumentRef(QuoteProvider.YAHOO, "AAPL")
        identities[apple] = InstrumentIdentity(isin = null, ticker = "AAPL", currencyCode = "USD")
        delegate.quote = null
        server.searchAnswers["AAPL"] = """
            [{"Code":"AAPL","Exchange":"MX","Type":"Common Stock","Currency":"MXN"},
             {"Code":"AAPL","Exchange":"US","Type":"Common Stock","Currency":"USD","isPrimary":true}]
        """.trimIndent()

        val quote = source().latestQuote(apple)

        assertEquals(Currency.USD, quote.price.currency)
        assertEquals("/api/eod/AAPL.US", server.eods.single().url.encodedPath)
    }

    @Test
    fun `a Yahoo holding without a stored ticker uses its Yahoo symbol, a Morningstar one does not`() = runTest {
        identities[etfRef] = InstrumentIdentity(isin = null, ticker = null, currencyCode = "EUR")
        identities[fundRef] = InstrumentIdentity(isin = null, ticker = null, currencyCode = "EUR")
        delegate.quote = null
        server.searchAnswers["IWDA"] = TICKER_SEARCH_JSON

        assertEquals(etfRef, source().latestQuote(etfRef).ref)
        assertFailsWith<QuoteUnavailableException> { source().latestQuote(fundRef) }

        assertEquals(listOf("/api/search/IWDA", "/api/eod/IWDA.AS"), server.paths)
    }

    @Test
    fun `an ISIN that EODHD lacks falls through to the ticker`() = runTest {
        identities[etfRef] = InstrumentIdentity(isin = "IE00B4L5Y983", ticker = "IWDA.AS", currencyCode = "EUR")
        delegate.quote = null
        server.searchAnswers["IE00B4L5Y983"] = "[]"
        server.searchAnswers["IWDA"] = TICKER_SEARCH_JSON

        source().latestQuote(etfRef)

        assertEquals(listOf("/api/search/IE00B4L5Y983", "/api/search/IWDA", "/api/eod/IWDA.AS"), server.paths)
        assertEquals("ISIN:IE00B4L5Y983", settings.cacheWrites.single())
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** The delegate: answers [quote] and [history], or fails with a [QuoteUnavailableException] when they are `null`. */
    private inner class ScriptedMarketData : MarketDataSource {
        var quote: Quote? = null
        var history: List<PricePoint>? = null
        var fundamentals: InstrumentFundamentals? = null
        var failureToThrow: Exception? = null
        var lastFailure: QuoteUnavailableException? = null
        var quoteCalls = 0
        val searches = mutableListOf<String>()

        private fun failure(): Exception =
            failureToThrow ?: QuoteUnavailableException("delegate down").also { lastFailure = it }

        override suspend fun search(query: String): List<InstrumentCandidate> {
            searches += query
            return emptyList()
        }

        override suspend fun latestQuote(ref: InstrumentRef): Quote {
            quoteCalls++
            return quote ?: throw failure()
        }

        override suspend fun priceHistory(ref: InstrumentRef, from: LocalDate?): List<PricePoint> =
            history ?: throw failure()

        override suspend fun fundamentals(isin: String, preferred: InstrumentRef?): InstrumentFundamentals? =
            fundamentals
    }

    /** A scripted EODHD: [searchAnswers] by query, one [eodAnswer], and a status for each of the two endpoints. */
    private class EodhdServer {
        val requests = mutableListOf<HttpRequestData>()
        val searchAnswers = mutableMapOf<String, String>()
        var eodAnswer = EOD_JSON
        var searchStatus = HttpStatusCode.OK
        var eodStatus = HttpStatusCode.OK

        /** A search is a test failure (the symbol must come from the cache). */
        var forbidSearch = false

        val paths get() = requests.map { it.url.encodedPath }
        val searches get() = requests.filter { it.url.encodedPath.startsWith("/api/search/") }
        val eods get() = requests.filter { it.url.encodedPath.startsWith("/api/eod/") }

        fun api() = EodhdApi(
            mockClient { request ->
                requests += request
                val path = request.url.encodedPath
                when {
                    path.startsWith("/api/search/") -> {
                        check(!forbidSearch) { "unexpected search" }
                        val query = path.removePrefix("/api/search/")
                        respondJson(searchAnswers[query] ?: "[]", searchStatus)
                    }
                    else -> respondJson(eodAnswer, eodStatus)
                }
            },
            FixedClock(FIXED_NOW),
        )
    }

    private companion object {
        val FUND_SEARCH_JSON = """
            [{"Code":"IE00BD0NCM55","Exchange":"EUFUND","Name":"iShares Developed World Index Fund","Type":"FUND",
              "Currency":"EUR","ISIN":"IE00BD0NCM55","isPrimary":false}]
        """.trimIndent()

        val ETF_SEARCH_JSON = """
            [{"Code":"SWDA","Exchange":"LSE","Type":"ETF","Currency":"GBX","ISIN":"IE00B4L5Y983","isPrimary":true},
             {"Code":"IWDA","Exchange":"AS","Type":"ETF","Currency":"EUR","ISIN":"IE00B4L5Y983","isPrimary":false},
             {"Code":"IWDA","Exchange":"MI","Type":"ETF","Currency":"EUR","ISIN":"IE00B4L5Y983","isPrimary":false},
             {"Code":"OTHER","Exchange":"AS","Type":"ETF","Currency":"EUR","ISIN":"IE0000000000","isPrimary":true}]
        """.trimIndent()

        val TICKER_SEARCH_JSON = """
            [{"Code":"IWDA","Exchange":"LSE","Type":"ETF","Currency":"GBX","ISIN":"IE00B4L5Y983"},
             {"Code":"IWDA","Exchange":"AS","Type":"ETF","Currency":"EUR","ISIN":"IE00B4L5Y983"},
             {"Code":"IWDAX","Exchange":"AS","Type":"ETF","Currency":"EUR"}]
        """.trimIndent()

        val EOD_JSON = """
            [{"date":"2025-10-13","close":29.5,"adjusted_close":29.1},
             {"date":"2025-10-14","close":29.686,"adjusted_close":29.2}]
        """.trimIndent()
    }
}
