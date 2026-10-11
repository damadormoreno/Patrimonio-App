package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NO_COINS_JSON = """{"coins":[]}"""

class CompositeQuoteSourceTest {
    private class Hits {
        val morningstar = mutableListOf<String>()
        val yahoo = mutableListOf<String>()
        val coinGecko = mutableListOf<String>()
    }

    /** Builds the composite over one mock engine; a handler returning `null` answers 404 (provider "down"). */
    private fun source(
        hits: Hits = Hits(),
        morningstar: (String) -> String? = { SCREENER_JSON },
        yahoo: (String) -> String? = { YAHOO_SEARCH_JSON },
        coinGecko: (String) -> String? = { NO_COINS_JSON },
    ): CompositeQuoteSource {
        val http = quoteHttp { request ->
            val url = request.url.toString()
            val (hitList, answer) = when (request.url.host) {
                "lt.morningstar.com" -> hits.morningstar to morningstar(url)
                "api.coingecko.com" -> hits.coinGecko to coinGecko(url)
                else -> hits.yahoo to yahoo(url)
            }
            hitList += url
            if (answer == null) respondJson("", HttpStatusCode.NotFound) else respondJson(answer)
        }
        return CompositeQuoteSource(
            MorningstarApi(http, FixedClock(FIXED_NOW)),
            YahooFinanceApi(http),
            CoinGeckoApi(http, FixedClock(FIXED_NOW)),
        )
    }

    private val yahooResultCodes = listOf("IWDA.AS", "AAPL", "0P0000ZZ99.F")

    private val yahooWithMorningstarTwin = """
        {"quotes":[
          {"exchange":"FRA","shortname":"Vanguard Global","quoteType":"MUTUALFUND","symbol":"0P00012PP6.F"},
          {"exchange":"AMS","shortname":"iShares Core MSCI World","quoteType":"ETF","symbol":"IWDA.AS"}
        ]}
    """.trimIndent()

    @Test
    fun `an ISIN searches Morningstar first then Yahoo`() = runTest {
        val hits = Hits()

        val candidates = source(hits).search("ie00b03hcz61 ")

        assertEquals(1, hits.morningstar.size)
        assertTrue(hits.morningstar.single().endsWith("term=IE00B03HCZ61"))
        assertEquals(1, hits.yahoo.size)
        assertEquals(
            listOf(
                InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"),
                InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000X1AB"),
                InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"),
                InstrumentRef(QuoteProvider.YAHOO, "AAPL"),
            ),
            candidates.map { it.ref },
        )
        assertTrue(candidates.all { it.isin == "IE00B03HCZ61" })
        assertEquals(Currency.EUR, candidates.first().currency)
    }

    @Test
    fun `a Yahoo 0P symbol equal to a Morningstar SecId is the same instrument and is dropped`() = runTest {
        val candidates = source(yahoo = { yahooWithMorningstarTwin }).search(ISIN_FUND)

        assertEquals(
            listOf(
                InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"),
                InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000X1AB"),
                InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"),
            ),
            candidates.map { it.ref },
        )
    }

    @Test
    fun `Yahoo mutual funds are dropped when Morningstar already offers a fund`() = runTest {
        val candidates = source().search(ISIN_FUND)

        assertTrue(candidates.none { it.ref == InstrumentRef(QuoteProvider.YAHOO, "0P0000ZZ99.F") })
    }

    @Test
    fun `a Yahoo fund stays offered when Morningstar knows no fund`() = runTest {
        val candidates = source(morningstar = { """{"rows":[]}""" }).search(ISIN_FUND)

        val fund = candidates.single { it.type == InstrumentType.FUND }
        assertEquals(InstrumentRef(QuoteProvider.YAHOO, "0P0000ZZ99.F"), fund.ref)
        assertEquals("IE00B03HCZ61", fund.isin)
    }

    @Test
    fun `a ticker or a name searches Yahoo and CoinGecko but never Morningstar`() = runTest {
        val hits = Hits()

        val candidates = source(hits).search("IWDA")

        assertTrue(hits.morningstar.isEmpty())
        assertEquals(1, hits.yahoo.size)
        assertEquals(1, hits.coinGecko.size)
        assertTrue(hits.coinGecko.single().endsWith("/search?query=IWDA"))
        assertEquals(yahooResultCodes, candidates.map { it.ref.code })
        assertTrue(candidates.all { it.isin == null })
    }

