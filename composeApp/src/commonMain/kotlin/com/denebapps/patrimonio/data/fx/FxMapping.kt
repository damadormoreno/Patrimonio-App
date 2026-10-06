package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.RATE_SCALE
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
