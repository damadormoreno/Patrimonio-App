package com.denebapps.patrimonio.data.quotes

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

internal typealias MockHandler = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

/** A bare client over [MockEngine]: the quote adapters do not rely on any client plugin. */
internal fun mockClient(handler: MockHandler): HttpClient = HttpClient(MockEngine(handler))

/** A [QuoteHttp] whose backoff delays are recorded into [delays] instead of waiting. */
internal fun quoteHttp(delays: MutableList<Long> = mutableListOf(), handler: MockHandler): QuoteHttp =
    QuoteHttp(mockClient(handler), delayMillis = { delays += it })

internal fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

internal class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

internal val FIXED_NOW: Instant = Instant.parse("2025-10-16T10:00:00Z")

internal const val ISIN_FUND = "IE00B03HCZ61"

/** Morningstar screener answer: one fund, one EUR ETF line, one CHF ETF line (filtered) and a row without SecId. */
internal val SCREENER_JSON = """
    {"total":4,"page":1,"pageSize":10,"rows":[
      {"SecId":"0P00012PP6","Name":"Vanguard Global Stock Index Fund EUR Acc","isin":"IE00B03HCZ61",
       "Universe":"FOESP${'$'}${'$'}ALL","PriceCurrency":"EUR","ExchangeId":null,"Ticker":null,
       "OngoingCharge":0.18,"CollectedSRRI":6,"CategoryName":"RV Global","BrandingCompanyName":"Vanguard"},
      {"SecId":"0P0000X1AB","Name":"Some ETF EUR","isin":"IE00B03HCZ61","Universe":"ETEUR${'$'}${'$'}ALL",
       "PriceCurrency":"EUR","ExchangeId":"EXGETR","Ticker":"SETF"},
      {"SecId":"0P0000X1AC","Name":"Some ETF CHF","isin":"IE00B03HCZ61","Universe":"ETEUR${'$'}${'$'}ALL",
       "PriceCurrency":"CHF","ExchangeId":"EXSWX","Ticker":"SETF"},
      {"Name":"Broken row","Universe":"FOESP${'$'}${'$'}ALL","PriceCurrency":"EUR"}
    ]}
""".trimIndent()

internal const val YAHOO_SEARCH_JSON = """
    {"explains":[],"count":6,"quotes":[
      {"exchange":"AMS","shortname":"iShares Core MSCI World","quoteType":"ETF","symbol":"IWDA.AS",
       "index":"quotes","score":31000.0,"typeDisp":"ETF","longname":"iShares Core MSCI World UCITS ETF USD (Acc)",
       "exchDisp":"Amsterdam","isYahooFinance":true},
      {"exchange":"NMS","shortname":"Apple Inc.","quoteType":"EQUITY","symbol":"AAPL","longname":"Apple Inc.",
       "exchDisp":"NASDAQ"},
      {"exchange":"FRA","shortname":"Fund shortname","quoteType":"MUTUALFUND","symbol":"0P0000ZZ99.F"},
      {"exchange":"CCC","shortname":"Bitcoin USD","quoteType":"CRYPTOCURRENCY","symbol":"BTC-USD"},
      {"exchange":"NYM","shortname":"Gold Dec 25","quoteType":"FUTURE","symbol":"GC=F"},
      {"exchange":"NMS","quoteType":"EQUITY"}
    ],"news":[]}
"""

/** Yahoo chart answer in EUR whose last bar has no close yet. */
internal const val YAHOO_CHART_JSON = """
    {"chart":{"result":[{"meta":{"currency":"EUR","symbol":"IWDA.AS","exchangeName":"AMS",
    "longName":"iShares Core MSCI World UCITS ETF USD (Acc)"},
    "timestamp":[1760428800,1760515200,1760601600],
    "indicators":{"quote":[{"close":[104.12,105.3,null]}]}}],"error":null}}
"""

/** Morningstar timeseries answer: two NAV points (2025-10-14 and 2025-10-15, UTC midnight). */
internal const val TIMESERIES_JSON = "[[1760400000000,123.456],[1760486400000,124.1]]"

/** CoinGecko `search` answer (real shape, trimmed): one coin without rank, one without id, other sections empty. */
internal const val COINGECKO_SEARCH_JSON = """
    {"coins":[
      {"id":"bitcoin-cash","name":"Bitcoin Cash","api_symbol":"bitcoin-cash","symbol":"BCH","market_cap_rank":22,
       "thumb":"https://coin-images.coingecko.com/coins/images/780/thumb/bitcoin-cash-circle.png"},
      {"id":"unranked-bitcoin","name":"Unranked Bitcoin","api_symbol":"unranked-bitcoin","symbol":"UBTC",
       "market_cap_rank":null},
      {"id":"bitcoin","name":"Bitcoin","api_symbol":"bitcoin","symbol":"BTC","market_cap_rank":1},
      {"id":"bitcoin-cash-sv","name":"Bitcoin SV","api_symbol":"bitcoin-cash-sv","symbol":"BSV","market_cap_rank":128},
      {"id":"","name":"No id","symbol":"NOID","market_cap_rank":2}
    ],"exchanges":[],"icos":[],"categories":[],"nfts":[]}
"""

/** CoinGecko `simple/price` answer (2025-10-16T10:00:02Z); the price can arrive as an integer. */
internal const val COINGECKO_PRICE_JSON = """{"bitcoin":{"eur":74052,"last_updated_at":1760608802}}"""

/**
 * CoinGecko `market_chart` answer: 2025-10-14, 2025-10-15 and 2025-10-16 at 00:00 UTC, plus the live "now" point on
 * 2025-10-16 at 10:00 UTC, which falls on the same day as the previous one.
 */
internal const val COINGECKO_CHART_JSON = """
    {"prices":[[1760400000000,100.5],[1760486400000,101.25],[1760572800000,102.0],[1760608800000,103.5]],
     "market_caps":[[1760400000000,1.0],[1760608800000,2.0]],"total_volumes":[[1760400000000,3.0]]}
"""

/** What the public API answers when more than a year of history is requested. */
internal const val COINGECKO_RANGE_ERROR_JSON =
    """{"error":{"status":{"timestamp":"2026-10-11T15:14:02.202+00:00","error_code":10012,""" +
        """"error_message":"Your request exceeds the allowed time range."}}}"""
