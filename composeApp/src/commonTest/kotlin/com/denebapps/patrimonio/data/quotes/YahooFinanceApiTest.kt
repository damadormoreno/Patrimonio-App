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

class YahooFinanceApiTest {
    private fun api(handler: MockHandler) = YahooFinanceApi(quoteHttp(handler = handler), FixedClock(FIXED_NOW))

    private fun chart(currency: String, close: String, timestamp: Long = 1760515200) =
        """{"chart":{"result":[{"meta":{"currency":"$currency"},"timestamp":[$timestamp],""" +
            """"indicators":{"quote":[{"close":[$close]}]}}],"error":null}}"""

    @Test
    fun `search requests the reference endpoint and maps supported types only`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(YAHOO_SEARCH_JSON)
        }

        val candidates = api.search("msci world")

        assertEquals(
            "https://query2.finance.yahoo.com/v1/finance/search?q=msci%20world&quotesCount=8&newsCount=0",
            url?.replace("+", "%20"),
        )
        assertEquals(
            listOf(
                InstrumentCandidate(
                    ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"),
                    name = "iShares Core MSCI World UCITS ETF USD (Acc)",
                    isin = null,
                    ticker = "IWDA.AS",
                    type = InstrumentType.ETF,
                    currency = null,
                    exchange = "Amsterdam",
                ),
                InstrumentCandidate(
                    ref = InstrumentRef(QuoteProvider.YAHOO, "AAPL"),
                    name = "Apple Inc.",
                    isin = null,
                    ticker = "AAPL",
                    type = InstrumentType.STOCK,
                    currency = null,
                    exchange = "NASDAQ",
                ),
                InstrumentCandidate(
                    ref = InstrumentRef(QuoteProvider.YAHOO, "0P0000ZZ99.F"),
                    name = "Fund shortname",
                    isin = null,
                    ticker = "0P0000ZZ99.F",
                    type = InstrumentType.FUND,
                    currency = null,
                    exchange = "FRA",
                ),
            ),
            candidates,
        )
    }

    @Test
    fun `search tolerates an answer without quotes`() = runTest {
        assertEquals(emptyList(), api { respondJson("""{"quotes":[]}""") }.search("zzz"))
        assertEquals(emptyList(), api { respondJson("{}") }.search("zzz"))
    }

    @Test
    fun `chart requests the reference endpoint and takes the last non null close`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(YAHOO_CHART_JSON)
        }

        val quote = api.latestQuote("IWDA.AS")

        assertEquals("https://query1.finance.yahoo.com/v8/finance/chart/IWDA.AS?range=1mo&interval=1d", url)
        assertEquals(
            Quote(
                ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"),
                price = Price(105_300_000L, Currency.EUR),
                date = LocalDate(2025, 10, 15),
            ),
            quote,
        )
    }

    @Test
    fun `chart percent-encodes special symbol characters in the path`() = runTest {
        var path: String? = null
        val api = api { request ->
            path = request.url.encodedPath
            respondJson(chart("USD", "10.0"))
        }

        api.latestQuote("^GSPC")

        assertEquals("/v8/finance/chart/%5EGSPC", path)
    }

    @Test
    fun `chart dates the quote with the UTC day of the timestamp`() = runTest {
        // 2025-10-14T23:59:59Z
        val quote = api { respondJson(chart("EUR", "10.0", timestamp = 1760486399)) }.latestQuote("X")

        assertEquals(LocalDate(2025, 10, 14), quote.date)
    }

    @Test
    fun `chart converts pence to pounds`() = runTest {
        val quote = api { respondJson(chart("GBp", "812.3")) }.latestQuote("VOD.L")

        assertEquals(Price(8_123_000L, Currency.GBP), quote.price)
    }

    @Test
    fun `chart in an unsupported currency is a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> { api { respondJson(chart("CHF", "10.0")) }.latestQuote("NESN.SW") }
    }

    @Test
    fun `chart without any close or with malformed json is a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> { api { respondJson(chart("EUR", "null")) }.latestQuote("X") }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("""{"chart":{"result":null,"error":{"code":"Not Found"}}}""") }.latestQuote("X")
        }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("not json") }.latestQuote("X") }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("{}", HttpStatusCode.NotFound) }.latestQuote("X")
        }
    }

    private fun history(currency: String, timestamps: String, closes: String) =
        """{"chart":{"result":[{"meta":{"currency":"$currency"},"timestamp":[$timestamps],""" +
            """"indicators":{"quote":[{"close":[$closes]}]}}],"error":null}}"""

    @Test
    fun `a first history download asks for daily bars since the epoch and skips bars without a close`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(YAHOO_CHART_JSON)
        }

        val points = api.priceHistory("IWDA.AS", from = null)

        // Not range=max: Yahoo downgrades it to monthly bars. FIXED_NOW (2025-10-16T10:00Z) is 1760608800.
        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/IWDA.AS?period1=0&period2=1760608800&interval=1d",
            url,
        )
        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 14), Price(104_120_000L, Currency.EUR)),
                PricePoint(LocalDate(2025, 10, 15), Price(105_300_000L, Currency.EUR)),
            ),
            points,
        )
    }

    @Test
    fun `an incremental history download asks for daily bars from the start of that day until now`() = runTest {
        suspend fun windowFor(from: LocalDate): String? {
            var url: String? = null
            api { request ->
                url = request.url.toString()
                respondJson(YAHOO_CHART_JSON)
            }.priceHistory("IWDA.AS", from)
            return url?.substringAfter('?')
        }

        // 2025-10-09T00:00Z is 1759968000 and 2005-01-01T00:00Z is 1104537600.
        assertEquals("period1=1759968000&period2=1760608800&interval=1d", windowFor(LocalDate(2025, 10, 9)))
        assertEquals("period1=1104537600&period2=1760608800&interval=1d", windowFor(LocalDate(2005, 1, 1)))
    }

    @Test
    fun `history drops float noise and converts pence like the latest quote`() = runTest {
        val noisy = history("EUR", "1760428800,1760515200", "131.669998,131.67")
        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 14), Price(131_670_000L, Currency.EUR)),
                PricePoint(LocalDate(2025, 10, 15), Price(131_670_000L, Currency.EUR)),
            ),
            api { respondJson(noisy) }.priceHistory("X", from = null),
        )

        val pence = history("GBp", "1760428800", "812.3")
        assertEquals(
            listOf(PricePoint(LocalDate(2025, 10, 14), Price(8_123_000L, Currency.GBP))),
            api { respondJson(pence) }.priceHistory("VOD.L", from = null),
        )
    }

    @Test
    fun `history is ascending with one price per UTC day and the later bar wins`() = runTest {
        // 2025-10-15T23:00Z, 2025-10-14T10:00Z and 2025-10-15T10:00Z.
        val body = history("EUR", "1760569200,1760436000,1760522400", "9.0,7.0,8.0")

        val points = api { respondJson(body) }.priceHistory("X", from = null)

        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 14), Price(7_000_000L, Currency.EUR)),
                PricePoint(LocalDate(2025, 10, 15), Price(9_000_000L, Currency.EUR)),
            ),
            points,
        )
    }

    @Test
    fun `history without bars is empty while unusable history is a domain failure`() = runTest {
        assertEquals(emptyList(), api { respondJson(history("EUR", "", "")) }.priceHistory("X", from = null))
        assertEquals(emptyList(), api { respondJson(history("EUR", "1760428800", "null")) }.priceHistory("X", null))

        assertFailsWith<QuoteUnavailableException> {
            api { respondJson(history("CHF", "1760428800", "10.0")) }.priceHistory("NESN.SW", from = null)
        }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("""{"chart":{"result":null,"error":{"code":"Not Found"}}}""") }.priceHistory("X", null)
        }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("not json") }.priceHistory("X", null) }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("{}", HttpStatusCode.NotFound) }.priceHistory("X", null)
        }
    }
}
