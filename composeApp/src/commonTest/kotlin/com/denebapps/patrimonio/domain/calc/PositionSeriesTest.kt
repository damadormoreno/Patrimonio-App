package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.sell
import com.denebapps.patrimonio.domain.model.units
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionSeriesTest {
    private fun price(day: LocalDate, whole: Long, millionths: Long = 0, currency: Currency = Currency.EUR) =
        PricePoint(day, Price(whole * 1_000_000 + millionths, currency))

    private fun point(day: LocalDate, unitsScaled: Long, cents: Long) =
        PositionValuePoint(day, UnitsBalance(unitsScaled), CurrencyAmount(Money(cents), Currency.EUR))

    private val ledger = computeLedgers(
        listOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000),
            buy("b2", date(2025, 2, 10), units(5), cents = 60_000),
            sell("s1", date(2025, 3, 10), units(15), cents = 195_000),
        ),
    ).getValue(FUND_A)

    @Test
    fun `the value of the position is the units held that day times that day's price`() {
        // 2025-01-10: 10 u x 100.00 = 1000.00;  2025-01-20: 10 u x 110.50 = 1105.00;
        // 2025-02-10: 15 u x 120.00 = 1800.00;  2025-03-10: sold out -> 0 u x 130.00 = 0.00.
        val prices = listOf(
            price(date(2025, 1, 9), 99),
            price(date(2025, 1, 10), 100),
            price(date(2025, 1, 20), 110, 500_000),
            price(date(2025, 2, 10), 120),
            price(date(2025, 3, 10), 130),
            price(date(2025, 3, 11), 131),
        )

        val series = ledger.valueSeries(prices)

        assertEquals(
            listOf(
                point(date(2025, 1, 10), 10_000_000, 100_000),
                point(date(2025, 1, 20), 10_000_000, 110_500),
                point(date(2025, 2, 10), 15_000_000, 180_000),
                point(date(2025, 3, 10), 0, 0),
                point(date(2025, 3, 11), 0, 0),
            ),
            series,
        )
    }

    @Test
    fun `prices before the first movement are left out`() {
        val series = ledger.valueSeries(
            listOf(price(date(2024, 12, 1), 90), price(date(2025, 1, 9), 99), price(date(2025, 3, 11), 131)),
        )

        assertEquals(listOf(point(date(2025, 3, 11), 0, 0)), series)
    }

    @Test
    fun `a movement after the last price ends the series on that day at the last price`() {
        // A sale newer than the latest quote: 6 u left on 2025-01-20, valued at the 2025-01-15 price (6 x 110 = 660).
        val recent = computeLedgers(
            listOf(
                buy("b1", date(2025, 1, 10), units(10), cents = 100_000),
                sell("s1", date(2025, 1, 20), units(4), cents = 44_000),
            ),
        ).getValue(FUND_A)

        val series = recent.valueSeries(listOf(price(date(2025, 1, 10), 100), price(date(2025, 1, 15), 110)))

        assertEquals(
            listOf(
                point(date(2025, 1, 10), 10_000_000, 100_000),
                point(date(2025, 1, 15), 10_000_000, 110_000),
                point(date(2025, 1, 20), 6_000_000, 66_000),
            ),
            series,
        )
    }

    @Test
    fun `without prices there is no series`() {
        assertTrue(ledger.valueSeries(emptyList()).isEmpty())
    }

    @Test
    fun `the series keeps the currency of the prices`() {
        // 10 u x 12.345678 USD = 123.45678 -> 123.46 USD (12_346 cents), rounded half-even.
        val series = ledger.valueSeries(listOf(price(date(2025, 1, 15), 12, 345_678, Currency.USD)))

        assertEquals(CurrencyAmount(Money(12_346), Currency.USD), series.first().value)
    }
}
