package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PriceTest {
    @Test
    fun `scale is six decimals of the major unit`() {
        assertEquals(1_000_000L, PRICE_SCALE)
    }

    @Test
    fun `fromDouble scales major units to six decimals`() {
        assertEquals(Price(12_345_678L, Currency.EUR), Price.fromDouble(12.345678, Currency.EUR))
        assertEquals(Price(125_000L, Currency.USD), Price.fromDouble(0.125, Currency.USD))
        assertEquals(Price(0L, Currency.GBP), Price.fromDouble(0.0, Currency.GBP))
    }

    @Test
    fun `fromDouble rounds beyond the sixth decimal to the nearest value`() {
        assertEquals(12_345_679L, Price.fromDouble(12.3456786, Currency.EUR).scaled)
        assertEquals(12_345_678L, Price.fromDouble(12.3456784, Currency.EUR).scaled)
    }

    @Test
    fun `fromDouble rounds exact ties half to even`() {
        // 2^-7 and 3 * 2^-7 are exactly representable, so the scaled values are exact .5 ties.
        assertEquals(7_812L, Price.fromDouble(0.0078125, Currency.EUR).scaled)
        assertEquals(23_438L, Price.fromDouble(0.0234375, Currency.EUR).scaled)
    }

    @Test
    fun `fromDouble rejects values that overflow the fixed point range`() {
        assertFailsWith<IllegalArgumentException> { Price.fromDouble(1.0E13, Currency.EUR) }
        assertFailsWith<IllegalArgumentException> { Price.fromDouble(Double.MAX_VALUE, Currency.EUR) }
    }

    @Test
    fun `fromDouble accepts the largest comfortably representable price`() {
        assertEquals(1_000_000_000_000_000_000L, Price.fromDouble(1.0E12, Currency.EUR).scaled)
    }

    @Test
    fun `fromDouble rejects non finite and negative values`() {
        assertFailsWith<IllegalArgumentException> { Price.fromDouble(Double.NaN, Currency.EUR) }
        assertFailsWith<IllegalArgumentException> { Price.fromDouble(Double.POSITIVE_INFINITY, Currency.EUR) }
        assertFailsWith<IllegalArgumentException> { Price.fromDouble(-0.01, Currency.EUR) }
    }

    @Test
    fun `constructor rejects a negative scaled value`() {
        assertFailsWith<IllegalArgumentException> { Price(-1L, Currency.EUR) }
    }
}
