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
    fun `malformed json and http failures become domain exceptions`() = runTest {
        assertFailsWith<QuoteUnavailableException> { api { respondJson("<html>oops</html>") }.searchByIsin(ISIN_FUND) }
        assertFailsWith<QuoteUnavailableException> { api { respondJson("""{"nope":1}""") }.latestNav("0P00012PP6") }
        assertFailsWith<QuoteUnavailableException> {
            api { respondJson("{}", HttpStatusCode.NotFound) }.latestNav("0P00012PP6")
        }
    }
}
