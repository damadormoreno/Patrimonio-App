package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RebalanceFormatTest {
    @Test
    fun `a share in basis points shows its decimals only when it has them`() {
        assertEquals("72 %", formatBp(7_200))
        assertEquals("12,5 %", formatBp(1_250))
        assertEquals("72,33 %", formatBp(7_233))
        assertEquals("0 %", formatBp(0))
        assertEquals("100 %", formatBp(10_000))
        assertEquals("0,05 %", formatBp(5))
    }

    @Test
    fun `a drift in points carries its sign, with a true minus`() {
        assertEquals("−8 pts", formatPoints(-800))
        assertEquals("+10 pts", formatPoints(1_000))
        assertEquals("+7,5 pts", formatPoints(750))
        assertEquals("−0,25 pts", formatPoints(-25))
        assertEquals("0 pts", formatPoints(0))
        assertEquals("+1 pt", formatPoints(100))
    }

    @Test
    fun `a tolerance in points has no sign`() {
        assertEquals("5 pts", formatPoints(500, signed = false))
        assertEquals("2,5 pts", formatPoints(250, signed = false))
        assertEquals("0 pts", formatPoints(0, signed = false))
    }

    @Test
    fun `the text of a field is the share with a decimal comma and no trailing zeros`() {
        assertEquals("80", bpInputText(8_000))
        assertEquals("12,5", bpInputText(1_250))
        assertEquals("12,05", bpInputText(1_205))
        assertEquals("0", bpInputText(0))
    }

    @Test
    fun `the input keeps digits and one decimal comma, two decimals and three whole digits at most`() {
        assertEquals("12,5", filterPercentInput("12.5"))
        assertEquals("12,5", filterPercentInput("12,5"))
        assertEquals("12", filterPercentInput("1a2"))
        assertEquals("12,34", filterPercentInput("12,345"))
        assertEquals("100", filterPercentInput("1000"))
        assertEquals("", filterPercentInput("abc"))
        assertEquals("5,", filterPercentInput("5,"))
    }

    @Test
    fun `a typed share becomes basis points exactly`() {
        assertEquals(1_250, parsePercentToBp("12,5"))
        assertEquals(8_000, parsePercentToBp("80"))
        assertEquals(1_205, parsePercentToBp("12,05"))
        assertEquals(500, parsePercentToBp("5,"))
        assertEquals(50, parsePercentToBp(",5"))
        assertEquals(0, parsePercentToBp(""))
        assertEquals(0, parsePercentToBp("  "))
    }

    @Test
    fun `a text that is not a share has no value`() {
        assertNull(parsePercentToBp(","))
        assertNull(parsePercentToBp("1,2,3"))
        assertNull(parsePercentToBp("12,345"))
        assertNull(parsePercentToBp("-5"))
    }

    @Test
    fun `the current split is rounded to whole percents that add up to 100`() {
        val rounded = roundedPercentSplit(
            mapOf(
                AssetClass.EQUITY to Money(100_000),
                AssetClass.FIXED_INCOME to Money(100_000),
                AssetClass.MONEY_MARKET to Money(100_000),
            ),
        )

        // 33,33 each: the missing point goes to the first of the equal fractions.
        assertEquals(
            mapOf(AssetClass.EQUITY to 34, AssetClass.FIXED_INCOME to 33, AssetClass.MONEY_MARKET to 33),
            rounded,
        )
        assertEquals(100, rounded.values.sum())
    }

    @Test
    fun `the largest fractions get the missing points`() {
        // 62,4 %, 25,3 %, 12,3 %: floors 62 + 25 + 12 = 99, the point goes to the largest fraction (0,4).
        val rounded = roundedPercentSplit(
            mapOf(
                AssetClass.EQUITY to Money(624),
                AssetClass.FIXED_INCOME to Money(253),
                AssetClass.CRYPTO to Money(123),
            ),
        )

        assertEquals(mapOf(AssetClass.EQUITY to 63, AssetClass.FIXED_INCOME to 25, AssetClass.CRYPTO to 12), rounded)
    }

    @Test
    fun `classes that round to nothing and an empty split give no entries`() {
        val rounded = roundedPercentSplit(
            mapOf(AssetClass.EQUITY to Money(100_000), AssetClass.CRYPTO to Money(1), AssetClass.OTHER to Money.ZERO),
        )

        assertEquals(mapOf(AssetClass.EQUITY to 100), rounded)
        assertEquals(emptyMap(), roundedPercentSplit(emptyMap()))
        assertEquals(emptyMap(), roundedPercentSplit(mapOf(AssetClass.EQUITY to Money.ZERO)))
    }
}
