package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.quotes.dto.EodhdEodRow
import com.denebapps.patrimonio.data.quotes.dto.EodhdSearchRow
import com.denebapps.patrimonio.data.quotes.dto.EodhdUserResponse
import com.denebapps.patrimonio.domain.calc.roundHalfEven
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.cancellation.CancellationException

/** A listing EODHD knows: [symbol] (`code.exchange`) is what its price endpoints take. [currency] is EODHD's code (`GBX`). */
data class EodhdSymbol(
    val code: String,
    val exchange: String,
    val name: String,
    val type: String?,
    val currency: String?,
    val isin: String?,
    val primary: Boolean,
) {
    val symbol: String get() = "$code.$exchange"
}

/** The calls the account made today ([apiRequests]) out of its [dailyRateLimit]. */
data class EodhdUsage(val apiRequests: Int, val dailyRateLimit: Int, val subscriptionType: String)

/** EODHD refused the key (HTTP 401). The message carries the path and status only, never the key. */
class EodhdInvalidKeyException(path: String) : QuoteUnavailableException("EODHD $path HTTP 401")

/** EODHD says the daily quota is used up (HTTP 402 or 429). The message carries the path and status only. */
class EodhdQuotaExceededException(path: String, status: Int) : QuoteUnavailableException("EODHD $path HTTP $status")

/**
 * EODHD (eodhd.com) search, end-of-day prices and account usage, with the user's own API key. The key travels in the
 * query string, so it is a parameter of every call, never state, and it never reaches an exception: a failure carries
 * the path and the status (`EODHD /eod/IWDA.AS HTTP 402`) and, for a transport error, the exception class, never the
 * transport's message (which can quote the URL) nor the exception itself as the cause.
 *
 * Quota is scarce (20 calls a day on the free plan), so nothing is retried: HTTP 401 is [EodhdInvalidKeyException],
 * HTTP 402 and 429 are [EodhdQuotaExceededException], any other failure is a [QuoteUnavailableException]. EODHD end of
 * day rows carry no currency, so the callers pass the one the symbol is listed in. [clock] sets today.
 */
class EodhdApi(private val client: HttpClient, private val clock: Clock = Clock.System) {
    /** Listings matching [query] (an ISIN, a ticker or a name). An empty list means "nothing found". */
    suspend fun search(apiKey: String, query: String): List<EodhdSymbol> {
        val body = get(apiKey, listOf("search", query.trim()))
        val rows = parsing("EODHD search") { QuoteJson.decodeFromString<List<EodhdSearchRow>>(body) }
        return rows.mapNotNull { row ->
            val code = row.code?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val exchange = row.exchange?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EodhdSymbol(
                code = code,
                exchange = exchange,
                name = row.name?.takeIf { it.isNotBlank() } ?: code,
                type = row.type,
                currency = row.currency?.takeIf { it.isNotBlank() },
                isin = row.isin?.takeIf { it.isNotBlank() },
                primary = row.isPrimary == true,
            )
        }
    }

    /**
     * The daily closes of [symbol] listed in [currencyCode] from [from] (everything the plan allows when `null`: about
     * a year on the free one), one per day and ascending; the later row of a day wins and rows without a close are
     * skipped. Pence (`GBX`, `GBp`) become pounds.
     */
    suspend fun history(apiKey: String, symbol: String, currencyCode: String?, from: LocalDate?): List<PricePoint> {
        val params = listOfNotNull(from?.let { "from" to it.toString() })
        val body = get(apiKey, listOf("eod", symbol), params)
        val rows = parsing("EODHD eod") { QuoteJson.decodeFromString<List<EodhdEodRow>>(body) }
        val points = parsing("EODHD eod") {
            rows.mapNotNull { row ->
                val day = row.date?.let(LocalDate::parse) ?: return@mapNotNull null
                val close = row.close?.takeIf { it > 0.0 } ?: return@mapNotNull null
                eodhdPrice(close, currencyCode)?.let { PricePoint(day, it) }
            }
        }
        if (points.isEmpty() && rows.any { it.close != null }) {
            throw QuoteUnavailableException("EODHD prices for $symbol are unusable (currency $currencyCode)")
        }
        return points.associateBy { it.date }.values.sortedBy { it.date }
    }

