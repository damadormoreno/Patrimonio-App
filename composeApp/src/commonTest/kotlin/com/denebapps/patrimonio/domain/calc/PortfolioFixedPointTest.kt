package com.denebapps.patrimonio.domain.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortfolioFixedPointTest {
    @Test
    fun `exact quotients are returned unchanged`() {
        assertEquals(21L, mulDivHalfEven(6, 7, 2))
        assertEquals(0L, mulDivHalfEven(0, 123_456_789, 7))
        assertEquals(0L, mulDivHalfEven(123_456_789, 0, 7))
    }

    @Test
    fun `ties round half to even`() {
        assertEquals(0L, mulDivHalfEven(1, 5, 10))
        assertEquals(2L, mulDivHalfEven(3, 5, 10))
        assertEquals(2L, mulDivHalfEven(5, 5, 10))
        assertEquals(4L, mulDivHalfEven(7, 5, 10))
    }

    @Test
    fun `non ties round to the nearest value`() {
        assertEquals(1L, mulDivHalfEven(6, 1, 10))
        assertEquals(0L, mulDivHalfEven(4, 1, 10))
    }

    @Test
    fun `negative results round symmetrically`() {
        assertEquals(0L, mulDivHalfEven(-1, 5, 10))
        assertEquals(-2L, mulDivHalfEven(-3, 5, 10))
        assertEquals(-2L, mulDivHalfEven(5, -5, 10))
        assertEquals(2L, mulDivHalfEven(-3, -5, 10))
    }

    @Test
    fun `agrees with the long based rounding wherever the product fits`() {
        for (a in -50L..50L) {
            for (b in -7L..7L) {
                for (divisor in 1L..12L) {
                    assertEquals(roundHalfEven(a * b, divisor), mulDivHalfEven(a, b, divisor), "$a * $b / $divisor")
                }
            }
        }
    }

    @Test
    fun `a product beyond the long range is divided exactly`() {
        // 1e13 * 1e11 = 1e24, far beyond Long.MAX_VALUE (about 9.2e18); the quotient 1e14 fits.
        assertEquals(100_000_000_000_000L, mulDivHalfEven(10_000_000_000_000L, 100_000_000_000L, 10_000_000_000L))
        assertEquals(Long.MAX_VALUE, mulDivHalfEven(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, mulDivHalfEven(Long.MAX_VALUE, 2, 2))
    }

    @Test
    fun `the result may be exactly Long MAX_VALUE or Long MIN_VALUE`() {
        assertEquals(Long.MAX_VALUE, mulDivHalfEven(Long.MAX_VALUE, 3, 3))
        assertEquals(Long.MIN_VALUE, mulDivHalfEven(Long.MIN_VALUE, 1, 1))
        assertEquals(Long.MIN_VALUE, mulDivHalfEven(Long.MIN_VALUE, 5, 5))
        // 65535 * 281479271743489 == 2^64 - 1 == 2 * MAX_VALUE + 1: a tie that rounds the magnitude up to 2^63.
        assertEquals(Long.MIN_VALUE, mulDivHalfEven(-65_535, 281_479_271_743_489, 2))
    }

    @Test
    fun `a genuine overflow of the result throws`() {
        assertFailsWith<ArithmeticException> { mulDivHalfEven(Long.MAX_VALUE, 2, 1) }
        assertFailsWith<ArithmeticException> { mulDivHalfEven(Long.MAX_VALUE, Long.MAX_VALUE, 1) }
        assertFailsWith<ArithmeticException> { mulDivHalfEven(Long.MIN_VALUE, -1, 1) }
        assertFailsWith<ArithmeticException> { mulDivHalfEven(Long.MIN_VALUE, 2, 1) }
        // Rounding the tie 2^63 - 0.5 up lands exactly one past Long.MAX_VALUE.
        assertFailsWith<ArithmeticException> { mulDivHalfEven(65_535, 281_479_271_743_489, 2) }
    }

    @Test
    fun `a non positive divisor is rejected`() {
        assertFailsWith<IllegalArgumentException> { mulDivHalfEven(1, 1, 0) }
        assertFailsWith<IllegalArgumentException> { mulDivHalfEven(1, 1, -3) }
    }

    @Test
    fun `checked add sums within range and throws instead of wrapping`() {
        assertEquals(5L, checkedAdd(2, 3))
        assertEquals(-1L, checkedAdd(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, checkedAdd(Long.MAX_VALUE - 1, 1))
        assertFailsWith<ArithmeticException> { checkedAdd(Long.MAX_VALUE, 1) }
        assertFailsWith<ArithmeticException> { checkedAdd(Long.MIN_VALUE, -1) }
    }
}
