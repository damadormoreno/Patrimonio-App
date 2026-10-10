package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units

/** `unitsScaled * priceScaled` carries both scales; dividing by this yields major units of the currency. */
private const val MAJOR_UNIT_SCALE: Long = UNITS_SCALE * PRICE_SCALE

/**
 * Value of [units] at [price], in minor units of the price's currency:
 * `roundHalfEven(unitsScaled * priceScaled * 10^decimals / (UNITS_SCALE * PRICE_SCALE))`. The product is carried at
 * 128 bits (see [mulDivHalfEven]), so ten million units at 100,000 EUR are fine.
 *
 * @throws ArithmeticException when the value does not fit in a [Long] of minor units.
 */
fun quotedValue(units: Units, price: Price): CurrencyAmount {
    val minorUnits = mulDivHalfEven(units.scaled, price.scaled, MAJOR_UNIT_SCALE / minorPerMajor(price.currency))
    return CurrencyAmount(Money(minorUnits), price.currency)
}

/**
 * Current value of the holding in its own currency, or `null` while a quoted holding has no price yet. A manual
 * holding is worth the amount the user entered.
 */
fun PortfolioHolding.value(): CurrencyAmount? = when (val valuation = valuation) {
    is HoldingValuation.Quoted -> valuation.lastQuote?.let { quotedValue(valuation.units, it.price) }
    is HoldingValuation.Manual -> valuation.value
}

/**
 * Units that make up a [total] (minor units of the price's currency) at [price], rounded half-even to 6 decimals:
 * the "I know what it is worth now" way of entering a holding.
 *
 * @throws IllegalArgumentException when [total] is not positive, [price] is zero, or the total is so small next to
 *   the price that it rounds to no units at all.
 * @throws ArithmeticException when the units do not fit in the fixed-point range.
 */
fun unitsFromTotal(total: Money, price: Price): Units {
    require(total.minorUnits > 0) { "total must be positive, was ${total.minorUnits}" }
    require(price.scaled > 0) { "price must be positive to derive units from a total" }
    val scaled = mulDivHalfEven(total.minorUnits, MAJOR_UNIT_SCALE / minorPerMajor(price.currency), price.scaled)
    require(scaled > 0) { "total ${total.minorUnits} is too small for price ${price.scaled} to hold any units" }
    return Units(scaled)
}

/**
 * Sum of every holding's value converted to EUR with [rates]. Quoted holdings that have no price yet are skipped
 * rather than counted as zero-valued positions.
 *
 * @throws ArithmeticException when the total overflows a [Long].
 */
fun portfolioTotalEur(holdings: List<PortfolioHolding>, rates: FxRates): Money = Money(
    holdings
        .mapNotNull { it.value() }
        .fold(0L) { total, value -> checkedAdd(total, value.toEur(rates).minorUnits) },
)

private fun minorPerMajor(currency: Currency): Long {
    var result = 1L
    repeat(currency.decimals) { result *= 10 }
    return result
}
