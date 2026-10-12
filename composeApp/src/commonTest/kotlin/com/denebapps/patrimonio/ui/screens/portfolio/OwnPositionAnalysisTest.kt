package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.FUND_B
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.transferIn
import com.denebapps.patrimonio.domain.model.transferOut
import com.denebapps.patrimonio.domain.model.units
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.datetime.LocalDate
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Turning a holding, its movements and its price history into the "Mi posición" card and the chart series.
class OwnPositionAnalysisTest {
    private val today = date(2026, 1, 1)
    private val noRates = FxRates(emptyMap())

    private fun fund(id: String, unitsHeld: Long, lastEuros: Long?) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo $id",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = Currency.EUR,
            units = UnitsBalance(unitsHeld * UNITS_SCALE),
            lastQuote = lastEuros?.let { LastQuote(Price(it * 1_000_000, Currency.EUR), today, 1L) },
        ),
    )

    private fun inputs(
        holding: PortfolioHolding,
        movements: List<Movement>,
        history: List<PricePoint> = emptyList(),
        fx: FxHistory = FxHistory(emptyMap()),
        rates: FxRates = noRates,
    ) = OwnPositionInputs(holding, movements, history, fx, rates)

    private fun eur(day: LocalDate, euros: Long) = PricePoint(day, Price(euros * 1_000_000, Currency.EUR))

    // ---- visibility --------------------------------------------------------------------------------------------

    @Test
    fun `a position without movements has no card`() {
        val other = listOf(buy("b", date(2025, 1, 1), units(1), 1_000, holding = FUND_B))

        assertNull(analyzeOwnPosition(inputs(fund(FUND_A, 10, 100), other), today))
        assertNull(analyzeOwnPosition(inputs(fund(FUND_A, 10, 100), emptyList()), today))
    }

    @Test
    fun `a manual holding has no card`() {
        val manual = PortfolioHolding(
            id = FUND_A,
            portfolioId = TEST_PORTFOLIO_ID,
            name = "Cuadro",
            type = InstrumentType.OTHER,
            isin = null,
            ticker = null,
            valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), 1L),
        )

        assertNull(analyzeOwnPosition(inputs(manual, listOf(buy("b", date(2025, 1, 1), units(1), 1_000))), today))
    }

    // ---- the value of my units over time -----------------------------------------------------------------------

    @Test
    fun `the series values the units held on each price day, from the first movement`() {
        val movements = listOf(
            buy("b1", date(2025, 3, 3), units(10), 100_000),
            buy("b2", date(2025, 3, 5), units(5), 55_000),
        )
        val history = listOf(
            eur(date(2025, 3, 1), 9),
            eur(date(2025, 3, 3), 10),
            eur(date(2025, 3, 4), 11),
            eur(date(2025, 3, 5), 12),
        )

        val analysis = assertNotNull(analyzeOwnPosition(inputs(fund(FUND_A, 15, 12), movements, history), today))

        // 03-03: 10 x 10 = 100,00 EUR; 03-04: 10 x 11 = 110,00 EUR; 03-05: 15 x 12 = 180,00 EUR. Price scale: 1e6.
        assertEquals(
            listOf(
                PricePoint(date(2025, 3, 3), Price(100_000_000, Currency.EUR)),
                PricePoint(date(2025, 3, 4), Price(110_000_000, Currency.EUR)),
                PricePoint(date(2025, 3, 5), Price(180_000_000, Currency.EUR)),
            ),
            analysis.series,
        )
    }

    @Test
    fun `the series of a position in another currency is converted with the rate of each day`() {
        val usdFund = PortfolioHolding(
            id = FUND_A,
            portfolioId = TEST_PORTFOLIO_ID,
            name = "Fondo USD",
            type = InstrumentType.FUND,
            isin = null,
            ticker = null,
            valuation = HoldingValuation.Quoted(
                ref = InstrumentRef(QuoteProvider.YAHOO, "USDF"),
                currency = Currency.USD,
                units = UnitsBalance(units(10).scaled),
                lastQuote = LastQuote(Price(12_000_000, Currency.USD), date(2026, 1, 6), 1L),
            ),
        )
        val bought = Movement(
            id = "b",
            holdingId = FUND_A,
            type = MovementType.BUY,
            date = date(2026, 1, 5),
            units = units(10),
            amount = CurrencyAmount(Money(10_000), Currency.USD),
            amountEur = Money(9_000),
        )
        val usd = { day: LocalDate, dollars: Long -> PricePoint(day, Price(dollars * 1_000_000, Currency.USD)) }
        val history = listOf(usd(date(2026, 1, 2), 10), usd(date(2026, 1, 5), 11), usd(date(2026, 1, 6), 12))
        val fx = FxHistory(
            mapOf(
                Currency.USD to listOf(
                    DatedFxRate(date(2026, 1, 2), 900_000),
                    DatedFxRate(date(2026, 1, 5), 900_000),
                    DatedFxRate(date(2026, 1, 6), 800_000),
                ),
            ),
        )

        val analysis = assertNotNull(
            analyzeOwnPosition(
                inputs(usdFund, listOf(bought), history, fx, FxRates(mapOf(Currency.USD to 800_000))),
                date(2026, 1, 6),
            ),
        )

        // 01-05: 10 x 11 $ x 0,90 = 99,00 EUR; 01-06: 10 x 12 $ x 0,80 = 96,00 EUR.
        assertEquals(
            listOf(
                PricePoint(date(2026, 1, 5), Price(99_000_000, Currency.EUR)),
                PricePoint(date(2026, 1, 6), Price(96_000_000, Currency.EUR)),
            ),
            analysis.series,
        )
        assertEquals("96,00 €", analysis.card.figures.first { it.label == "Valor actual" }.valueText)
    }

    @Test
    fun `a closed position is worth zero on the days after it was sold out`() {
        val movements = listOf(
            buy("b", date(2025, 3, 3), units(10), 100_000),
            Movement(
                id = "s",
                holdingId = FUND_A,
                type = MovementType.SELL,
                date = date(2025, 3, 4),
                units = units(10),
                amount = CurrencyAmount(Money(110_000), Currency.EUR),
                amountEur = Money(110_000),
            ),
        )
        val history = listOf(eur(date(2025, 3, 3), 10), eur(date(2025, 3, 4), 11), eur(date(2025, 3, 5), 12))

        val analysis = assertNotNull(analyzeOwnPosition(inputs(fund(FUND_A, 0, 12), movements, history), today))

        assertEquals(listOf(100_000_000L, 0L, 0L), analysis.series.map { it.price.scaled })
    }

    // ---- the market value of a transfer ------------------------------------------------------------------------

    private val transfer = listOf(
        buy("b", date(2025, 1, 1), units(10), 100_000),
        transferOut("o", date(2025, 7, 1), units(10), "t"),
        transferIn("i", date(2025, 7, 1), units(10), "t"),
    )

    private fun annualReturn(of: Double): String = formatPercent(of.pow(365.0 / 184.0) - 1, signed = true)

    @Test
    fun `a transfer in is valued at the price of its day in the annual return`() {
        // 10 units were worth 1200,00 EUR on 2025-07-01 (120 each) and 1300,00 EUR today.
        val history = listOf(eur(date(2025, 7, 1), 120), eur(date(2026, 1, 1), 130))

        val analysis = assertNotNull(analyzeOwnPosition(inputs(fund(FUND_B, 10, 130), transfer, history), today))

        assertEquals(annualReturn(1300.0 / 1200.0), analysis.card.figures.last().valueText)
    }

    @Test
    fun `a transfer with no price near its day falls back on the cost it carried`() {
        // The history only starts a month after the transfer: the cost carried by the lots (1000,00 EUR) is used.
        val history = listOf(eur(date(2025, 8, 5), 125), eur(date(2026, 1, 1), 130))

        val analysis = assertNotNull(analyzeOwnPosition(inputs(fund(FUND_B, 10, 130), transfer, history), today))

        assertEquals(annualReturn(1300.0 / 1000.0), analysis.card.figures.last().valueText)
    }
}
