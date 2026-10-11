package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.quotes.dto.YahooChartResponse
import com.denebapps.patrimonio.data.quotes.dto.YahooSearchResponse
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Yahoo Finance search and latest close. Unofficial endpoints that may change or throttle at any time; the
 * request shapes and field names follow the reference app rumbo (MIT, github.com/danidm98/rumbo, `buscar.py`
 * and `motor.py`). Failures surface as [QuoteUnavailableException]. [clock] only sizes the window of an incremental
 * history download.
 */
class YahooFinanceApi(private val http: QuoteHttp, private val clock: Clock = Clock.System) {
    /** Equities, ETFs and mutual funds matching [query] (ISIN, ticker or name). Currency is unknown until priced. */
    suspend fun search(query: String): List<InstrumentCandidate> {
        val body = http.get(searchUrl(query))
        val quotes = parsingQuotes("Yahoo search") { QuoteJson.decodeFromString<YahooSearchResponse>(body).quotes }
        return quotes.mapNotNull { quote ->
            val symbol = quote.symbol?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = yahooInstrumentType(quote.quoteType) ?: return@mapNotNull null
            InstrumentCandidate(
                ref = InstrumentRef(QuoteProvider.YAHOO, symbol),
                name = quote.longname ?: quote.shortname ?: symbol,
                isin = null,
                ticker = symbol,
                type = type,
                currency = null,
                exchange = quote.exchDisp ?: quote.exchange,
            )
        }
    }

    /** The last non-null daily close of the last month, in the currency Yahoo reports for [symbol]. */
    suspend fun latestQuote(symbol: String): Quote {
        val body = http.get(chartUrl(symbol))
        val result = parsingQuotes("Yahoo chart") {
            QuoteJson.decodeFromString<YahooChartResponse>(body).chart?.result?.firstOrNull()
        } ?: throw QuoteUnavailableException("Yahoo has no data for $symbol")
        val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
        val timestamps = result.timestamp.orEmpty()
        val last = closes.indices.reversed().firstOrNull { index -> closes[index] != null && index < timestamps.size }
            ?: throw QuoteUnavailableException("Yahoo has no recent close for $symbol")
        val currencyCode = result.meta?.currency
        val price = yahooPrice(checkNotNull(closes[last]), currencyCode)
            ?: throw QuoteUnavailableException("Yahoo price for $symbol is unusable (currency $currencyCode)")
        return Quote(
            ref = InstrumentRef(QuoteProvider.YAHOO, symbol),
            price = price,
            date = Instant.fromEpochSeconds(timestamps[last]).toLocalDateTime(TimeZone.UTC).date,
        )
    }

    /**
     * The daily closes of [symbol] from [from] to today (everything Yahoo has when `null`), one per UTC day and
     * ascending; the later bar of a day wins. Bars without a close are skipped and prices are denoised and converted
     * from pence exactly like [latestQuote]. Yahoo's chart takes a range, not a start date, so the smallest range that
     * covers [from] is requested and the caller upserts the overlap.
     */
    suspend fun priceHistory(symbol: String, from: LocalDate?): List<PricePoint> {
        val range = from?.let { rangeCovering(it.daysUntil(clock.now().toLocalDateTime(TimeZone.UTC).date)) } ?: "max"
        val body = http.get(chartUrl(symbol, range))
        val result = parsingQuotes("Yahoo chart") {
            QuoteJson.decodeFromString<YahooChartResponse>(body).chart?.result?.firstOrNull()
        } ?: throw QuoteUnavailableException("Yahoo has no data for $symbol")
        val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
        val bars = result.timestamp.orEmpty().zip(closes)
            .mapNotNull { (timestamp, close) -> close?.let { timestamp to it } }
            .sortedBy { it.first }
        val currencyCode = result.meta?.currency
        val points = bars.mapNotNull { (timestamp, close) ->
            yahooPrice(close, currencyCode)?.let {
                PricePoint(Instant.fromEpochSeconds(timestamp).toLocalDateTime(TimeZone.UTC).date, it)
            }
        }
        if (bars.isNotEmpty() && points.isEmpty()) {
            throw QuoteUnavailableException("Yahoo prices for $symbol are unusable (currency $currencyCode)")
        }
        return points.associateBy { it.date }.values.sortedBy { it.date }
    }

    private fun searchUrl(query: String): String = URLBuilder(SEARCH_URL).apply {
        parameters.append("q", query)
        parameters.append("quotesCount", SEARCH_COUNT)
        parameters.append("newsCount", "0")
    }.buildString()

    private fun chartUrl(symbol: String, range: String = "1mo"): String =
        "$CHART_URL/${symbol.encodeURLPathPart()}?range=$range&interval=1d"

    /** The smallest range Yahoo offers that reaches [days] back, with some slack for weekends and holidays. */
    private fun rangeCovering(days: Int): String =
        RANGES.firstOrNull { (maxDays, _) -> days <= maxDays }?.second ?: "max"

    private companion object {
        const val SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search"
        const val CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart"
        const val SEARCH_COUNT = "8"

        /** Yahoo chart ranges as (days they safely cover, name). */
        val RANGES = listOf(
            27 to "1mo",
            85 to "3mo",
            175 to "6mo",
            355 to "1y",
            715 to "2y",
            1_790 to "5y",
            3_600 to "10y",
        )
    }
}
