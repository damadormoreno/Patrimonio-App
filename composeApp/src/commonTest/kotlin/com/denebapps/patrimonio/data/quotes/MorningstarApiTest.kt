package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorningstarApiTest {
    private fun api(handler: MockHandler) = MorningstarApi(quoteHttp(handler = handler), FixedClock(FIXED_NOW))

    @Test
    fun `screener url mirrors the reference parameters and escapes like urlencode`() {
        assertEquals(
            "https://lt.morningstar.com/api/rest.svc/klr5zyak8x/security/screener?page=1&pageSize=10" +
                "&outputType=json&version=1&languageId=es-ES&currencyId=EUR" +
                "&universeIds=FOESP%24%24ALL%7CETEUR%24%24ALL" +
                "&securityDataPoints=SecId%7CName%7Cisin%7CUniverse%7CPriceCurrency%7CExchangeId%7CTicker%7C" +
                "OngoingCharge%7CCollectedSRRI%7CCategoryName%7CBrandingCompanyName" +
                "&term=IE00B03HCZ61",
            MorningstarApi.screenerUrl(ISIN_FUND),
        )
    }

    @Test
    fun `timeseries url keeps brackets and dollars unescaped and covers the last 30 days`() {
        assertEquals(
            "https://lt.morningstar.com/api/rest.svc/timeseries_price/t92wz0sj7c?currencyId=EUR" +
                "&idtype=Morningstar&frequency=daily&startDate=2025-09-16&endDate=2025-10-16" +
                "&outputType=COMPACTJSON&id=0P00012PP6]2]0]FOESP\$\$ALL",
            MorningstarApi.timeseriesUrl("0P00012PP6", LocalDate(2025, 10, 16)),
        )
    }

    @Test
    fun `the requests reach the engine with brackets and dollars unescaped`() = runTest {
        val urls = mutableListOf<String>()
        val api = api { request ->
            urls += request.url.toString()
            if ("screener" in request.url.encodedPath) respondJson(SCREENER_JSON) else respondJson(TIMESERIES_JSON)
        }

        api.searchByIsin(ISIN_FUND)
        api.latestNav("0P00012PP6")

        assertEquals(MorningstarApi.screenerUrl(ISIN_FUND), urls[0])
        assertEquals(MorningstarApi.timeseriesUrl("0P00012PP6", LocalDate(2025, 10, 16)), urls[1])
        assertTrue(urls[1].endsWith("&id=0P00012PP6]2]0]FOESP\$\$ALL"))
    }

    @Test
    fun `search maps funds and EUR ETF lines and drops unsupported rows`() = runTest {
        val api = api { respondJson(SCREENER_JSON) }

        val candidates = api.searchByIsin(ISIN_FUND)

        assertEquals(
            listOf(
                InstrumentCandidate(
                    ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"),
                    name = "Vanguard Global Stock Index Fund EUR Acc",
                    isin = "IE00B03HCZ61",
                    ticker = null,
                    type = InstrumentType.FUND,
                    currency = Currency.EUR,
                    exchange = null,
                    fundamentals = InstrumentFundamentals(
                        ongoingChargePercentScaled = 1_800L,
                        riskLevel = 6,
                        category = "RV Global",
                        managementCompany = "Vanguard",
                    ),
                ),
                InstrumentCandidate(
                    ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000X1AB"),
                    name = "Some ETF EUR",
                    isin = "IE00B03HCZ61",
                    ticker = "SETF",
                    type = InstrumentType.ETF,
                    currency = Currency.EUR,
                    exchange = null,
                ),
            ),
            candidates,
        )
    }

    @Test
    fun `search tolerates a screener answer without rows`() = runTest {
        assertEquals(emptyList(), api { respondJson("""{"total":0,"rows":[]}""") }.searchByIsin(ISIN_FUND))
        assertEquals(emptyList(), api { respondJson("{}") }.searchByIsin(ISIN_FUND))
    }

    @Test
    fun `latest nav takes the last point as an EUR price dated in UTC`() = runTest {
        val quote = api { respondJson(TIMESERIES_JSON) }.latestNav("0P00012PP6")

        assertEquals(
            Quote(
                ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6"),
                price = Price(124_100_000L, Currency.EUR),
                date = LocalDate(2025, 10, 15),
            ),
            quote,
        )
    }

    @Test
    fun `latest nav skips null and zero points and fails when none is left`() = runTest {
        val quote = api { respondJson("[[1760400000000,123.456],[1760486400000,null],null,[1760572800000,0]]") }
            .latestNav("0P00012PP6")
        assertEquals(Price(123_456_000L, Currency.EUR), quote.price)
        assertEquals(LocalDate(2025, 10, 14), quote.date)

        assertFailsWith<QuoteUnavailableException> { api { respondJson("[]") }.latestNav("0P00012PP6") }
    }

    @Test
    fun `history url asks for the given window with the same escaping as the latest nav`() {
        assertEquals(
            "https://lt.morningstar.com/api/rest.svc/timeseries_price/t92wz0sj7c?currencyId=EUR" +
                "&idtype=Morningstar&frequency=daily&startDate=1995-10-24&endDate=2025-10-16" +
                "&outputType=COMPACTJSON&id=0P00012PP6]2]0]FOESP\$\$ALL",
            MorningstarApi.historyUrl("0P00012PP6", LocalDate(1995, 10, 24), LocalDate(2025, 10, 16)),
        )
    }

    @Test
    fun `a first history download asks for the last thirty years in EUR`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(TIMESERIES_JSON)
        }

        val history = api.priceHistory("0P00012PP6", from = null)

        assertEquals(
            MorningstarApi.historyUrl("0P00012PP6", LocalDate(1995, 10, 24), LocalDate(2025, 10, 16)),
            url,
        )
        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 14), Price(123_456_000L, Currency.EUR)),
                PricePoint(LocalDate(2025, 10, 15), Price(124_100_000L, Currency.EUR)),
            ),
            history,
        )
    }

    @Test
    fun `an incremental history download starts at the given date`() = runTest {
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(TIMESERIES_JSON)
        }

        api.priceHistory("0P00012PP6", from = LocalDate(2025, 10, 9))

        assertEquals(
            MorningstarApi.historyUrl("0P00012PP6", LocalDate(2025, 10, 9), LocalDate(2025, 10, 16)),
            url,
        )
    }

    @Test
    fun `history skips unusable points and comes out ascending with one price per day`() = runTest {
        // Out of order, a null value, a zero, a short point and a second point on the same day (the later one wins).
        val body = "[[1760486400000,124.1],[1760400000000,123.456],[1760572800000,null],[1760572800000,0]," +
            "[1760659200000],null,[1760486400000,125.5]]"

        val history = api { respondJson(body) }.priceHistory("0P00012PP6", from = null)

        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 14), Price(123_456_000L, Currency.EUR)),
                PricePoint(LocalDate(2025, 10, 15), Price(125_500_000L, Currency.EUR)),
            ),
            history,
        )
    }

    @Test
    fun `a nav dated on a weekend fills an empty previous friday and never replaces a real one`() = runTest {
        // Thu 16 (99.0), Fri 17 (100.0), Sat 18 (100.5), Sun 19 (101.0), Mon 20 (102.0) and Sat 25 (103.0).
        val body = "[[1760572800000,99.0],[1760659200000,100.0],[1760745600000,100.5],[1760832000000,101.0]," +
            "[1760918400000,102.0],[1761350400000,103.0]]"

        val history = api { respondJson(body) }.priceHistory("0P00012PP6", from = null)

        assertEquals(
            listOf(
                PricePoint(LocalDate(2025, 10, 16), Price(99_000_000L, Currency.EUR)),
                // Friday the 17th keeps its own NAV: the weekend copies only fill a Friday without one.
                PricePoint(LocalDate(2025, 10, 17), Price(100_000_000L, Currency.EUR)),
                PricePoint(LocalDate(2025, 10, 20), Price(102_000_000L, Currency.EUR)),
                // Saturday 2025-10-25 has no Friday of its own, so it takes Friday the 24th.
                PricePoint(LocalDate(2025, 10, 24), Price(103_000_000L, Currency.EUR)),
            ),
            history,
        )
    }

    @Test
    fun `history may be empty and malformed history is a domain failure`() = runTest {
        assertEquals(emptyList(), api { respondJson("[]") }.priceHistory("0P00012PP6", from = null))
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("""{"nope":1}""") }.priceHistory("0P00012PP6", from = null)
        }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("{}", HttpStatusCode.NotFound) }.priceHistory("0P00012PP6", from = null)
        }
    }

    @Test
    fun `fundamentals come from the screener row of the preferred SecId`() = runTest {
        val body = """
            {"rows":[
              {"SecId":"0P0000AAAA","isin":"$ISIN_FUND","OngoingCharge":0.5,"CollectedSRRI":4,
               "CategoryName":"Other","BrandingCompanyName":"Other Co"},
              {"SecId":"0P00012PP6","isin":"$ISIN_FUND","OngoingCharge":0.1825,"CollectedSRRI":6.0,
               "CategoryName":"  RV Global ","BrandingCompanyName":"Vanguard"}
            ]}
        """.trimIndent()
        var url: String? = null
        val api = api { request ->
            url = request.url.toString()
            respondJson(body)
        }

        val fundamentals = api.fundamentals(ISIN_FUND, preferredSecId = "0P00012PP6")

        assertEquals(MorningstarApi.screenerUrl(ISIN_FUND), url)
        assertEquals(
            InstrumentFundamentals(
                ongoingChargePercentScaled = 1_825L,
                riskLevel = 6,
                category = "RV Global",
                managementCompany = "Vanguard",
            ),
            fundamentals,
        )
    }

    @Test
    fun `fundamentals fall back to the first row that has data when the SecId is not listed`() = runTest {
        val body = """
            {"rows":[
              {"SecId":"0P0000BBBB","isin":"$ISIN_FUND"},
              {"SecId":"0P0000AAAA","isin":"$ISIN_FUND","OngoingCharge":0.2,"CategoryName":"Renta Fija"}
            ]}
        """.trimIndent()
        val expected = InstrumentFundamentals(ongoingChargePercentScaled = 2_000L, category = "Renta Fija")

        assertEquals(expected, api { respondJson(body) }.fundamentals(ISIN_FUND, preferredSecId = "0P00012PP6"))
        assertEquals(expected, api { respondJson(body) }.fundamentals(ISIN_FUND, preferredSecId = null))
    }

    @Test
    fun `fundamentals ignore missing and invalid values and are null when nothing usable is left`() = runTest {
        val partial = """
            {"rows":[{"SecId":"0P00012PP6","OngoingCharge":-0.4,"CollectedSRRI":9,"CategoryName":"   ",
            "BrandingCompanyName":"M"}]}
        """.trimIndent()
        assertEquals(
            InstrumentFundamentals(managementCompany = "M"),
            api { respondJson(partial) }.fundamentals(ISIN_FUND, "0P00012PP6"),
        )

        val garbage = """
            {"rows":[{"SecId":"0P00012PP6","OngoingCharge":"n/a","CollectedSRRI":0,"CategoryName":null}]}
        """.trimIndent()
        assertNull(api { respondJson(garbage) }.fundamentals(ISIN_FUND, "0P00012PP6"))
        assertNull(api { respondJson("""{"rows":[]}""") }.fundamentals(ISIN_FUND, "0P00012PP6"))
        assertNull(api { respondJson("{}") }.fundamentals(ISIN_FUND, "0P00012PP6"))
    }

    @Test
    fun `fundamentals text is capped`() = runTest {
        val body = """{"rows":[{"SecId":"0P00012PP6","CategoryName":"${"X".repeat(300)}"}]}"""

        val fundamentals = api { respondJson(body) }.fundamentals(ISIN_FUND, "0P00012PP6")

        assertEquals(120, fundamentals?.category?.length)
    }

    @Test
    fun `a failing screener makes fundamentals a domain failure`() = runTest {
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("{}", HttpStatusCode.NotFound) }.fundamentals(ISIN_FUND, null)
        }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("<html>oops</html>") }.fundamentals(ISIN_FUND, null)
        }
    }

    @Test
    fun `malformed json and http failures become domain exceptions`() = runTest {
        assertFailsWith<QuoteUnavailableException> { api { respondJson("<html>oops</html>") }.searchByIsin(ISIN_FUND) }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("""{"nope":1}""") }.latestNav("0P00012PP6") }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("{}", HttpStatusCode.NotFound) }.latestNav("0P00012PP6")
        }
    }
}
