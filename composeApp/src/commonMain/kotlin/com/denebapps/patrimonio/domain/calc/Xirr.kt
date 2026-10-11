package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Money
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.pow

// The annualized money-weighted return (XIRR). Rates and ratios are `Double`; the money in is exact `Money` until the
// last moment, when it is turned into euros to solve the equation.

/** Lower bound of the search: -99.99 % a year, practically a total loss. */
private const val MIN_RATE = -0.9999

/** Upper bound of the search: +1000 % a year. */
private const val MAX_RATE = 10.0

/** The search stops when the net present value, in euros, is this close to zero. */
private const val NPV_TOLERANCE = 1e-7

/** The search also stops when the bracket around the rate is narrower than this. */
private const val RATE_TOLERANCE = 1e-12

private const val MAX_ITERATIONS = 200
private const val DAYS_PER_YEAR = 365.0
private const val MINOR_PER_EURO = 100.0

/**
 * One movement of money between the user and a position on [date], in EUR: negative when it leaves the user's pocket
 * (a purchase, a fee), positive when it comes back (a sale, a dividend, the current value). [transferId] marks the
 * legs of a fund transfer, valued at market, so that a portfolio can cancel the pair out (see [combineCashFlows]).
 */
data class CashFlow(val date: LocalDate, val amountEur: Money, val transferId: String? = null)

/**
 * The annualized return (0.10 = 10 %) that makes the net present value of [flows] zero, with ACT/365 year fractions
 * counted from the earliest flow, the way rumbo does. Bisection over [-99.99 %, +1000 %], so it cannot diverge.
 *
 * Zero flows are ignored. Returns `null` when there is no rate to find: no flow of each sign, every flow on the same
 * day, or a rate outside the search range.
 */
fun xirr(flows: List<CashFlow>): Double? {
    val nonZero = flows.filter { it.amountEur.minorUnits != 0L }
    if (nonZero.none { it.amountEur.minorUnits < 0 } || nonZero.none { it.amountEur.minorUnits > 0 }) return null
    val first = nonZero.minOf { it.date.toEpochDays().toLong() }
    if (nonZero.all { it.date.toEpochDays().toLong() == first }) return null
    val terms = nonZero.map { flow ->
        (flow.date.toEpochDays().toLong() - first) / DAYS_PER_YEAR to flow.amountEur.minorUnits / MINOR_PER_EURO
    }

    fun netPresentValue(rate: Double): Double = terms.sumOf { (years, amount) -> amount / (1.0 + rate).pow(years) }

    var low = MIN_RATE
    var high = MAX_RATE
    var valueLow = netPresentValue(low)
    val valueHigh = netPresentValue(high)
    if (!valueLow.isFinite() || !valueHigh.isFinite() || (valueLow > 0) == (valueHigh > 0)) return null
    repeat(MAX_ITERATIONS) {
        val middle = (low + high) / 2
        val value = netPresentValue(middle)
        if (abs(value) < NPV_TOLERANCE || high - low < RATE_TOLERANCE) return middle
        if ((value > 0) == (valueLow > 0)) {
            low = middle
            valueLow = value
        } else {
            high = middle
        }
    }
    return (low + high) / 2
}
