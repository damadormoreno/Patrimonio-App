package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** The longest gap, in days, a lookup bridges when a date has no rate of its own (weekends, bank holidays). */
const val MAX_FX_FALLBACK_DAYS = 7

/** The rate of a currency to EUR on one [date], scaled by [RATE_SCALE] like [FxRates]. */
data class DatedFxRate(val date: LocalDate, val rateToEurScaled: Long)

/**
 * Daily currency-to-EUR rates over time, to convert an amount of the past (the rates in [FxRates] are only today's).
 * Each list in [toEurScaled] is ascending by date with one rate per day. Frankfurter publishes working days only, so
 * [rateOn] takes the nearest previous rate, at most [MAX_FX_FALLBACK_DAYS] days back, the way rumbo does.
 */
data class FxHistory(val toEurScaled: Map<Currency, List<DatedFxRate>>) {
    /**
     * The scaled rate of [currency] to EUR on [date]: its own, or the latest one before it within
     * [MAX_FX_FALLBACK_DAYS] days. Always [RATE_SCALE] for EUR. `null` when no such rate is stored — a rate is never
     * invented, and a later one is never borrowed.
     */
    fun rateOn(currency: Currency, date: LocalDate): Long? {
        if (currency == Currency.EUR) return RATE_SCALE
        val series = toEurScaled[currency] ?: return null
        val found = series.binarySearchBy(date) { it.date }
        val index = if (found >= 0) found else -found - 2
        if (index < 0) return null
        val rate = series[index]
        return rate.rateToEurScaled.takeIf { rate.date >= date.minus(MAX_FX_FALLBACK_DAYS, DateTimeUnit.DAY) }
    }
}
