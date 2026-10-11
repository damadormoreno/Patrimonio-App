package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoinGeckoApiTest {
    private val bitcoin = InstrumentRef(QuoteProvider.COINGECKO, "bitcoin")

    private fun api(delays: MutableList<Long> = mutableListOf(), handler: MockHandler) =
        CoinGeckoApi(quoteHttp(delays, handler), FixedClock(FIXED_NOW))

    private fun eur(scaled: Long) = Price(scaled, Currency.EUR)

    @Test
    fun `search requests the public endpoint and maps coins ordered by market cap rank`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(COINGECKO_SEARCH_JSON)
        }

        val candidates = api.search("bitcoin cash")

        assertEquals("https://api.coingecko.com/api/v3/search?query=bitcoin%20cash", url?.replace("+", "%20"))
        assertEquals(
            InstrumentCandidate(
                ref = bitcoin,
                name = "Bitcoin",
                isin = null,
                ticker = "BTC",
                type = InstrumentType.CRYPTO,
                currency = Currency.EUR,
                exchange = null,
            ),
            candidates.first(),
        )
        // Ranked coins by rank, the rank-less one last, the one without an id dropped.
        assertEquals(
            listOf("bitcoin", "bitcoin-cash", "bitcoin-cash-sv", "unranked-bitcoin"),
            candidates.map { it.ref.code },
        )
        assertTrue(candidates.all { it.fundamentals == null && it.isin == null })
    }

    @Test
    fun `search keeps the symbol in upper case and falls back to the id for a missing name`() = runTest {
        val api = api { respondJson("""{"coins":[{"id":"some-coin","symbol":"sc","market_cap_rank":5}]}""") }

        val candidate = api.search("some").single()

        assertEquals("SC", candidate.ticker)
        assertEquals("some-coin", candidate.name)
    }

    @Test
    fun `search returns at most eight coins`() = runTest {
        val coins = (1..12).joinToString(",") {
            """{"id":"coin-$it","name":"Coin $it","symbol":"c$it","market_cap_rank":${13 - it}}"""
        }
        val api = api { respondJson("""{"coins":[$coins]}""") }

        val candidates = api.search("coin")

        assertEquals(8, candidates.size)
        assertEquals("coin-12", candidates.first().ref.code)
        assertEquals("coin-5", candidates.last().ref.code)
    }

    @Test
    fun `search tolerates an answer without coins`() = runTest {
        assertEquals(emptyList(), api { respondJson("""{"coins":[],"exchanges":[]}""") }.search("zzz"))
        assertEquals(emptyList(), api { respondJson("{}") }.search("zzz"))
    }

    @Test
    fun `search with a malformed answer is a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> { api { respondJson("not json") }.search("btc") }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("""{"coins":"x"}""") }.search("btc") }
    }

    @Test
    fun `latest quote asks the simple price endpoint in euros and dates it from the update time`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(COINGECKO_PRICE_JSON)
        }

        val quote = api.latestQuote("bitcoin")

        assertEquals(
            "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur&include_last_updated_at=true",
            url,
        )
        assertEquals(Quote(bitcoin, eur(74_052_000_000L), LocalDate(2025, 10, 16)), quote)
    }

    @Test
    fun `latest quote keeps six decimals and dates by the UTC day`() = runTest {
        // 2025-10-15T23:59:59Z
        val json = """{"bitcoin":{"eur":0.123456789,"last_updated_at":1760572799}}"""

        val quote = api { respondJson(json) }.latestQuote("bitcoin")

        assertEquals(eur(123_457L), quote.price)
        assertEquals(LocalDate(2025, 10, 15), quote.date)
    }

    @Test
    fun `latest quote of an unknown coin or without a price is a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> { api { respondJson("{}") }.latestQuote("nope") }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("""{"bitcoin":{"last_updated_at":1760608802}}""") }.latestQuote("bitcoin")
        }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("not json") }.latestQuote("bitcoin") }
    }

    @Test
    fun `latest quote falls back to today when the update time is missing`() = runTest {
        val quote = api { respondJson("""{"bitcoin":{"eur":10.5}}""") }.latestQuote("bitcoin")

        assertEquals(LocalDate(2025, 10, 16), quote.date)
    }

    @Test
    fun `a first history download asks for the last 365 days and never for max`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(COINGECKO_CHART_JSON)
        }

        api.priceHistory("bitcoin", from = null)

        assertEquals(
            "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=eur&days=365&interval=daily",
            url,
        )
        assertTrue("max" !in url.orEmpty())
    }

    @Test
    fun `an incremental history download asks for the days since the last point plus one`() = runTest {
        val urls = mutableListOf<String>()
        val api = api { request ->
            urls += request.url.toString()
            respondJson(COINGECKO_CHART_JSON)
        }

        api.priceHistory("bitcoin", from = LocalDate(2025, 10, 10))
        api.priceHistory("bitcoin", from = LocalDate(2025, 10, 16))
        api.priceHistory("bitcoin", from = LocalDate(2024, 1, 1))
        api.priceHistory("bitcoin", from = LocalDate(2025, 10, 20))

        assertEquals(listOf("7", "1", "365", "1"), urls.map { it.substringAfter("days=").substringBefore('&') })
        assertTrue(urls.none { "max" in it })
    }

    @Test
    fun `history has one point per UTC day and the later point of a day wins`() = runTest {
        val points = api { respondJson(COINGECKO_CHART_JSON) }.priceHistory("bitcoin", from = null)

        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 14), eur(100_500_000L)),
                PricePoint(LocalDate(2025, 10, 15), eur(101_250_000L)),
                PricePoint(LocalDate(2025, 10, 16), eur(103_500_000L)),
            ),
            points,
        )
    }

    @Test
    fun `history starts at the requested date and skips points without a price`() = runTest {
        val json = """{"prices":[[1760400000000,100.5],[1760486400000,null],[1760572800000,102.0]]}"""

        val points = api { respondJson(json) }.priceHistory("bitcoin", from = LocalDate(2025, 10, 15))

        assertEquals(listOf(PricePoint(LocalDate(2025, 10, 16), eur(102_000_000L))), points)
    }

    @Test
    fun `history sorts unordered points and tolerates an empty window`() = runTest {
        val unordered = """{"prices":[[1760572800000,102.0],[1760400000000,100.5]]}"""

        assertEquals(
            listOf(LocalDate(2025, 10, 14), LocalDate(2025, 10, 16)),
            api { respondJson(unordered) }.priceHistory("bitcoin", null).map { it.date },
        )
        assertEquals(emptyList(), api { respondJson("""{"prices":[]}""") }.priceHistory("bitcoin", null))
    }

    @Test
    fun `the public API range error or a missing key is a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson(COINGECKO_RANGE_ERROR_JSON) }.priceHistory("bitcoin", null)
        }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson(COINGECKO_RANGE_ERROR_JSON, HttpStatusCode.Unauthorized) }.priceHistory("bitcoin", null)
        }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("not json") }.priceHistory("bitcoin", null) }
    }

    @Test
    fun `a rate limited request is retried with backoff and then answered`() = runTest {
        val delays = mutableListOf<Long>()
        var calls = 0
        val api = api(delays) {
            calls++
            if (calls <= 2) {
                respondJson("""{"status":{"error_code":429}}""", HttpStatusCode.TooManyRequests)
            } else {
                respondJson(COINGECKO_PRICE_JSON)
            }
        }

        val quote = api.latestQuote("bitcoin")

        assertEquals(3, calls)
        assertEquals(listOf(QuoteHttp.BACKOFF_MILLIS, 2 * QuoteHttp.BACKOFF_MILLIS), delays)
        assertEquals(eur(74_052_000_000L), quote.price)
    }

    @Test
    fun `a persistently rate limited request is a domain failure`() = runTest {
        val api = api { respondJson("{}", HttpStatusCode.TooManyRequests) }

        assertFailsWith<QuoteUnavailableException> { api.search("btc") }
    }
}