    @Test
    fun `without an exact match the top three coins follow the Yahoo results`() = runTest {
        val candidates = source(coinGecko = { COINGECKO_SEARCH_JSON }).search("apple")

        assertEquals(
            yahooResultCodes.map { InstrumentRef(QuoteProvider.YAHOO, it) } + listOf(
                InstrumentRef(QuoteProvider.COINGECKO, "bitcoin"),
                InstrumentRef(QuoteProvider.COINGECKO, "bitcoin-cash"),
                InstrumentRef(QuoteProvider.COINGECKO, "bitcoin-cash-sv"),
            ),
            candidates.map { it.ref },
        )
    }

    @Test
    fun `a coin whose symbol or name equals the query goes first and the other coins are left out`() = runTest {
        val bySymbol = source(coinGecko = { COINGECKO_SEARCH_JSON }).search(" btc ")
        val byName = source(coinGecko = { COINGECKO_SEARCH_JSON }).search("BITCOIN CASH")
        val bySuffix = source(coinGecko = { COINGECKO_SEARCH_JSON }).search("bsv")

        assertEquals(
            listOf(InstrumentRef(QuoteProvider.COINGECKO, "bitcoin")) +
                yahooResultCodes.map { InstrumentRef(QuoteProvider.YAHOO, it) },
            bySymbol.map { it.ref },
        )
        assertEquals(InstrumentRef(QuoteProvider.COINGECKO, "bitcoin-cash"), byName.first().ref)
        assertEquals(yahooResultCodes.size + 1, byName.size)
        assertEquals(InstrumentRef(QuoteProvider.COINGECKO, "bitcoin-cash-sv"), bySuffix.first().ref)
    }

    @Test
    fun `at most three exact coin matches are put first`() = runTest {
        val coins = (1..5).joinToString(
            ",",
        ) { """{"id":"abc-$it","name":"Abc $it","symbol":"ABC","market_cap_rank":$it}""" }

        val candidates = source(coinGecko = { """{"coins":[$coins]}""" }).search("abc")

        assertEquals(listOf("abc-1", "abc-2", "abc-3") + yahooResultCodes, candidates.map { it.ref.code })
    }

    @Test
    fun `Yahoo crypto results stay skipped because CoinGecko is the crypto source`() = runTest {
        val candidates = source(coinGecko = { COINGECKO_SEARCH_JSON }).search("btc")

        assertTrue(candidates.none { it.ref.provider == QuoteProvider.YAHOO && it.ref.code == "BTC-USD" })
        assertTrue(
            candidates.filter { it.type == InstrumentType.CRYPTO }.all { it.ref.provider == QuoteProvider.COINGECKO },
        )
    }

    @Test
    fun `a name search survives CoinGecko being down`() = runTest {
        val candidates = source(coinGecko = { null }).search("apple")

        assertEquals(yahooResultCodes, candidates.map { it.ref.code })
    }

    @Test
    fun `a name search survives Yahoo being down and still offers the coins`() = runTest {
        val candidates = source(yahoo = { null }, coinGecko = { COINGECKO_SEARCH_JSON }).search("apple")

        assertEquals(listOf("bitcoin", "bitcoin-cash", "bitcoin-cash-sv"), candidates.map { it.ref.code })
    }

    @Test
    fun `an ISIN search never asks CoinGecko`() = runTest {
        val hits = Hits()

        source(hits, coinGecko = { COINGECKO_SEARCH_JSON }).search(ISIN_FUND)

        assertTrue(hits.coinGecko.isEmpty())
    }

    @Test
    fun `a blank query returns nothing without any request`() = runTest {
        val hits = Hits()

        assertEquals(emptyList(), source(hits).search("   "))

        assertTrue(hits.morningstar.isEmpty() && hits.yahoo.isEmpty())
    }

    @Test
    fun `an ISIN search survives Yahoo being down`() = runTest {
        val candidates = source(yahoo = { null }).search(ISIN_FUND)

        assertEquals(
            listOf(
                InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"),
                InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000X1AB"),
            ),
            candidates.map { it.ref },
        )
    }

