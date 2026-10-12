package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A position that sold everything it bought stays listed at zero units: it is worth nothing and breaks nothing. */
class ClosedPositionTest {
    private val price = Price(10_000_000, Currency.EUR)

    private fun held(id: String, units: UnitsBalance, lastQuote: LastQuote? = LastQuote(price, DAY, 1L)) =
        PortfolioHolding(
            id = id,
            portfolioId = TEST_PORTFOLIO_ID,
            name = "Fund $id",
            type = InstrumentType.FUND,
            isin = null,
            ticker = null,
            valuation = HoldingValuation.Quoted(
                ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
                currency = Currency.EUR,
                units = units,
                lastQuote = lastQuote,
            ),
        )

    @Test
    fun `a closed position is worth zero with or without a price`() {
        assertEquals(CurrencyAmount(Money.ZERO, Currency.EUR), held("c", UnitsBalance.ZERO).value())
        assertEquals(CurrencyAmount(Money.ZERO, Currency.EUR), held("c", UnitsBalance.ZERO, lastQuote = null).value())
    }

    @Test
    fun `an open position without a price still has no value yet`() {
        assertNull(held("o", UnitsBalance(UNITS_SCALE), lastQuote = null).value())
    }

    @Test
    fun `a closed position adds nothing to the portfolio total`() {
        val open = held("o", UnitsBalance(2 * UNITS_SCALE))
        val closed = held("c", UnitsBalance.ZERO)

        assertEquals(Money(2_000), portfolioTotalEur(listOf(open, closed), FxRates(emptyMap())))
    }

    @Test
    fun `a closed position is not part of the constant composition history`() {
        val open = held("o", UnitsBalance(2 * UNITS_SCALE))
        val closed = held("c", UnitsBalance.ZERO)
        val points = listOf(PricePoint(DAY, price))

        val result = constantCompositionSeries(listOf(open, closed), mapOf("o" to points, "c" to points))

        assertEquals(listOf("o"), result.includedHoldingIds)
        assertEquals(emptyList(), result.excludedHoldingIds)
        assertEquals(listOf(Money(2_000)), result.values.map { it.value })
    }

    @Test
    fun `units given as Units build the same valuation as the balance`() {
        val ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS")

        val fromUnits = HoldingValuation.Quoted(ref, Currency.EUR, Units(5 * UNITS_SCALE))
        val fromBalance = HoldingValuation.Quoted(ref, Currency.EUR, UnitsBalance(5 * UNITS_SCALE))

        assertEquals(fromBalance, fromUnits)
        assertFalse(fromUnits.isClosed)
        assertTrue(HoldingValuation.Quoted(ref, Currency.EUR, UnitsBalance.ZERO).isClosed)
        assertNotNull(fromUnits.units.toUnits())
    }

    @Test
    fun `a balance is shown like units and a zero balance as zero`() {
        assertEquals("12,50", formatUnits(UnitsBalance(12_500_000)))
        assertEquals("0,00", formatUnits(UnitsBalance.ZERO))
        assertEquals("12,5", unitsInputText(UnitsBalance(12_500_000)))
        assertEquals("0", unitsInputText(UnitsBalance.ZERO))
    }

    private companion object {
        val DAY = LocalDate(2026, 10, 9)
    }
}
