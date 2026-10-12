package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Units
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitSplitTest {
    @Test
    fun `units are shared in proportion to the market value`() {
        val split = splitUnits(lotsWorth(100, 300), Units(1_000_000)).ok()

        assertEquals(listOf(Units(250_000), Units(750_000)), split)
    }

    @Test
    fun `the units left by the rounding go to the largest remainders`() {
        // Quotas 1.33 and 2.67: the leftover unit goes to the second lot.
        assertEquals(listOf(Units(1), Units(3)), splitUnits(lotsWorth(1, 2), Units(4)).ok())
    }

    @Test
    fun `equal remainders are broken in favour of the earlier lot`() {
        assertEquals(listOf(Units(4), Units(3), Units(3)), splitUnits(lotsWorth(1, 1, 1), Units(10)).ok())
        assertEquals(listOf(Units(4), Units(4), Units(3)), splitUnits(lotsWorth(1, 1, 1), Units(11)).ok())
    }

    @Test
    fun `the shares always add up exactly to the total and stay within one unit of the exact quota`() {
        val worths = listOf(1L, 7L, 13L, 29_999L, 123_456L, 3L, 1_000_000L)
        val totals = listOf(1_234_567L, 3_000_000L, 8_100_001L, 999_999_999_999L)
        for (total in totals) {
            val lots = lotsWorth(*worths.toLongArray())
            val split = splitUnits(lots, Units(total)).ok()

            assertEquals(total, split.sumOf { it.scaled }, "total $total")
            val sumWorth = worths.sum()
            split.forEachIndexed { index, units ->
                // |units - total * worth / sumWorth| < 1, compared without division: |units * W - total * w| < W.
                val gap = units.scaled * sumWorth - total * worths[index]
                assertTrue(gap > -sumWorth && gap < sumWorth, "lot $index of total $total is off by $gap/$sumWorth")
            }
        }
    }

    @Test
    fun `no overflow with a large fund and large market values`() {
        // 9 million units (9e12 scaled) over lots worth 50 million EUR (5e9 cents) each.
        val split = splitUnits(lotsWorth(5_000_000_000, 5_000_000_000, 5_000_000_001), Units(9_000_000_000_000)).ok()

        assertEquals(9_000_000_000_000, split.sumOf { it.scaled })
        assertTrue(split.all { it.scaled in 2_999_999_999_000..3_000_000_001_000 })
    }

    @Test
    fun `a total too small to give every lot a unit is refused`() {
        assertEquals(UnitSplitError.TotalTooSmall, splitUnits(lotsWorth(100, 100, 100), Units(2)).err())
        // The lot worth almost nothing would round to no units at all.
        assertEquals(UnitSplitError.TotalTooSmall, splitUnits(lotsWorth(1, 1_000_000), Units(500_000)).err())
    }

    @Test
    fun `exactly one unit per lot is enough`() {
        assertEquals(listOf(Units(1), Units(1), Units(1)), splitUnits(lotsWorth(5, 5, 5), Units(3)).ok())
    }

    @Test
    fun `a lot without market value cannot be given units`() {
        assertEquals(UnitSplitError.LotWithoutMarketValue(1), splitUnits(lotsWorth(100, 0, 50), Units(1_000)).err())
    }

    @Test
    fun `there is nothing to split without lots`() {
        assertFailsWith<IllegalArgumentException> { splitUnits(emptyList(), Units(10)) }
    }

    @Test
    fun `the units estimate is the market value over the latest EUR price, rounded half to even`() {
        assertEquals(Units(250_000_000), estimateTotalUnits(lotsWorth(100_000), eur(4_000_000)))
        assertEquals(Units(333_333_333), estimateTotalUnits(lotsWorth(100_000), eur(3_000_000)))
        // 0.01 EUR / 4000 EUR = 2.5 millionths of a unit: ties go to the even one; 0.03 / 4000 = 7.5 goes to 8.
        assertEquals(Units(2), estimateTotalUnits(lotsWorth(1), eur(4_000_000_000)))
        assertEquals(Units(8), estimateTotalUnits(lotsWorth(3), eur(4_000_000_000)))
    }

    @Test
    fun `the units estimate adds the market value of all the lots`() {
        assertEquals(Units(30_000_000), estimateTotalUnits(lotsWorth(1_000, 2_000), eur(1_000_000)))
    }

    @Test
    fun `there is no units estimate without a price, a market value or a usable price`() {
        assertNull(estimateTotalUnits(lotsWorth(100_000), null))
        assertNull(estimateTotalUnits(lotsWorth(100_000), eur(0)))
        assertNull(estimateTotalUnits(lotsWorth(0), eur(1_000_000)))
    }

    @Test
    fun `the units estimate is for EUR prices only`() {
        assertFailsWith<IllegalArgumentException> {
            estimateTotalUnits(lotsWorth(100_000), Price(1_000_000, Currency.USD))
        }
    }

    @Test
    fun `units look off when units times price is more than 2 percent away from the market value`() {
        val units = Units(100_000_000) // 100 units
        val price = eur(10_000_000) // 10 EUR: worth 1000.00

        assertFalse(unitsLookOff(units, price, Money(100_000)))
        // 1000.00 against a market value of 1020.40 (1.9992 %) and 1020.41 (2.0001 %).
        assertFalse(unitsLookOff(units, price, Money(102_040)))
        assertTrue(unitsLookOff(units, price, Money(102_041)))
        assertFalse(unitsLookOff(units, price, Money(98_040)))
        assertTrue(unitsLookOff(units, price, Money(98_039)))
    }

    @Test
    fun `exactly 2 percent is not off, a hair more is`() {
        val price = eur(10_000_000)

        assertFalse(unitsLookOff(Units(102_000_000), price, Money(100_000)))
        assertTrue(unitsLookOff(Units(102_000_001), price, Money(100_000)))
        assertFalse(unitsLookOff(Units(98_000_000), price, Money(100_000)))
        assertTrue(unitsLookOff(Units(97_999_999), price, Money(100_000)))
    }

    @Test
    fun `units far from the file are off in both directions`() {
        val price = eur(10_000_000)

        assertTrue(unitsLookOff(Units(10_000_000), price, Money(100_000)))
        assertTrue(unitsLookOff(Units(1_000_000_000), price, Money(100_000)))
    }

    private fun lotsWorth(vararg marketValueCents: Long) = marketValueCents.map { cents ->
        OpenLot(LocalDate(2024, 1, 1), Money(cents), Money(cents))
    }

    private fun eur(scaled: Long) = Price(scaled, Currency.EUR)
}
