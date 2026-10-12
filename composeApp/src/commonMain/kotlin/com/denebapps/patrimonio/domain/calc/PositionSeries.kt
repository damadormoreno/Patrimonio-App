package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.UnitsBalance
import kotlinx.datetime.LocalDate

/** The position on [date]: the [units] held at the end of the day and their [value] at that day's price. */
data class PositionValuePoint(val date: LocalDate, val units: UnitsBalance, val value: CurrencyAmount)

/**
 * The value of the position over time: for each of [prices] (ascending by date, in the currency of the product), the
 * units held that day times the price. Prices before the position's first movement are left out; a day on which
 * the position is closed is worth zero. A movement newer than the last price ends the series on its date, valued at
 * that last price, so the series ends on the units held now. Converting the values to EUR is up to the caller.
 */
fun PositionLedger.valueSeries(prices: List<PricePoint>): List<PositionValuePoint> {
    val start = unitsHistory.firstOrNull()?.date ?: return emptyList()
    val latest = prices.lastOrNull() ?: return emptyList()
    val lastMovement = unitsHistory.last().date
    val points = prices.filter { it.date >= start }.map { pointOn(it.date, it) }
    return if (lastMovement > latest.date) points + pointOn(lastMovement, latest) else points
}

private fun PositionLedger.pointOn(date: LocalDate, price: PricePoint): PositionValuePoint {
    val units = unitsOn(date)
    val value = units.toUnits()?.let { quotedValue(it, price.price) }
        ?: CurrencyAmount(Money.ZERO, price.price.currency)
    return PositionValuePoint(date, units, value)
}
