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
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Yahoo Finance search and latest close. Unofficial endpoints that may change or throttle at any time; the
 * request shapes and field names follow the reference app rumbo (MIT, github.com/danidm98/rumbo, `buscar.py`
 * and `motor.py`). Failures surface as [QuoteUnavailableException]. [clock] sets the end of a history download.
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
     * from pence exactly like [latestQuote]. The window goes as an explicit `period1`/`period2` (start of [from] in
     * UTC, or the epoch, until now): `range=max` would come back as monthly bars, which break weekly analytics.
     */
    suspend fun priceHistory(symbol: String, from: LocalDate?): List<PricePoint> {
        val start = from?.atStartOfDayIn(TimeZone.UTC)?.epochSeconds ?: 0L
        val body = http.get(historyUrl(symbol, start, clock.now().epochSeconds))
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

    private fun chartUrl(symbol: String): String = "$CHART_URL/${symbol.encodeURLPathPart()}?range=1mo&interval=1d"

    private fun historyUrl(symbol: String, startEpochSeconds: Long, endEpochSeconds: Long): String =
        "$CHART_URL/${symbol.encodeURLPathPart()}?period1=$startEpochSeconds&period2=$endEpochSeconds&interval=1d"

    private companion object {
        const val SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search"
        const val CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart"
        const val SEARCH_COUNT = "8"
    }
}
