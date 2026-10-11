package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortfolioAnalyticsTest {
    private val today = LocalDate(2026, 10, 11)

    private fun pt(date: LocalDate, price: Double, currency: Currency = Currency.EUR) =
        PricePoint(date, Price.fromDouble(price, currency))

    private fun pt(month: Int, day: Int, price: Double) = pt(LocalDate(2026, month, day), price)

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 1e-6) {
        assertNotNull(actual)
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    private fun eurCents(whole: Long) = Money(whole * 100)

    // ---- EUR conversion -------------------------------------------------------------------------------------

    @Test
    fun `a series converts to EUR with the rate of each day and drops days without one`() {
        val fx = FxHistory(
            mapOf(
                Currency.USD to listOf(
                    DatedFxRate(LocalDate(2026, 10, 5), 900_000L),
                    DatedFxRate(LocalDate(2026, 10, 6), 800_000L),
                ),
            ),
        )
        val usd = listOf(
            // no rate within 7 days before: dropped
            pt(LocalDate(2026, 9, 20), 70.0, Currency.USD),
            // 100 * 0.9 = 90
            pt(LocalDate(2026, 10, 5), 100.0, Currency.USD),
            // falls back to the 10-06 rate: 50 * 0.8 = 40
            pt(LocalDate(2026, 10, 7), 50.0, Currency.USD),
        )
        assertEquals(listOf(pt(10, 5, 90.0), pt(10, 7, 40.0)), toEurSeries(usd, fx))
        // GBP has no history at all: every point is dropped.
        assertEquals(emptyList(), toEurSeries(listOf(pt(LocalDate(2026, 10, 5), 10.0, Currency.GBP)), fx))
    }

    @Test
    fun `an EUR series is returned as it is`() {
        val series = listOf(pt(10, 5, 12.5), pt(10, 6, 12.75))
        assertEquals(series, toEurSeries(series, FxHistory(emptyMap())))
    }

    // ---- correlation --------------------------------------------------------------------------------------------

    private val firstFriday = LocalDate(2025, 10, 10)

    /** [count] weekly returns following a fixed non-constant pattern; [sign] flips them for an opposite series. */
    private fun pattern(count: Int, sign: Double = 1.0) =
        List(count) { sign * listOf(0.02, -0.01, 0.03, -0.02, 0.01)[it % 5] }

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

    @Test
    fun `correlation matrix has a unit diagonal, is symmetric and reflects perfect and inverse relations`() {
        val a = HoldingPriceSeries("a", weekly(firstFriday, 1000.0, pattern(30)))
        val b = HoldingPriceSeries("b", weekly(firstFriday, 50.0, pattern(30)))
        val c = HoldingPriceSeries("c", weekly(firstFriday, 200.0, pattern(30, sign = -1.0)))
        val matrix = correlationMatrix(listOf(a, b, c), today)

        assertEquals(listOf("a", "b", "c"), matrix.holdingIds)
        for (i in 0..2) assertEquals(1.0, matrix[i, i].correlation)
        // b has the same weekly returns as a (correlation 1); c has exactly the opposite ones (-1).
        assertClose(1.0, matrix[0, 1].correlation)
        assertClose(-1.0, matrix[0, 2].correlation)
        assertClose(-1.0, matrix[1, 2].correlation)
        for (i in 0..2) for (j in 0..2) assertEquals(matrix[i, j], matrix[j, i])
        // 31 points -> 30 weekly returns over the common weeks, all within the last three years.
        assertEquals(30, matrix[0, 1].weeks)
        assertEquals(30, matrix[0, 0].weeks)
    }

    @Test
    fun `a pair with fewer than twenty six common weeks has no correlation but reports its weeks`() {
        val long = HoldingPriceSeries("long", weekly(firstFriday, 1000.0, pattern(30)))
        // The last 10 points of the same weeks: 9 weekly returns in common.
        val shortStart = firstFriday.plus(7 * 21, DateTimeUnit.DAY)
        val short = HoldingPriceSeries("short", weekly(shortStart, 80.0, pattern(9)))
        val matrix = correlationMatrix(listOf(long, short), today)
        assertNull(matrix[0, 1].correlation)
        assertEquals(9, matrix[0, 1].weeks)
        assertNull(matrix[1, 0].correlation)
        assertEquals(1.0, matrix[1, 1].correlation)
    }

    @Test
    fun `a constant series has no defined correlation`() {
        val a = HoldingPriceSeries("a", weekly(firstFriday, 1000.0, pattern(30)))
        val flat = HoldingPriceSeries("flat", weekly(firstFriday, 10.0, List(30) { 0.0 }))
        assertNull(correlationMatrix(listOf(a, flat), today)[0, 1].correlation)
    }

    @Test
    fun `correlation uses the last three years when enough common weeks exist and all of them otherwise`() {
        // 40 common weeks end in 2020: no recent week, so the fallback uses all of them.
        val oldStart = LocalDate(2019, 3, 1)
        val a = HoldingPriceSeries("a", weekly(oldStart, 100.0, pattern(40)))
        val b = HoldingPriceSeries("b", weekly(oldStart, 20.0, pattern(40)))
        val matrix = correlationMatrix(listOf(a, b), today)
        assertClose(1.0, matrix[0, 1].correlation)
        assertEquals(40, matrix[0, 1].weeks)
    }

    @Test
    fun `an empty input gives an empty matrix`() {
        val matrix = correlationMatrix(emptyList(), today)
        assertEquals(emptyList(), matrix.holdingIds)
        assertEquals(emptyList(), matrix.cells)
    }

    // ---- holdings fixtures ------------------------------------------------------------------------------------

    private fun fundamentals(terScaled: Long? = null, risk: Int? = null) =
        InstrumentFundamentals(ongoingChargePercentScaled = terScaled, riskLevel = risk)

    private fun quoted(
        id: String,
        wholeUnits: Long,
        price: Price?,
        type: InstrumentType = InstrumentType.FUND,
        broker: String? = null,
        fundamentals: InstrumentFundamentals? = null,
        currency: Currency = price?.currency ?: Currency.EUR,
        assetClass: AssetClass? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P000$id"),
            currency = currency,
            units = Units(wholeUnits * UNITS_SCALE),
            lastQuote = price?.let { LastQuote(it, LocalDate(2026, 10, 9), 1L) },
        ),
        broker = broker,
        fundamentals = fundamentals,
        assetClass = assetClass,
    )

    private fun manual(
        id: String,
        wholeEur: Long,
        broker: String? = null,
        fundamentals: InstrumentFundamentals? = null,
        assetClass: AssetClass? = null,
    ) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(eurCents(wholeEur), Currency.EUR), 1L),
        broker = broker,
        fundamentals = fundamentals,
        assetClass = assetClass,
    )

    private fun eur(whole: Long) = Price(whole * 1_000_000L, Currency.EUR)

    private fun usd(whole: Long) = Price(whole * 1_000_000L, Currency.USD)

    private val rates = FxRates(mapOf(Currency.USD to 900_000L))

    // ---- constant-composition value series ----------------------------------------------------------------

    @Test
    fun `constant composition multiplies units by EUR prices from the first day every holding has one`() {
        val h1 = quoted("h1", 2, eur(100))
        val h2 = quoted("h2", 10, eur(10))
        val series = mapOf(
            "h1" to listOf(
                pt(10, 1, 100.0),
                pt(10, 2, 110.0),
                pt(10, 11, 121.0),
                pt(10, 12, 122.0),
                pt(10, 20, 120.0),
            ),
            "h2" to listOf(pt(10, 2, 10.0), pt(10, 3, 11.0), pt(10, 4, 12.0), pt(10, 25, 13.0)),
        )
        val result = constantCompositionSeries(listOf(h1, h2), series)

        assertEquals(listOf("h1", "h2"), result.includedHoldingIds)
        assertEquals(emptyList(), result.excludedHoldingIds)
        val values = result.values.associate { it.date to it.value }
        // 10-01: h2 has no price yet -> skipped.
        // 10-02: 2 * 110 + 10 * 10 = 320; 10-03: h1 forward-filled (110): 220 + 10 * 11 = 330;
        // 10-04: 220 + 10 * 12 = 340; 10-11: h2 forward-filled 7 days (12): 2 * 121 + 120 = 362;
        // 10-12: h2 is 8 days old -> skipped; 10-20: h2 is 16 days old -> skipped;
        // 10-25: h1 forward-filled 5 days (120): 240 + 10 * 13 = 370.
        assertEquals(
            mapOf(
                LocalDate(2026, 10, 2) to eurCents(320),
                LocalDate(2026, 10, 3) to eurCents(330),
                LocalDate(2026, 10, 4) to eurCents(340),
                LocalDate(2026, 10, 11) to eurCents(362),
                LocalDate(2026, 10, 25) to eurCents(370),
            ),
            values,
        )
        assertEquals(result.values.map { it.date }.sorted(), result.values.map { it.date })
    }

    @Test
    fun `constant composition excludes manual holdings and holdings without a series and says so`() {
        val quotedWithSeries = quoted("q", 1, eur(5))
        val noSeries = quoted("n", 1, eur(5))
        val manual = manual("m", 100)
        val result = constantCompositionSeries(
            listOf(manual, quotedWithSeries, noSeries),
            mapOf("q" to listOf(pt(10, 1, 5.0), pt(10, 2, 6.0))),
        )
        assertEquals(listOf("q"), result.includedHoldingIds)
        assertEquals(listOf("m", "n"), result.excludedHoldingIds)
        assertEquals(listOf(eurCents(5), eurCents(6)), result.values.map { it.value })
    }

    @Test
    fun `constant composition without any quoted series is empty`() {
        val result = constantCompositionSeries(listOf(manual("m", 100)), emptyMap())
        assertEquals(emptyList(), result.values)
        assertEquals(emptyList(), result.includedHoldingIds)
        assertEquals(listOf("m"), result.excludedHoldingIds)
    }

    @Test
    fun `the value series adapts to price points that the position analytics accept`() {
        val h = quoted("h", 2, eur(100))
        val series = constantCompositionSeries(
            listOf(h),
            mapOf("h" to listOf(pt(10, 1, 100.0), pt(10, 2, 150.0), pt(10, 3, 75.0))),
        )
        val points = series.toPricePoints()
        // 2 units * 100 = 200 EUR = 20_000 cents = 200.000000 as a price.
        assertEquals(listOf(pt(10, 1, 200.0), pt(10, 2, 300.0), pt(10, 3, 150.0)), points)
        assertClose(0.5, maxDrawdown(points)?.fall)
        assertClose(-0.25, rangeChange(points)?.relative)
    }

    // ---- weights and allocation ---------------------------------------------------------------------------------

    // A: 10 * 50 EUR = 500 (FUND, "Broker X"); B: 10 * 100 USD = 1000 USD = 900 EUR (ETF, no broker);
    // C: manual 600 EUR (OTHER, "Broker X"); D: quoted without a price (ignored). Total 2000 EUR.
    private val a = quoted("a", 10, eur(50), InstrumentType.FUND, broker = "Broker X")
    private val b = quoted("b", 10, usd(100), InstrumentType.ETF)
    private val c = manual("c", 600, broker = "Broker X")
    private val d = quoted("d", 5, null)

    @Test
    fun `weights are shares of the EUR total, largest first, and skip holdings without a value`() {
        val weights = holdingWeights(listOf(a, b, c, d), rates)
        assertEquals(listOf("b", "c", "a"), weights.map { it.holding.id })
        assertEquals(listOf(eurCents(900), eurCents(600), eurCents(500)), weights.map { it.valueEur })
        assertClose(0.45, weights[0].weight)
        assertClose(0.30, weights[1].weight)
        assertClose(0.25, weights[2].weight)
        assertEquals(portfolioTotalEur(listOf(a, b, c, d), rates), Money(weights.sumOf { it.valueEur.minorUnits }))
        assertEquals(emptyList(), holdingWeights(listOf(d), rates))
    }

    @Test
    fun `allocation by type lists amounts and shares in descending order`() {
        val byType = allocationByType(listOf(a, b, c, d), rates)
        assertEquals(listOf(InstrumentType.ETF, InstrumentType.OTHER, InstrumentType.FUND), byType.map { it.key })
        assertEquals(listOf(eurCents(900), eurCents(600), eurCents(500)), byType.map { it.valueEur })
        assertClose(0.45, byType[0].share)
        assertClose(0.25, byType[2].share)
    }

    @Test
    fun `allocation by broker gathers holdings without a broker under a null key`() {
        val byBroker = allocationByBroker(listOf(a, b, c, d), rates)
        // Broker X: 500 + 600 = 1100 (0.55); no broker: 900 (0.45).
        assertEquals(listOf<String?>("Broker X", null), byBroker.map { it.key })
        assertEquals(listOf(eurCents(1100), eurCents(900)), byBroker.map { it.valueEur })
        assertClose(0.55, byBroker[0].share)
        assertClose(0.45, byBroker[1].share)
    }

    @Test
    fun `allocation by currency groups by the currency the holding is valued in`() {
        val byCurrency = allocationByCurrency(listOf(a, b, c, d), rates)
        // EUR: 500 + 600 = 1100; USD: 900 EUR-equivalent.
        assertEquals(listOf(Currency.EUR, Currency.USD), byCurrency.map { it.key })
        assertEquals(listOf(eurCents(1100), eurCents(900)), byCurrency.map { it.valueEur })
        assertClose(0.55, byCurrency[0].share)
    }

    @Test
    fun `allocation by asset class uses the explicit class first and the inferred one after`() {
        val equityFund = quoted("e", 10, eur(50), fundamentals = InstrumentFundamentals(category = "RV Global"))
        val bondEtf = quoted(
            "f",
            10,
            usd(100),
            InstrumentType.ETF,
            fundamentals = InstrumentFundamentals(category = "RF Deuda Pública EUR"),
        )
        // Explicit choice beats the inferred equity class of its category.
        val relabelled = quoted(
            "g",
            10,
            eur(10),
            fundamentals = InstrumentFundamentals(category = "RV Global"),
            assetClass = AssetClass.MIXED,
        )
        val gold = manual("h", 600, assetClass = AssetClass.COMMODITIES)

        val byClass = allocationByAssetClass(listOf(equityFund, bondEtf, relabelled, gold), rates)

        // Equity 500; fixed income 900 (USD at 0.9); mixed 100; commodities 600.
        assertEquals(
            listOf<AssetClass?>(
                AssetClass.FIXED_INCOME,
                AssetClass.COMMODITIES,
                AssetClass.EQUITY,
                AssetClass.MIXED,
            ),
            byClass.map { it.key },
        )
        assertEquals(listOf(eurCents(900), eurCents(600), eurCents(500), eurCents(100)), byClass.map { it.valueEur })
        assertClose(900.0 / 2100.0, byClass[0].share)
        assertClose(600.0 / 2100.0, byClass[1].share)
        assertClose(500.0 / 2100.0, byClass[2].share)
        assertClose(100.0 / 2100.0, byClass[3].share)
        assertClose(1.0, byClass.sumOf { it.share })
    }

    @Test
    fun `allocation by asset class gathers the unclassified holdings under a null key`() {
        val classified = manual("x", 300, assetClass = AssetClass.CRYPTO)
        val byClass = allocationByAssetClass(listOf(a, b, c, d, classified), rates)

        // Unclassified: a 500 + b 900 + c 600 = 2000 (0.869...); crypto 300; d has no value.
        assertEquals(listOf<AssetClass?>(null, AssetClass.CRYPTO), byClass.map { it.key })
        assertEquals(listOf(eurCents(2000), eurCents(300)), byClass.map { it.valueEur })
        assertClose(2000.0 / 2300.0, byClass[0].share)
        assertClose(300.0 / 2300.0, byClass[1].share)
    }

    @Test
    fun `asset class shares add up to one and a stock without a category counts as equity`() {
        val stock = quoted("s", 10, eur(20), InstrumentType.STOCK)
        val byClass = allocationByAssetClass(listOf(a, b, c, stock), rates)

        assertEquals(listOf<AssetClass?>(null, AssetClass.EQUITY), byClass.map { it.key })
        assertClose(1.0, byClass.sumOf { it.share })
        assertEquals(portfolioTotalEur(listOf(a, b, c, stock), rates), Money(byClass.sumOf { it.valueEur.minorUnits }))
    }

    @Test
    fun `allocation by asset class of an empty or valueless portfolio is empty`() {
        assertEquals(emptyList(), allocationByAssetClass(emptyList(), rates))
        assertEquals(emptyList(), allocationByAssetClass(listOf(d), rates))
    }

    @Test
    fun `allocation of an empty portfolio is empty`() {
        assertEquals(emptyList(), allocationByType(emptyList(), rates))
        assertEquals(emptyList(), allocationByBroker(listOf(d), rates))
    }

    // ---- costs and risk ------------------------------------------------------------------------------------------

    @Test
    fun `costs weight the TER of the holdings that have one and report coverage and yearly cost`() {
        // A: 500 EUR at 0.20 % (2_000), B: 900 EUR at 0.50 % (5_000), C: no TER. Covered value 1400 of 2000.
        val withTer = listOf(
            quoted("a", 10, eur(50), fundamentals = fundamentals(terScaled = 2_000L)),
            quoted("b", 10, usd(100), InstrumentType.ETF, fundamentals = fundamentals(terScaled = 5_000L)),
            manual("c", 600),
        )
        val costs = costSummary(withTer, rates)
        // Average = (500 * 0.20 + 900 * 0.50) / 1400 = 550 / 1400 = 0.392857 % -> 3_929 scaled.
        assertEquals(3_929L, costs.averageTerPercentScaled)
        // Yearly: 500 * 0.002 = 1.00 EUR + 900 * 0.005 = 4.50 EUR = 5.50 EUR.
        assertEquals(Money(550), costs.yearlyCost)
        assertClose(0.7, costs.coverage)
        assertEquals(2, costs.coveredHoldings)
    }

    @Test
    fun `costs are empty when no holding has a TER`() {
        val costs = costSummary(listOf(a, c), rates)
        assertNull(costs.averageTerPercentScaled)
        assertEquals(Money.ZERO, costs.yearlyCost)
        assertEquals(0.0, costs.coverage)
        assertEquals(0, costs.coveredHoldings)
        assertEquals(0.0, costSummary(emptyList(), rates).coverage)
    }

    @Test
    fun `risk is the value weighted SRRI of the holdings that have one`() {
        val withRisk = listOf(
            quoted("a", 10, eur(50), fundamentals = fundamentals(risk = 4)),
            quoted("b", 10, usd(100), InstrumentType.ETF, fundamentals = fundamentals(risk = 6)),
            manual("c", 600),
        )
        val risk = riskSummary(withRisk, rates)
        // (500 * 4 + 900 * 6) / 1400 = 7400 / 1400 = 5.285714; coverage 1400 / 2000.
        assertClose(5.285714, risk.averageRiskLevel)
        assertClose(0.7, risk.coverage)
        assertEquals(2, risk.coveredHoldings)
        assertNull(riskSummary(listOf(a, c), rates).averageRiskLevel)
    }

    // ---- concentration -----------------------------------------------------------------------------------------------

    @Test
    fun `concentration reports the largest holding and warns above forty percent`() {
        val concentration = concentration(listOf(a, b, c, d), rates)
        assertNotNull(concentration)
        assertEquals("b", concentration.holding.id)
        assertClose(0.45, concentration.weight)
        assertTrue(concentration.exceedsThreshold)
    }

    @Test
    fun `exactly forty percent does not exceed the threshold`() {
        val even = listOf(manual("x", 400), manual("y", 300), manual("z", 300))
        val concentration = concentration(even, rates)
        assertNotNull(concentration)
        assertClose(0.4, concentration.weight)
        assertEquals(false, concentration.exceedsThreshold)
    }

    @Test
    fun `concentration is null without valued holdings and a lone holding is fully concentrated`() {
        assertNull(concentration(listOf(d), rates))
        val lone = concentration(listOf(manual("only", 10)), rates)
        assertNotNull(lone)
        assertClose(1.0, lone.weight)
        assertTrue(lone.exceedsThreshold)
    }
}
