package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentFundamentalsTest {
    @Test
    fun `holds a cost in fixed point percent a risk level and trimmed texts`() {
        val fundamentals = InstrumentFundamentals(
            ongoingChargePercentScaled = 1_800L,
            riskLevel = 6,
            category = "RV Global",
            managementCompany = "Vanguard",
        )

        assertEquals(1_800L, fundamentals.ongoingChargePercentScaled)
        assertEquals(18 * PERCENT_SCALE / 100, fundamentals.ongoingChargePercentScaled)
        assertFalse(fundamentals.isEmpty)
    }

    @Test
    fun `nothing at all is empty`() {
        assertTrue(InstrumentFundamentals().isEmpty)
        assertFalse(InstrumentFundamentals(riskLevel = 1).isEmpty)
    }

    @Test
    fun `a cost may be zero but never negative`() {
        assertEquals(0L, InstrumentFundamentals(ongoingChargePercentScaled = 0L).ongoingChargePercentScaled)
        assertFailsWith<IllegalArgumentException> { InstrumentFundamentals(ongoingChargePercentScaled = -1L) }
    }

    @Test
    fun `the risk level is one of the seven SRRI classes`() {
        (1..7).forEach { assertEquals(it, InstrumentFundamentals(riskLevel = it).riskLevel) }
        assertFailsWith<IllegalArgumentException> { InstrumentFundamentals(riskLevel = 0) }
        assertFailsWith<IllegalArgumentException> { InstrumentFundamentals(riskLevel = 8) }
    }

    @Test
    fun `texts must be trimmed present and within the cap`() {
        listOf("", "   ", " padded", "padded ", "X".repeat(MAX_FUNDAMENTALS_TEXT_LENGTH + 1)).forEach { text ->
            assertFailsWith<IllegalArgumentException> { InstrumentFundamentals(category = text) }
            assertFailsWith<IllegalArgumentException> { InstrumentFundamentals(managementCompany = text) }
        }
        val atCap = "X".repeat(MAX_FUNDAMENTALS_TEXT_LENGTH)
        assertEquals(atCap, InstrumentFundamentals(category = atCap).category)
    }

    @Test
    fun `normalizing text trims blanks to null and caps the length`() {
        assertNull(normalizeFundamentalsText(null))
        assertNull(normalizeFundamentalsText("   "))
        assertEquals("RV Global", normalizeFundamentalsText("  RV Global "))
        assertEquals(MAX_FUNDAMENTALS_TEXT_LENGTH, normalizeFundamentalsText("X".repeat(500))?.length)
    }

    @Test
    fun `a holding and a candidate carry their fundamentals and default to none`() {
        val fundamentals = InstrumentFundamentals(riskLevel = 5)
        val holding = PortfolioHolding(
            id = "h",
            name = "Fondo",
            type = InstrumentType.FUND,
            isin = null,
            ticker = null,
            valuation = HoldingValuation.Manual(CurrencyAmount(Money(100), Currency.EUR), 0L),
        )

        assertNull(holding.fundamentals)
        assertEquals(fundamentals, holding.copy(fundamentals = fundamentals).fundamentals)
    }
}
