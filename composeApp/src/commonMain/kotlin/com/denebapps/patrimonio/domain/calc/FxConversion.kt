package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import kotlin.math.absoluteValue

/** Converts to EUR using [rates], rounding to EUR minor units via round-half-even. EUR to EUR is
 *  identity (no rounding, no rate lookup).
 *
 *  `eurMinor = roundHalfEven(amount.minorUnits * rateScaled * 10^(EUR.decimals - src.decimals) / RATE_SCALE)`
 */
fun CurrencyAmount.toEur(rates: FxRates): Money {
    if (currency == Currency.EUR) return amount
    val rateScaled = rates.rateToEurScaled(currency)
    val powFactor = pow10(Currency.EUR.decimals - currency.decimals)
    val numerator = amount.minorUnits * rateScaled * powFactor
    return Money(roundHalfEven(numerator, RATE_SCALE))
}

/** Converts to [target] using [rates], going through EUR (the only pivot the rates know about):
 *  [toEur] first, then EUR to [target] (`eurMinor * RATE_SCALE * 10^(target.decimals - EUR.decimals) /
 *  targetRate`, round-half-even). Same-currency is identity (no rounding, no rate lookup). Cross
 *  conversions round twice (once per leg), the same way each leg rounds on its own elsewhere. */
fun CurrencyAmount.convertTo(target: Currency, rates: FxRates): Money {
    if (currency == target) return amount
    val eur = toEur(rates)
    if (target == Currency.EUR) return eur
    val targetRate = rates.rateToEurScaled(target)
    val scaleExponent = target.decimals - Currency.EUR.decimals
    val numerator = eur.minorUnits * RATE_SCALE * pow10(maxOf(scaleExponent, 0))
    val divisor = targetRate * pow10(maxOf(-scaleExponent, 0))
    return Money(roundHalfEven(numerator, divisor))
}

/** Round-half-even ("banker's rounding") integer division of [numerator] by [divisor]. */
fun roundHalfEven(numerator: Long, divisor: Long): Long {
    require(divisor > 0) { "divisor must be positive, was $divisor" }
    val quotient = numerator / divisor
    val remainder = numerator - quotient * divisor
    val doubledRemainder = remainder * 2
    val step = if (numerator >= 0) 1L else -1L
    return when {
        doubledRemainder.absoluteValue < divisor -> quotient
        doubledRemainder.absoluteValue > divisor -> quotient + step
        quotient % 2 == 0L -> quotient
        else -> quotient + step
    }
}

private fun pow10(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= 10 }
    return result
}
