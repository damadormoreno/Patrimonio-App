package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.quotes.dto.CoinGeckoChartResponse
import com.denebapps.patrimonio.data.quotes.dto.CoinGeckoCoin
import com.denebapps.patrimonio.data.quotes.dto.CoinGeckoSearchResponse
import com.denebapps.patrimonio.data.quotes.dto.CoinGeckoSimplePrice
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
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
 * CoinGecko search, latest price and daily history of crypto coins, always in EUR. Uses the public API without a key
 * (request shapes follow the reference app rumbo, MIT, github.com/danidm98/rumbo, `buscar.py` and `motor.py`), which
 * throttles aggressively (HTTP 429, retried by [QuoteHttp]) and only serves the last [MAX_HISTORY_DAYS] days of
 * history: `days=max` is refused with error 10012 / HTTP 401, so it is never requested. Failures surface as
 * [QuoteUnavailableException]. [clock] sets today for the history window and the fallback date of a price.
 *
 * Prices go through [Price.fromDouble], which keeps six decimals: a coin worth less than a cent loses precision.
 */
class CoinGeckoApi(private val http: QuoteHttp, private val clock: Clock = Clock.System) {
    /** Up to [SEARCH_LIMIT] coins matching [query] (name, symbol or id), best market cap rank first. */
    suspend fun search(query: String): List<InstrumentCandidate> {
        val body = http.get(searchUrl(query))
        val coins = parsingQuotes("CoinGecko search") {
            QuoteJson.decodeFromString<CoinGeckoSearchResponse>(body).coins
        }
        return coins
            .filter { !it.id.isNullOrBlank() }
            .sortedBy { it.marketCapRank ?: Int.MAX_VALUE }
            .take(SEARCH_LIMIT)
            .map { it.toCandidate() }
    }

    /** The current EUR price of the coin [id], dated by the UTC day CoinGecko last updated it (today when absent). */
    suspend fun latestQuote(id: String): Quote {
        val body = http.get(priceUrl(id))
        val entry = parsingQuotes("CoinGecko price") {
            QuoteJson.decodeFromString<Map<String, CoinGeckoSimplePrice>>(body)[id]
        } ?: throw QuoteUnavailableException("CoinGecko has no price for $id")
        val eur = entry.eur ?: throw QuoteUnavailableException("CoinGecko has no EUR price for $id")
        val price = parsingQuotes("CoinGecko price") { Price.fromDouble(eur, Currency.EUR) }
        val updated = entry.lastUpdatedAt?.let { Instant.fromEpochSeconds(it) } ?: clock.now()
        return Quote(InstrumentRef(QuoteProvider.COINGECKO, id), price, updated.toUtcDate())
    }

    /**
     * The daily EUR prices of the coin [id] from [from] to today, one per UTC day and ascending; the later point of a
     * day wins, which makes the live "now" point CoinGecko appends the price of today. Asks for the last 365 days
     * (all the public API keeps) on a first download and for the days since [from] plus one otherwise.
     */
    suspend fun priceHistory(id: String, from: LocalDate?): List<PricePoint> {
        val today = clock.now().toUtcDate()
        val days = from?.let { (it.daysUntil(today) + 1).coerceIn(1, MAX_HISTORY_DAYS) } ?: MAX_HISTORY_DAYS
        val body = http.get(historyUrl(id, days))
        val prices = parsingQuotes("CoinGecko history") {
            QuoteJson.decodeFromString<CoinGeckoChartResponse>(body).prices
        } ?: throw QuoteUnavailableException("CoinGecko has no price history for $id")
        val points = parsingQuotes("CoinGecko history") {
            prices.mapNotNull { pair ->
                val millis = pair.getOrNull(0) ?: return@mapNotNull null
                val value = pair.getOrNull(1) ?: return@mapNotNull null
                millis.toLong() to Price.fromDouble(value, Currency.EUR)
            }
        }
        return points.sortedBy { it.first }
            .map { (millis, price) -> PricePoint(Instant.fromEpochMilliseconds(millis).toUtcDate(), price) }
            .associateBy { it.date }.values
            .filter { from == null || it.date >= from }
            .sortedBy { it.date }
    }

    private fun CoinGeckoCoin.toCandidate(): InstrumentCandidate {
        val coinId = checkNotNull(id)
        return InstrumentCandidate(
            ref = InstrumentRef(QuoteProvider.COINGECKO, coinId),
            name = name?.takeIf { it.isNotBlank() } ?: coinId,
            isin = null,
            ticker = symbol?.takeIf { it.isNotBlank() }?.uppercase(),
            type = InstrumentType.CRYPTO,
            currency = Currency.EUR,
            exchange = null,
        )
    }

    private fun Instant.toUtcDate(): LocalDate = toLocalDateTime(TimeZone.UTC).date

    private fun searchUrl(query: String): String = URLBuilder("$BASE_URL/search").apply {
        parameters.append("query", query)
    }.buildString()

    private fun priceUrl(id: String): String = URLBuilder("$BASE_URL/simple/price").apply {
        parameters.append("ids", id)
        parameters.append("vs_currencies", "eur")
        parameters.append("include_last_updated_at", "true")
    }.buildString()

    private fun historyUrl(id: String, days: Int): String =
        "$BASE_URL/coins/${id.encodeURLPathPart()}/market_chart?vs_currency=eur&days=$days&interval=daily"

    companion object {
        /** All the history the public API serves; asking for more (`days=max`) is rejected. */
        const val MAX_HISTORY_DAYS = 365
        const val SEARCH_LIMIT = 8
        private const val BASE_URL = "https://api.coingecko.com/api/v3"
    }
}