    /**
     * The last close of the last [LATEST_WINDOW_DAYS] days of [symbol] as a quote of [ref]. The `real-time` endpoint is
     * not used: it answers `NA` and no date for funds.
     */
    suspend fun latestQuote(apiKey: String, symbol: String, currencyCode: String?, ref: InstrumentRef): Quote {
        val from = today().minus(LATEST_WINDOW_DAYS, DateTimeUnit.DAY)
        val last = history(apiKey, symbol, currencyCode, from).lastOrNull()
            ?: throw QuoteUnavailableException("EODHD has no recent close for $symbol")
        return Quote(ref, last.price, last.date)
    }

    /** The account's quota counters. This call does not consume quota; it also tells a good key from a bad one. */
    suspend fun usage(apiKey: String): EodhdUsage {
        val body = get(apiKey, listOf("user"))
        val user = parsing("EODHD user") { QuoteJson.decodeFromString<EodhdUserResponse>(body) }
        val used = user.apiRequests.asInt()
        val limit = user.dailyRateLimit.asInt()
        if (used == null || limit == null) throw QuoteUnavailableException("Unexpected EODHD user response")
        return EodhdUsage(used, limit, user.subscriptionType.orEmpty())
    }

    /** Like `parsingQuotes`, but without the cause: a serializer error quotes the body, which could echo the key. */
    private inline fun <T> parsing(source: String, block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw QuoteUnavailableException("Unexpected $source response")
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.UTC).date

    private fun JsonPrimitive?.asInt(): Int? = this?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    /** One `GET` without retries; [segments] are the path below `/api` and the only thing an error message quotes. */
    private suspend fun get(
        apiKey: String,
        segments: List<String>,
        params: List<Pair<String, String>> = emptyList(),
    ): String {
        val path = "/" + segments.joinToString("/")
        val url = URLBuilder(BASE_URL).apply {
            appendPathSegments(segments)
            parameters.append("api_token", apiKey)
            parameters.append("fmt", "json")
            params.forEach { (name, value) -> parameters.append(name, value) }
        }.buildString()
        val (status, body) = try {
            val response = client.get(url) {
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
            response.status to response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Neither the message nor the cause: Ktor's timeout and DNS errors quote the URL, key included.
            throw QuoteUnavailableException("EODHD $path network error (${failure::class.simpleName})")
        }
        return when {
            status.isSuccess() -> body
            status.value == HTTP_UNAUTHORIZED -> throw EodhdInvalidKeyException(path)
            status.value == HTTP_PAYMENT_REQUIRED || status.value == HTTP_TOO_MANY_REQUESTS ->
                throw EodhdQuotaExceededException(path, status.value)
            else -> throw QuoteUnavailableException("EODHD $path HTTP ${status.value}")
        }
    }

    companion object {
        const val LATEST_WINDOW_DAYS = 14
        private const val BASE_URL = "https://eodhd.com/api"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/**
 * Converts an EODHD close [value] listed in [currencyCode] to a [Price], or `null` when the currency is not one of the
 * app's [Currency] values or the number is unusable. EODHD numbers are exact decimals, so they keep the six decimals of
 * [Price.fromDouble] (no Yahoo-style denoising); pence (`GBX`, `GBp`) become pounds.
 */
internal fun eodhdPrice(value: Double, currencyCode: String?): Price? {
    val code = currencyCode?.trim() ?: return null
    return try {
        if (code in PENCE_CODES) {
            val pence = Price.fromDouble(value, Currency.GBP)
            Price(roundHalfEven(pence.scaled, PENCE_PER_POUND), Currency.GBP)
        } else {
            val currency = Currency.entries.firstOrNull { it.code == code.uppercase() } ?: return null
            Price.fromDouble(value, currency)
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}
