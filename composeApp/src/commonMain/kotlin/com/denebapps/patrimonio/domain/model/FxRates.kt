package com.denebapps.patrimonio.domain.model

/** Scale factor for FX rates stored as scaled [Long]: `RATE_SCALE` == a rate of 1.0 (6 dp). */
const val RATE_SCALE: Long = 1_000_000L

/**
 * Compiled-in static seed rates (currency -> EUR), used as a per-currency fallback when a
 * persisted/cached rate is unavailable. Never a 1:1 identity fallback — every non-EUR currency
 * has its own dedicated seed value.
 */
object FxSeedRates {
    val scaled: Map<Currency, Long> = mapOf(
        Currency.EUR to RATE_SCALE,
        Currency.USD to 920_000L,
        Currency.GBP to 1_170_000L,
        Currency.JPY to 6_100L,
    )
}

/** Currently persisted/cached FX rates (currency -> EUR), scaled by [RATE_SCALE]. */
data class FxRates(val toEurScaled: Map<Currency, Long>) {
    fun rateToEurScaled(currency: Currency): Long = when (currency) {
        Currency.EUR -> RATE_SCALE
        Currency.USD, Currency.GBP, Currency.JPY ->
            toEurScaled[currency] ?: FxSeedRates.scaled.getValue(currency)
    }
    // No unknown-currency branch: `when` is exhaustive over the closed Currency enum — adding a
    // new Currency forces a compiler error here (and in FxSeedRates.scaled) until a seed entry
    // exists; no runtime throw is reachable or needed.
}
