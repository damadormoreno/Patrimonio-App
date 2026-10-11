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
 * the position is closed is worth zero. Converting the values to EUR is up to the caller.
 */
fun PositionLedger.valueSeries(prices: List<PricePoint>): List<PositionValuePoint> {
    val start = unitsHistory.firstOrNull()?.date ?: return emptyList()
    return prices.filter { it.date >= start }.map { point ->
        val units = unitsOn(point.date)
        val value = units.toUnits()?.let { quotedValue(it, point.price) }
            ?: CurrencyAmount(Money.ZERO, point.price.currency)
        PositionValuePoint(point.date, units, value)
    }
}
