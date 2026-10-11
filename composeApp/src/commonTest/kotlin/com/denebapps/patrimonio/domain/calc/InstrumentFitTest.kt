package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
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
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstrumentFitTest {
    private val today = LocalDate(2026, 10, 11)
    private val firstFriday = LocalDate(2025, 10, 10)
    private val rates = FxRates(emptyMap())

    private fun pt(date: LocalDate, price: Double) = PricePoint(date, Price.fromDouble(price, Currency.EUR))

    /** Weekly returns following a fixed non-constant pattern of period 5; [sign] flips them. */
    private fun pattern(count: Int, sign: Double = 1.0) =
        List(count) { sign * listOf(0.02, -0.01, 0.03, -0.02, 0.01)[it % 5] }

    /** Another fixed pattern, of period 7, that is nearly uncorrelated with [pattern]. */
    private fun otherPattern(count: Int) = List(count) { listOf(0.02, -0.03, 0.01, 0.03, -0.02, 0.0, -0.01)[it % 7] }

    private fun weekly(first: LocalDate, startPrice: Double, returns: List<Double>): List<PricePoint> {
        var price = startPrice
        var date = first
        val points = mutableListOf(pt(date, price))
        for (r in returns) {
            price *= 1.0 + r
            date = date.plus(7, DateTimeUnit.DAY)
            points += pt(date, price)
        }
        return points
    }

    private fun eur(whole: Long) = Price(whole * 1_000_000L, Currency.EUR)

    private fun quoted(
        id: String,
        wholeUnits: Long = 10,
        price: Price? = eur(100),
        fundamentals: InstrumentFundamentals? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P000$id"),
            currency = Currency.EUR,
            units = Units(wholeUnits * UNITS_SCALE),
            lastQuote = price?.let { LastQuote(it, LocalDate(2026, 10, 9), 1L) },
        ),
        fundamentals = fundamentals,
    )

    private fun manual(id: String, wholeEur: Long, fundamentals: InstrumentFundamentals? = null) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(wholeEur * 100), Currency.EUR), 1L),
        fundamentals = fundamentals,
    )

    private fun preview(
        series: List<PricePoint>,
        fundamentals: InstrumentFundamentals? = null,
        name: String = "Candidate",
    ) = InstrumentPreviewData(
        candidate = InstrumentCandidate(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P000CAND"),
            name = name,
            isin = null,
            ticker = null,
            type = InstrumentType.ETF,
            currency = Currency.EUR,
            exchange = null,
            fundamentals = fundamentals,
        ),
        currency = Currency.EUR,
        history = series,
        eurHistory = series,
        fundamentals = fundamentals,
    )

    private val longSeries = weekly(firstFriday, 100.0, pattern(30))

    private fun fit(
        candidate: InstrumentPreviewData,
        holdings: List<PortfolioHolding>,
        series: Map<String, List<PricePoint>>,
        amount: Money? = null,
    ) = instrumentFit(candidate, holdings, series, rates, today, amount)

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 1e-6) {
        assertNotNull(actual)
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ---- verdict thresholds -------------------------------------------------------------------------------------

    @Test
    fun `the verdict follows the thresholds, inclusive on the lower edge of each band`() {
        assertEquals(FitVerdict.LOW_DIVERSIFICATION, fitVerdict(1.0))
        assertEquals(FitVerdict.LOW_DIVERSIFICATION, fitVerdict(0.9))
        assertEquals(FitVerdict.MODERATE, fitVerdict(0.8999))
        assertEquals(FitVerdict.MODERATE, fitVerdict(0.5))
        assertEquals(FitVerdict.DIVERSIFIES, fitVerdict(0.4999))
        assertEquals(FitVerdict.DIVERSIFIES, fitVerdict(-1.0))
    }

    // ---- correlation and verdict --------------------------------------------------------------------------------

    @Test
    fun `an identical series correlates 1 with the holding and the portfolio and diversifies little`() {
        val holding = quoted("h1")
        val result = fit(preview(longSeries), listOf(holding), mapOf("h1" to longSeries))

        val similarity = result.holdings.single()
        assertEquals(holding, similarity.holding)
        assertClose(1.0, similarity.correlation.correlation)
        assertEquals(30, similarity.correlation.weeks)
        assertClose(1.0, result.portfolio?.correlation)
        assertEquals(30, result.portfolio?.weeks)
        assertEquals(FitVerdict.LOW_DIVERSIFICATION, result.verdict)
        assertNull(result.projection)
    }

    @Test
    fun `an anti correlated series diversifies`() {
        val opposite = weekly(firstFriday, 100.0, pattern(30, sign = -1.0))
        val result = fit(preview(opposite), listOf(quoted("h1")), mapOf("h1" to longSeries))

        assertClose(-1.0, result.holdings.single().correlation.correlation)
        assertClose(-1.0, result.portfolio?.correlation)
        assertEquals(FitVerdict.DIVERSIFIES, result.verdict)
    }

    @Test
    fun `a partly related series is moderately diversifying`() {
        val mixed = weekly(firstFriday, 100.0, pattern(30).zip(otherPattern(30)) { a, b -> a + b })
        val result = fit(preview(mixed), listOf(quoted("h1")), mapOf("h1" to longSeries))

        val correlation = assertNotNull(result.portfolio?.correlation)
        assertTrue(correlation >= 0.5 && correlation < 0.9, "expected a moderate correlation but was $correlation")
        assertEquals(FitVerdict.MODERATE, result.verdict)
    }

    @Test
    fun `a candidate with too short a history has no correlations, no verdict, but reports its weeks`() {
        val short = weekly(firstFriday.plus(7 * 21, DateTimeUnit.DAY), 80.0, pattern(9))
        val result = fit(preview(short), listOf(quoted("h1")), mapOf("h1" to longSeries))

        val similarity = result.holdings.single().correlation
        assertNull(similarity.correlation)
        assertEquals(9, similarity.weeks)
        assertNull(result.portfolio?.correlation)
        assertEquals(9, result.portfolio?.weeks)
        assertNull(result.verdict)
    }

    @Test
    fun `an empty candidate history gives no verdict and does not throw`() {
        val result = fit(preview(emptyList()), listOf(quoted("h1")), mapOf("h1" to longSeries))

        assertNull(result.holdings.single().correlation.correlation)
        assertNull(result.verdict)
    }

    @Test
    fun `holdings are ordered by correlation, highest first, undefined ones last`() {
        val candidate = preview(longSeries)
        val mixed = weekly(firstFriday, 100.0, pattern(30).zip(otherPattern(30)) { a, b -> a + b })
        val opposite = weekly(firstFriday, 100.0, pattern(30, sign = -1.0))
        val short = weekly(firstFriday.plus(7 * 21, DateTimeUnit.DAY), 80.0, pattern(9))
        val holdings = listOf(quoted("short"), quoted("opposite"), quoted("same"), quoted("mixed"))
        val series = mapOf("short" to short, "opposite" to opposite, "same" to longSeries, "mixed" to mixed)

        val result = fit(candidate, holdings, series)

        assertEquals(listOf("same", "mixed", "opposite", "short"), result.holdings.map { it.holding.id })
        assertNull(result.holdings.last().correlation.correlation)
    }

    @Test
    fun `without a portfolio correlation the verdict falls back to the highest holding correlation`() {
        // The short holding limits the constant-composition series to 9 weeks, too few for a correlation.
        val short = weekly(firstFriday.plus(7 * 21, DateTimeUnit.DAY), 80.0, pattern(9))
        val holdings = listOf(quoted("long"), quoted("short"))
        val result = fit(preview(longSeries), holdings, mapOf("long" to longSeries, "short" to short))

        assertNull(result.portfolio?.correlation)
        assertEquals("long", result.holdings.first().holding.id)
        assertEquals(FitVerdict.LOW_DIVERSIFICATION, result.verdict)
    }

    @Test
    fun `the portfolio correlation wins over the holdings when it is defined`() {
        val opposite = weekly(firstFriday, 100.0, pattern(30, sign = -1.0))
        // Both holdings weigh the same, so the composition moves with neither pattern strongly; the verdict must come
        // from the portfolio cell whatever the best holding says.
        val holdings = listOf(quoted("same"), quoted("opposite"))
        val result = fit(preview(longSeries), holdings, mapOf("same" to longSeries, "opposite" to opposite))

        val portfolio = assertNotNull(result.portfolio?.correlation)
        assertEquals(fitVerdict(portfolio), result.verdict)
    }

    @Test
    fun `manual holdings and holdings without history take no part in the correlations`() {
        val holdings = listOf(manual("cash", 5_000), quoted("fresh"), quoted("h1"))
        val series = mapOf("fresh" to emptyList(), "h1" to longSeries)
        val result = fit(preview(longSeries), holdings, series)

        assertEquals(listOf("h1"), result.holdings.map { it.holding.id })
        assertClose(1.0, result.portfolio?.correlation)
    }

    @Test
    fun `without a quoted holding with history there is nothing to compare with`() {
        val holdings = listOf(manual("cash", 5_000), quoted("fresh"))
        val result = fit(preview(longSeries), holdings, mapOf("fresh" to emptyList()))

        assertEquals(emptyList(), result.holdings)
        assertNull(result.portfolio)
        assertNull(result.verdict)
    }

    // ---- projection ---------------------------------------------------------------------------------------------

    private val holdingFundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 2_000L, riskLevel = 4)

    /** `h1` worth 1,000 EUR with fundamentals and a manual holding worth 1,000 EUR without any. */
    private val twoHoldings = listOf(quoted("h1", fundamentals = holdingFundamentals), manual("cash", 1_000))
    private val twoSeries = mapOf("h1" to longSeries)

    @Test
    fun `no amount, or an amount of zero or less, projects nothing`() {
        val candidate = preview(longSeries)
        assertNull(fit(candidate, twoHoldings, twoSeries, amount = null).projection)
        assertNull(fit(candidate, twoHoldings, twoSeries, amount = Money.ZERO).projection)
        assertNull(fit(candidate, twoHoldings, twoSeries, amount = Money(-100)).projection)
    }

    @Test
    fun `the projection gives the new weight, the TER and the risk after adding the amount`() {
        val candidate = preview(longSeries, InstrumentFundamentals(ongoingChargePercentScaled = 1_000L, riskLevel = 6))
        val projection = assertNotNull(fit(candidate, twoHoldings, twoSeries, Money(100_000)).projection)

        assertEquals(Money(100_000), projection.amount)
        assertEquals(Money(200_000), projection.portfolioTotal)
        assertClose(1.0 / 3.0, projection.newWeight)
        // Before: only h1 has a TER (0.20 %), over half of the value.
        assertEquals(2_000L, projection.costBefore.averageTerPercentScaled)
        assertClose(0.5, projection.costBefore.coverage)
        // After: h1 (0.20 %) and the candidate (0.10 %) with 1,000 EUR each average 0.15 %, covering two thirds.
        assertEquals(1_500L, projection.costAfter.averageTerPercentScaled)
        assertEquals(2, projection.costAfter.coveredHoldings)
        assertClose(2.0 / 3.0, projection.costAfter.coverage)
        assertEquals(Money(300), projection.costAfter.yearlyCost)
        assertEquals(4.0, projection.riskBefore.averageRiskLevel)
        assertEquals(5.0, projection.riskAfter.averageRiskLevel)
        assertEquals(2, projection.riskAfter.coveredHoldings)
        assertNull(projection.concentrationWarning)
    }

    @Test
    fun `a candidate without fundamentals leaves the averages and lowers the coverage`() {
        val projection = assertNotNull(fit(preview(longSeries), twoHoldings, twoSeries, Money(100_000)).projection)

        assertEquals(projection.costBefore.averageTerPercentScaled, projection.costAfter.averageTerPercentScaled)
        assertEquals(1, projection.costAfter.coveredHoldings)
        assertClose(1.0 / 3.0, projection.costAfter.coverage)
        assertEquals(projection.riskBefore.averageRiskLevel, projection.riskAfter.averageRiskLevel)
        assertClose(1.0 / 3.0, projection.riskAfter.coverage)
    }

    @Test
    fun `the concentration warning fires for a candidate that would weigh more than 40 percent`() {
        val projection = assertNotNull(
            fit(preview(longSeries, name = "Big fund"), twoHoldings, twoSeries, Money(300_000)).projection,
        )

        val warning = assertNotNull(projection.concentrationWarning)
        assertTrue(warning.isCandidate)
        assertEquals("Big fund", warning.name)
        assertClose(0.6, warning.weight)
    }

    @Test
    fun `the concentration warning fires for an existing holding that already is above 40 percent`() {
        val holdings = listOf(quoted("big", wholeUnits = 90), manual("cash", 1_000))
        val projection = assertNotNull(
            fit(preview(longSeries), holdings, mapOf("big" to longSeries), Money(50_000)).projection,
        )

        val warning = assertNotNull(projection.concentrationWarning)
        assertFalse(warning.isCandidate)
        assertEquals("Holding big", warning.name)
        assertClose(9_000.0 / 10_500.0, warning.weight)
    }

    @Test
    fun `exactly 40 percent is not a concentration`() {
        val holdings = listOf(manual("a", 300), manual("b", 300))
        val projection = assertNotNull(fit(preview(longSeries), holdings, emptyMap(), Money(40_000)).projection)

        assertClose(0.4, projection.newWeight)
        assertNull(projection.concentrationWarning)
    }

    @Test
    fun `the first product of an empty portfolio is all of it but no warning`() {
        val projection = assertNotNull(fit(preview(longSeries), emptyList(), emptyMap(), Money(50_000)).projection)

        assertEquals(Money.ZERO, projection.portfolioTotal)
        assertClose(1.0, projection.newWeight)
        assertNull(projection.concentrationWarning)
    }

    @Test
    fun `an amount too big to add up gives no projection instead of throwing`() {
        val result = fit(preview(longSeries), twoHoldings, twoSeries, Money(Long.MAX_VALUE))

        assertNull(result.projection)
        assertNotNull(result.portfolio)
    }
}
