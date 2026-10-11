package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
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
    private fun api(handler: MockHandler) = YahooFinanceApi(quoteHttp(handler = handler))

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
}
