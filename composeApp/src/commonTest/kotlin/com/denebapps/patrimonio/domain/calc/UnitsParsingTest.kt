package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnitsParsingTest {
    @Test
    fun `parses a comma-decimal number with up to six decimals`() {
        assertEquals(Units(12_345_678), parseUnitsEs("12,345678"))
        assertEquals(Units(12_500_000), parseUnitsEs("12,5"))
        assertEquals(Units(12_000_000), parseUnitsEs("12"))
        assertEquals(Units(1), parseUnitsEs("0,000001"))
    }

    @Test
    fun `accepts period thousands separators`() {
        assertEquals(Units(1_234_500_000), parseUnitsEs("1.234,5"))
        assertEquals(Units(1_234_567_000_000), parseUnitsEs("1.234.567"))
    }

    @Test
    fun `a trailing comma and a leading comma are read as a missing zero`() {
        assertEquals(Units(12_000_000), parseUnitsEs("12,"))
        assertEquals(Units(500_000), parseUnitsEs(",5"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(Units(12_500_000), parseUnitsEs(" 12,5 "))
    }

    @Test
    fun `rejects zero, negatives and blanks`() {
        assertNull(parseUnitsEs("0"))
        assertNull(parseUnitsEs("0,000000"))
        assertNull(parseUnitsEs("-1"))
        assertNull(parseUnitsEs(""))
        assertNull(parseUnitsEs("   "))
        assertNull(parseUnitsEs(","))
    }

    @Test
    fun `rejects more than six decimals`() {
        assertNull(parseUnitsEs("1,1234567"))
    }

    @Test
    fun `rejects malformed text`() {
        assertNull(parseUnitsEs("abc"))
        assertNull(parseUnitsEs("1,2,3"))
        assertNull(parseUnitsEs("1e3"))
        assertNull(parseUnitsEs("12 5"))
    }

    @Test
    fun `rejects numbers that overflow the fixed-point range`() {
        assertNull(parseUnitsEs("99999999999999"))
    }

    @Test
    fun `formats grouped with at least two decimals and no trailing zeros beyond them`() {
        assertEquals("12,345678", formatUnits(Units(12_345_678)))
        assertEquals("12,50", formatUnits(Units(12_500_000)))
        assertEquals("12,3456", formatUnits(Units(12_345_600)))
        assertEquals("1,00", formatUnits(Units(1_000_000)))
        assertEquals("0,000001", formatUnits(Units(1)))
        assertEquals("1.234,00", formatUnits(Units(1_234_000_000)))
        assertEquals("1.234.567,891234", formatUnits(Units(1_234_567_891_234)))
    }

    @Test
    fun `input text is ungrouped with trailing zeros trimmed`() {
        assertEquals("12,345678", unitsInputText(Units(12_345_678)))
        assertEquals("12", unitsInputText(Units(12_000_000)))
        assertEquals("1234,5", unitsInputText(Units(1_234_500_000)))
        assertEquals("0,000001", unitsInputText(Units(1)))
    }

    @Test
    fun `input text parses back to the same units`() {
        listOf(1L, 999_999L, 1_000_000L, 12_345_678L, 1_234_500_000L, 9_000_000_000_000L).forEach { scaled ->
            assertEquals(Units(scaled), parseUnitsEs(unitsInputText(Units(scaled))))
        }
    }

    @Test
    fun `formatted units parse back to the same units`() {
        listOf(1L, 12_345_678L, 1_234_567_891_234L).forEach { scaled ->
            assertEquals(Units(scaled), parseUnitsEs(formatUnits(Units(scaled))))
        }
    }
}