    @Test
    fun `an ISIN search survives Morningstar being down`() = runTest {
        val candidates = source(morningstar = { null }).search(ISIN_FUND)

        assertEquals(listOf("IWDA.AS", "AAPL", "0P0000ZZ99.F"), candidates.map { it.ref.code })
    }

    @Test
    fun `an ISIN search fails when both providers fail`() = runTest {
        assertFailsWith<QuoteUnavailableException> {
            source(morningstar = { null }, yahoo = { null }).search(ISIN_FUND)
        }
    }

    @Test
    fun `a name search fails when Yahoo and CoinGecko both fail`() = runTest {
        assertFailsWith<QuoteUnavailableException> { source(yahoo = { null }, coinGecko = { null }).search("apple") }
    }

    @Test
    fun `latest quote routes by provider`() = runTest {
        val hits = Hits()
        val source = source(hits, morningstar = { TIMESERIES_JSON }, yahoo = { YAHOO_CHART_JSON })

        val nav = source.latestQuote(InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"))
        val price = source.latestQuote(InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"))

        assertEquals(124_100_000L, nav.price.scaled)
        assertEquals(105_300_000L, price.price.scaled)
        assertEquals(1, hits.morningstar.size)
        assertEquals(1, hits.yahoo.size)
    }

    @Test
    fun `price history routes by provider`() = runTest {
        val hits = Hits()
        val source = source(hits, morningstar = { TIMESERIES_JSON }, yahoo = { YAHOO_CHART_JSON })

        val nav = source.priceHistory(InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"), from = null)
        val prices = source.priceHistory(InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"), from = null)

        assertEquals(listOf(123_456_000L, 124_100_000L), nav.map { it.price.scaled })
        assertEquals(listOf(104_120_000L, 105_300_000L), prices.map { it.price.scaled })
        assertEquals(1, hits.morningstar.size)
        assertTrue("timeseries_price" in hits.morningstar.single())
        assertEquals(1, hits.yahoo.size)
        assertTrue("period1=0&" in hits.yahoo.single())
    }

    @Test
    fun `CoinGecko quotes and history are routed to CoinGecko`() = runTest {
        val hits = Hits()
        val source = source(
            hits,
            coinGecko = { url -> if ("simple/price" in url) COINGECKO_PRICE_JSON else COINGECKO_CHART_JSON },
        )
        val bitcoin = InstrumentRef(QuoteProvider.COINGECKO, "bitcoin")

        val quote = source.latestQuote(bitcoin)
        val history = source.priceHistory(bitcoin, from = null)

        assertEquals(74_052_000_000L, quote.price.scaled)
        assertEquals(listOf(100_500_000L, 101_250_000L, 103_500_000L), history.map { it.price.scaled })
        assertEquals(2, hits.coinGecko.size)
        assertTrue(hits.morningstar.isEmpty() && hits.yahoo.isEmpty())
    }

    @Test
    fun `a CoinGecko holding has no fundamentals and asks nobody`() = runTest {
        val hits = Hits()

        val fundamentals = source(hits).fundamentals(ISIN_FUND, InstrumentRef(QuoteProvider.COINGECKO, "bitcoin"))

        assertNull(fundamentals)
        assertTrue(hits.morningstar.isEmpty() && hits.yahoo.isEmpty() && hits.coinGecko.isEmpty())
    }

    @Test
    fun `fundamentals are read from the Morningstar screener whichever provider the holding uses`() = runTest {
        val hits = Hits()
        val source = source(hits)

        val fromMorningstar = source.fundamentals(ISIN_FUND, InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"))
        val fromYahoo = source.fundamentals(ISIN_FUND, InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"))

        assertEquals(1_800L, fromMorningstar?.ongoingChargePercentScaled)
        assertEquals(6, fromMorningstar?.riskLevel)
        // A Yahoo holding has no SecId on Morningstar: the first row with data answers.
        assertEquals(fromMorningstar, fromYahoo)
        assertEquals(2, hits.morningstar.size)
        assertTrue(hits.yahoo.isEmpty())
    }

    @Test
    fun `a failing screener makes fundamentals a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> {
            source(morningstar = { null }).fundamentals(ISIN_FUND, null)
        }
    }
}
