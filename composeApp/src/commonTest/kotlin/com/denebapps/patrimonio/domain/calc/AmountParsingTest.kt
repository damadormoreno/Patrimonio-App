package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountParsingTest {
    @Test
    fun `parses comma-decimal locale digits with thousands separators into EUR minor units`() {
        assertEquals(123456L, parseAmountToMinor("1.234,56", Currency.EUR))
    }

    @Test
    fun `parses a plain integer with no decimal part`() {
        assertEquals(500000L, parseAmountToMinor("5000", Currency.EUR))
    }

    @Test
    fun `pads a single decimal digit to two minor-unit digits`() {
        assertEquals(150L, parseAmountToMinor("1,5", Currency.EUR))
    }

    @Test
    fun `JPY has zero decimals so thousands-separated digits map 1-to-1 to minor units`() {
        assertEquals(1234L, parseAmountToMinor("1.234", Currency.JPY))
    }

    @Test
    fun `a comma decimal part is invalid for a zero-decimal currency`() {
        assertNull(parseAmountToMinor("1234,5", Currency.JPY))
    }

    @Test
    fun `blank text is invalid`() {
        assertNull(parseAmountToMinor("", Currency.EUR))
        assertNull(parseAmountToMinor("   ", Currency.EUR))
    }

    @Test
    fun `negative amounts are invalid`() {
        assertNull(parseAmountToMinor("-5,00", Currency.EUR))
    }

    @Test
    fun `zero amount is invalid`() {
        assertNull(parseAmountToMinor("0,00", Currency.EUR))
        assertNull(parseAmountToMinor("0", Currency.JPY))
    }

    @Test
    fun `non-numeric text is invalid`() {
        assertNull(parseAmountToMinor("abc", Currency.EUR))
    }

    @Test
    fun `too many decimal digits for the currency is invalid`() {
        assertNull(parseAmountToMinor("1,234", Currency.EUR))
    }
}
