package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import kotlinx.datetime.LocalDate
import kotlin.math.round

/**
 * Inverts a Frankfurter EUR-to-currency [Double] rate (e.g. `rates["USD"] = 1.0870`, meaning
 * 1 EUR = 1.0870 USD) into a currency-to-EUR scaled `Long` (design.md FX Pipeline:
 * `rateToEurScaled(c) = roundHalfEven(RATE_SCALE / rates[c])`). [kotlin.math.round] rounds a
 * [Double] ties-to-even, matching the half-even requirement without needing the integer
 * `roundHalfEven` from `domain.calc.FxConversion` (that overload takes two `Long`s; this is a
 * `Double` division, a different arithmetic shape reusing the same rounding rule).
 */
fun invertAndScale(rateEurToCurrency: Double): Long {
    require(rateEurToCurrency.isFinite() && rateEurToCurrency > 0) {
        "rate must be finite and positive, was $rateEurToCurrency"
    }
    return round(RATE_SCALE.toDouble() / rateEurToCurrency).toLong()
}

/**
 * Validates a Frankfurter response's [rates] against the [requested] currencies and inverts each
 * to a scaled `Long`. Every requested symbol MUST be present AND every rate MUST be finite and
 * strictly positive — otherwise the whole response MUST be rejected (spec: fetch-failure-non-fatal),
 * matching a network failure so the caller's `runCatching` treats it identically.
 */
fun validateRates(rates: Map<String, Double>, requested: List<Currency>): Map<Currency, Long> =
    requested.associateWith { currency ->
        val rate = rates[currency.code]
            ?: error("Frankfurter response missing requested symbol ${currency.code}")
        invertAndScale(rate)
    }

/**
 * Inverts a Frankfurter time series ([rates]: day -> currency code -> amount per EUR) into the scaled
 * currency-to-EUR rates of each [requested] currency, ascending by date, with the same [invertAndScale] as the latest
 * rates. Unlike [validateRates], a gap is not fatal for a history: a day without a usable rate for a currency (missing,
 * zero, negative) is simply skipped, so one bad day never discards years of good ones.
 *
 * @throws IllegalArgumentException when a day key is not a `YYYY-MM-DD` date.
 */
fun scaleSeries(rates: Map<String, Map<String, Double>>, requested: List<Currency>): Map<Currency, List<DatedFxRate>> {
    val days = rates.map { (day, perCurrency) -> LocalDate.parse(day) to perCurrency }.sortedBy { it.first }
    return requested.associateWith { currency ->
        days.mapNotNull { (day, perCurrency) ->
            val rate = perCurrency[currency.code]?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
            DatedFxRate(day, invertAndScale(rate))
        }
    }
}
