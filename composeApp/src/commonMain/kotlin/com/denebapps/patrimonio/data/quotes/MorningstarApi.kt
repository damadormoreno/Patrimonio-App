package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.quotes.dto.MorningstarScreenerResponse
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/**
 * Morningstar funds/ETFs by ISIN (screener) and their latest NAV (daily time series), always priced in EUR.
 *
 * The endpoints and their path tokens (`klr5zyak8x`, `t92wz0sj7c`) are UNOFFICIAL and may change or disappear
 * at any time; the request shapes and field names follow the reference app rumbo (MIT,
 * github.com/danidm98/rumbo, `buscar.py` and `motor.py`). Failures surface as [QuoteUnavailableException].
 *
 * URLs are built as strings, not with Ktor's parameter builder, because the server expects the time-series `id`
 * with literal `]` and `$` while the screener query is percent-encoded like Python's `urlencode`.
 */
class MorningstarApi(private val http: QuoteHttp, private val clock: Clock) {
    /** Funds (`FOESP` universe) and EUR-priced ETF lines (`ETEUR` universe) matching [isin]; no price lookups. */
    suspend fun searchByIsin(isin: String): List<InstrumentCandidate> {
        val body = http.get(screenerUrl(isin))
        val rows = parsingQuotes("Morningstar screener") {
            QuoteJson.decodeFromString<MorningstarScreenerResponse>(body).rows
        }
        return rows.mapNotNull { row ->
            val secId = row.secId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val isFund = row.universe.orEmpty().startsWith(FUND_UNIVERSE_PREFIX)
            // ETF lines in other currencies are skipped, as rumbo does: the series is requested in EUR.
            if (!isFund && row.priceCurrency != Currency.EUR.code) return@mapNotNull null
            InstrumentCandidate(
                ref = InstrumentRef(QuoteProvider.MORNINGSTAR, secId),
                name = row.name?.takeIf { it.isNotBlank() } ?: secId,
                isin = row.isin ?: isin,
                ticker = row.ticker,
                type = if (isFund) InstrumentType.FUND else InstrumentType.ETF,
                currency = Currency.EUR,
                exchange = null,
            )
        }
    }

    /** The last NAV of the last 30 days for [secId], in EUR, dated with the UTC day of the point. */
    suspend fun latestNav(secId: String): Quote {
        val today = clock.now().toLocalDateTime(TimeZone.UTC).date
        val body = http.get(timeseriesUrl(secId, today))
        val (epochMillis, value) = parsingQuotes("Morningstar time series") {
            QuoteJson.parseToJsonElement(body).jsonArray.mapNotNull(::validPoint).lastOrNull()
        } ?: throw QuoteUnavailableException("Morningstar has no recent price for $secId")
        val price = try {
            Price.fromDouble(value, Currency.EUR)
        } catch (failure: IllegalArgumentException) {
            throw QuoteUnavailableException("Morningstar price for $secId is out of range", failure)
        }
        return Quote(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, secId),
            price = price,
            date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date,
        )
    }

    /** A `[epochMs, value]` pair with a positive value; null/short/zero points are skipped like rumbo does. */
    private fun validPoint(element: JsonElement): Pair<Long, Double>? {
        val point = element as? JsonArray ?: return null
        if (point.size < 2) return null
        val epochMillis = (point[0] as? JsonPrimitive)?.longOrNull ?: return null
        val value = (point[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        return if (value > 0.0) epochMillis to value else null
    }

    companion object {
        private const val BASE = "https://lt.morningstar.com/api/rest.svc"
        private const val FUND_UNIVERSE_PREFIX = "FO"
        private const val WINDOW_DAYS = 30

        /** Same parameters, order and escaping as rumbo's `urlencode(q)`: `$` and `|` are percent-encoded. */
        fun screenerUrl(isin: String): String = "$BASE/klr5zyak8x/security/screener?page=1&pageSize=10" +
            "&outputType=json&version=1&languageId=es-ES&currencyId=EUR" +
            "&universeIds=FOESP%24%24ALL%7CETEUR%24%24ALL" +
            "&securityDataPoints=SecId%7CName%7Cisin%7CUniverse%7CPriceCurrency%7CExchangeId%7CTicker%7C" +
            "OngoingCharge%7CCollectedSRRI%7CCategoryName%7CBrandingCompanyName" +
            "&term=${isin.encodeURLParameter()}"

        /** Same as rumbo's `urlencode(q, safe="]$")`: the `id` keeps its literal `]` and `$`. */
        fun timeseriesUrl(secId: String, today: LocalDate): String {
            val start = today.minus(WINDOW_DAYS, DateTimeUnit.DAY)
            return "$BASE/timeseries_price/t92wz0sj7c?currencyId=EUR&idtype=Morningstar&frequency=daily" +
                "&startDate=$start&endDate=$today&outputType=COMPACTJSON" +
                "&id=${secId.encodeURLParameter()}]2]0]FOESP\$\$ALL"
        }
    }
}
