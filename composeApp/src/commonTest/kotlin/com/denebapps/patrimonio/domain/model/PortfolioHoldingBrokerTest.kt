package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PortfolioHoldingBrokerTest {
    private fun holding(broker: String?) = PortfolioHolding(
        id = "p1",
        name = "Vanguard Global",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(100), Currency.EUR), valuedAtEpochMs = 1L),
        broker = broker,
    )

    @Test
    fun `a holding has no broker unless one is given`() {
        assertNull(holding(null).broker)
    }

    @Test
    fun `normalizing trims the text and keeps the casing the user typed`() {
        assertEquals("MyInvestor", normalizeBroker("  MyInvestor \n"))
        assertEquals("myInvestor", normalizeBroker("myInvestor"))
    }

    @Test
    fun `normalizing a missing or blank broker gives none`() {
        assertNull(normalizeBroker(null))
        assertNull(normalizeBroker(""))
        assertNull(normalizeBroker("   "))
    }

    @Test
    fun `a broker may be as long as the cap but not longer`() {
        val atCap = "B".repeat(MAX_BROKER_LENGTH)

        assertEquals(atCap, normalizeBroker(atCap))
        assertFailsWith<IllegalArgumentException> { normalizeBroker(atCap + "B") }
    }

    @Test
    fun `the cap applies to the trimmed text`() {
        val atCap = "B".repeat(MAX_BROKER_LENGTH)

        assertEquals(atCap, normalizeBroker("  $atCap  "))
    }

    @Test
    fun `a holding only accepts a normalized broker`() {
        assertEquals("Openbank", holding("Openbank").broker)
        assertFailsWith<IllegalArgumentException> { holding("") }
        assertFailsWith<IllegalArgumentException> { holding("   ") }
        assertFailsWith<IllegalArgumentException> { holding(" Openbank") }
        assertFailsWith<IllegalArgumentException> { holding("B".repeat(MAX_BROKER_LENGTH + 1)) }
    }
}
