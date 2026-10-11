package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.quotes.dto.MorningstarScreenerResponse
import com.denebapps.patrimonio.data.quotes.dto.MorningstarScreenerRow
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
 * Morningstar funds/ETFs by ISIN (screener, which also carries their fundamentals) and their NAV (daily time series),
 * always priced in EUR.
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
        return screenerRows(isin).mapNotNull { row ->
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
                fundamentals = row.toFundamentals(),
            )
        }
    }

    /**
     * The fundamentals the screener publishes for [isin]: those of the line with SecId [preferredSecId] when it has
     * any, otherwise of the first line that has some (cost, risk and manager belong to the ISIN, whichever line is
     * asked); `null` when no line has any. The screener is asked by ISIN only.
     */
    suspend fun fundamentals(isin: String, preferredSecId: String?): InstrumentFundamentals? {
        val rows = screenerRows(isin)
        val preferred = preferredSecId?.let { secId -> rows.firstOrNull { it.secId == secId }?.toFundamentals() }
        return preferred ?: rows.firstNotNullOfOrNull { it.toFundamentals() }
    }

    private suspend fun screenerRows(isin: String): List<MorningstarScreenerRow> {
        val body = http.get(screenerUrl(isin))
        return parsingQuotes("Morningstar screener") {
            QuoteJson.decodeFromString<MorningstarScreenerResponse>(body).rows
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

    /**
     * The daily NAVs of [secId] in EUR from [from] (inclusive) to today, `null` meaning the last [HISTORY_YEARS] years,
     * as rumbo asks. One point per UTC day, ascending, with weekend-dated NAVs moved to the previous Friday; points
     * that are null, zero or out of the price range are skipped.
     */
    suspend fun priceHistory(secId: String, from: LocalDate?): List<PricePoint> {
        val today = clock.now().toLocalDateTime(TimeZone.UTC).date
        val start = from ?: today.minus(HISTORY_YEARS * DAYS_PER_YEAR, DateTimeUnit.DAY)
        val body = http.get(historyUrl(secId, start, today))
        val points = parsingQuotes("Morningstar time series") {
            QuoteJson.parseToJsonElement(body).jsonArray.mapNotNull(::validPoint)
        }
        return points.sortedBy { it.first }.mapNotNull { (epochMillis, value) ->
            val price = try {
                Price.fromDouble(value, Currency.EUR)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            PricePoint(Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date, price)
        }.weekendsMovedToFriday()
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
        private const val HISTORY_YEARS = 30
        private const val DAYS_PER_YEAR = 365

        /** Same parameters, order and escaping as rumbo's `urlencode(q)`: `$` and `|` are percent-encoded. */
        fun screenerUrl(isin: String): String = "$BASE/klr5zyak8x/security/screener?page=1&pageSize=10" +
            "&outputType=json&version=1&languageId=es-ES&currencyId=EUR" +
            "&universeIds=FOESP%24%24ALL%7CETEUR%24%24ALL" +
            "&securityDataPoints=SecId%7CName%7Cisin%7CUniverse%7CPriceCurrency%7CExchangeId%7CTicker%7C" +
            "OngoingCharge%7CCollectedSRRI%7CCategoryName%7CBrandingCompanyName" +
            "&term=${isin.encodeURLParameter()}"

        /** The last [WINDOW_DAYS] days up to [today]: what the latest NAV needs. */
        fun timeseriesUrl(secId: String, today: LocalDate): String =
            historyUrl(secId, today.minus(WINDOW_DAYS, DateTimeUnit.DAY), today)

        /** Same as rumbo's `urlencode(q, safe="]$")`: the `id` keeps its literal `]` and `$`. */
        fun historyUrl(secId: String, start: LocalDate, end: LocalDate): String =
            "$BASE/timeseries_price/t92wz0sj7c?currencyId=EUR&idtype=Morningstar&frequency=daily" +
                "&startDate=$start&endDate=$end&outputType=COMPACTJSON" +
                "&id=${secId.encodeURLParameter()}]2]0]FOESP\$\$ALL"
    }
}
