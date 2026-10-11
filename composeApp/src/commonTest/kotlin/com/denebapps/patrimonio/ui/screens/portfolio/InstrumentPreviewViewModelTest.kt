package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.CorrelationCell
import com.denebapps.patrimonio.domain.calc.FitVerdict
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.HoldingSimilarity
import com.denebapps.patrimonio.domain.calc.InstrumentFit
import com.denebapps.patrimonio.domain.calc.annualizedReturn
import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.calc.instrumentFit
import com.denebapps.patrimonio.domain.calc.pointsIn
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
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.PortfolioScopeSeries
import com.denebapps.patrimonio.domain.repository.PreviewFailure
import com.denebapps.patrimonio.domain.repository.PreviewUnavailableException
import com.denebapps.patrimonio.testing.FakeInstrumentPreviewLoader
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioSeriesLoader
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstrumentPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-11T12:00:00Z")
    private val today = LocalDate(2026, 10, 11)
    private val lastFriday = LocalDate(2026, 10, 9)
    private val rates = FxRates(emptyMap())

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // ---- data ---------------------------------------------------------------------------------------------------

    /** Weekly returns following a fixed non-constant pattern of period 5; [sign] flips them. */
    private fun pattern(count: Int, sign: Double = 1.0) =
        List(count) { sign * listOf(0.02, -0.01, 0.03, -0.02, 0.01)[it % 5] }

    /** Another fixed pattern, of period 7, that is nearly uncorrelated with [pattern]. */
    private fun otherPattern(count: Int) = List(count) { listOf(0.02, -0.03, 0.01, 0.03, -0.02, 0.0, -0.01)[it % 7] }

    /** [pattern] plus [otherPattern]: it moves with the first one, but not exactly like it. */
    private fun mixedPattern(count: Int) = pattern(count).zip(otherPattern(count)) { a, b -> a + b }

    /** A Friday close every week, the last one on [lastFriday], following [returns] from a price of 100. */
    private fun weekly(returns: List<Double>): List<PricePoint> {
        var price = 100.0
        var date = lastFriday.minus(returns.size * WEEK_DAYS, DateTimeUnit.DAY)
        val points = mutableListOf(PricePoint(date, Price.fromDouble(price, Currency.EUR)))
        for (r in returns) {
            price *= 1.0 + r
            date = LocalDate.fromEpochDays(date.toEpochDays() + WEEK_DAYS)
            points += PricePoint(date, Price.fromDouble(price, Currency.EUR))
        }
        return points
    }

    private val longReturns = pattern(WEEKS)
    private val longSeries = weekly(longReturns)

    private fun holding(id: String, wholeUnits: Long = 10, fundamentals: InstrumentFundamentals? = null) =
        PortfolioHolding(
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
                lastQuote = LastQuote(Price(100_000_000, Currency.EUR), lastFriday, 1L),
            ),
            fundamentals = fundamentals,
        )

    private fun manualHolding(id: String, wholeEur: Long) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Holding $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(wholeEur * 100), Currency.EUR), 1L),
    )

    private val etfFundamentals = InstrumentFundamentals(
        ongoingChargePercentScaled = 1_800,
        riskLevel = 7,
        category = "Renta variable global",
        managementCompany = "BlackRock",
    )

    private val morningstarEtf = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000CAND"),
        name = "iShares MSCI World",
        isin = "IE00B4L5Y983",
        ticker = null,
        type = InstrumentType.ETF,
        currency = Currency.EUR,
        exchange = null,
        fundamentals = etfFundamentals,
    )

    private val yahooEtf = InstrumentCandidate(
        ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"),
        name = "iShares Core MSCI World",
        isin = null,
        ticker = "IWDA",
        type = InstrumentType.ETF,
        currency = null,
        exchange = "Amsterdam",
    )

    private fun previewOf(candidate: InstrumentCandidate, history: List<PricePoint> = longSeries) =
        InstrumentPreviewData(
            candidate = candidate,
            currency = Currency.EUR,
            history = history,
            eurHistory = history,
            fundamentals = candidate.fundamentals,
        )

    private fun scopeOf(vararg held: Pair<PortfolioHolding, List<PricePoint>?>) = PortfolioScopeSeries(
        holdings = held.map { it.first },
        eurSeries = held.mapNotNull { (holding, series) -> series?.let { holding.id to it } }.toMap(),
        rates = rates,
    )

    private class Setup(
        val loader: FakeInstrumentPreviewLoader,
        val series: FakePortfolioSeriesLoader,
        val vm: InstrumentPreviewViewModel,
    )

    private fun setup(
        candidate: InstrumentCandidate = morningstarEtf,
        history: List<PricePoint> = longSeries,
        scope: PortfolioScopeSeries = scopeOf(holding("a") to longSeries),
        portfolios: List<Portfolio> = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
        selected: String? = null,
    ): Setup {
        val loader = FakeInstrumentPreviewLoader().apply { data = previewOf(candidate, history) }
        val series = FakePortfolioSeriesLoader(scope)
        val vm = InstrumentPreviewViewModel(
            candidate = candidate,
            previewLoader = loader,
            seriesLoader = series,
            portfolios = FakePortfolioCollectionRepository(portfolios),
            preferences = FakePreferencesRepository(selectedPortfolioId = selected),
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
        return Setup(loader, series, vm)
    }

    private fun TestScope.collect(vm: InstrumentPreviewViewModel): Job = launch { vm.state.collect {} }

    /** Loads the preview, types [amount] and lets the projection catch up. */
    private fun TestScope.open(s: Setup, amount: String? = null): Job {
        val job = collect(s.vm)
        advanceUntilIdle()
        if (amount != null) {
            s.vm.onAmountChange(amount)
            advanceUntilIdle()
        }
        return job
    }

    private fun Setup.fit() = assertNotNull(vm.state.value.fit)

    // ---- header and product information ---------------------------------------------------------------------------

    @Test
    fun `while the download runs the candidate is shown and nothing else`() = runTest(dispatcher) {
        val s = setup()
        val gate = CompletableDeferred<Unit>()
        s.loader.gate = gate
        val job = collect(s.vm)
        advanceUntilIdle()

        val state = s.vm.state.value
        assertEquals(PreviewStatus.LOADING, state.status)
        assertEquals("iShares MSCI World", state.header.name)
        assertEquals("ETF", state.header.typeLabel)
        assertNull(state.header.priceText)
        assertNull(state.chart)
        assertNull(state.fit)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PreviewStatus.READY, s.vm.state.value.status)
        assertEquals(listOf(morningstarEtf), s.loader.calls)
        job.cancel()
    }

    @Test
    fun `a Morningstar candidate with fundamentals shows its header, chart, returns, risk and product data`() =
        runTest(dispatcher) {
            val s = setup()
            val job = open(s)

            val state = s.vm.state.value
            assertEquals(PreviewStatus.READY, state.status)
            assertEquals("iShares MSCI World", state.header.name)
            assertEquals("ETF", state.header.typeLabel)
            assertEquals("IE00B4L5Y983 · Morningstar", state.header.identifier)
            assertNull(state.header.exchange)
            assertEquals(formatPrice(longSeries.last().price), state.header.priceText)
            assertEquals("09/10/2026", state.header.dateText)

            assertEquals(HistoryRange.ONE_YEAR, state.ranges.single { it.selected }.range)
            assertEquals(pointsIn(longSeries, HistoryRange.ONE_YEAR, today), state.chart?.prices)
            assertEquals(yearReturnsUi(longSeries, today), state.yearReturns)
            assertEquals(listOf("1 año", "3 años", "5 años"), state.annualized.map { it.label })
            assertEquals(formatPercent(annualizedReturn(longSeries, 1)!!), state.annualized.first().valueText)
            assertEquals(NO_FIGURE_TEXT, state.annualized.last().valueText)
            assertEquals(riskUi(longSeries, today), state.risk)
            assertEquals(
                ProductDataUi(
                    terText = "0,18 %",
                    yearlyCostText = null,
                    riskLevel = 7,
                    category = "Renta variable global",
                    manager = "BlackRock",
                ),
                state.product,
            )
            job.cancel()
        }

    @Test
    fun `a Yahoo candidate without fundamentals names its ticker and exchange and has no product data`() =
        runTest(dispatcher) {
            val s = setup(candidate = yahooEtf)
            val job = open(s)

            val state = s.vm.state.value
            assertEquals("IWDA · Yahoo Finance", state.header.identifier)
            assertEquals("Amsterdam", state.header.exchange)
            assertNull(state.product)
            assertNotNull(state.chart)
            job.cancel()
        }

    @Test
    fun `picking another range redraws the chart`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onRangeSelect(HistoryRange.SIX_MONTHS)
        advanceUntilIdle()
        assertEquals(HistoryRange.SIX_MONTHS, s.vm.state.value.ranges.single { it.selected }.range)
        assertEquals(pointsIn(longSeries, HistoryRange.SIX_MONTHS, today), s.vm.state.value.chart?.prices)

        s.vm.onRangeSelect(HistoryRange.MAX)
        advanceUntilIdle()
        assertEquals(longSeries, s.vm.state.value.chart?.prices)
        job.cancel()
    }

    @Test
    fun `a range without data is disabled, cannot be picked and the chart shows all the history`() =
        runTest(dispatcher) {
            val sparse = listOf(
                PricePoint(LocalDate(2025, 1, 3), Price.fromDouble(100.0, Currency.EUR)),
                PricePoint(lastFriday, Price.fromDouble(110.0, Currency.EUR)),
            )
            val s = setup(history = sparse)
            val job = open(s)

            assertEquals(listOf(false, false, false, true, true), s.vm.state.value.ranges.map { it.enabled })
            assertEquals(HistoryRange.MAX, s.vm.state.value.ranges.single { it.selected }.range)

            s.vm.onRangeSelect(HistoryRange.ONE_MONTH)
            advanceUntilIdle()
            assertEquals(HistoryRange.MAX, s.vm.state.value.ranges.single { it.selected }.range)
            job.cancel()
        }

    // ---- failures -------------------------------------------------------------------------------------------------

    @Test
    fun `a failed download says so, keeps the candidate and a retry loads it`() = runTest(dispatcher) {
        val s = setup()
        s.loader.failure = PreviewUnavailableException(PreviewFailure.UNAVAILABLE, "offline")
        val job = open(s)

        val failed = s.vm.state.value
        assertEquals(PreviewStatus.UNAVAILABLE, failed.status)
        assertEquals("iShares MSCI World", failed.header.name)
        assertNull(failed.chart)
        assertNull(failed.fit)

        s.loader.failure = null
        s.vm.onRetry()
        advanceUntilIdle()
        assertEquals(PreviewStatus.READY, s.vm.state.value.status)
        assertEquals(2, s.loader.calls.size)
        job.cancel()
    }

    @Test
    fun `an unexpected failure is also an unavailable preview`() = runTest(dispatcher) {
        val s = setup()
        s.loader.failure = IllegalStateException("boom")
        val job = open(s)

        assertEquals(PreviewStatus.UNAVAILABLE, s.vm.state.value.status)
        job.cancel()
    }

    @Test
    fun `a product the provider has no history for is reported as such`() = runTest(dispatcher) {
        val s = setup()
        s.loader.failure = PreviewUnavailableException(PreviewFailure.NO_DATA, "empty")
        val job = open(s)

        assertEquals(PreviewStatus.NO_DATA, s.vm.state.value.status)
        assertNull(s.vm.state.value.fit)
        job.cancel()
    }

    @Test
    fun `a history too short to draw is reported as no data`() = runTest(dispatcher) {
        val s = setup(history = longSeries.take(1))
        val job = open(s)

        assertEquals(PreviewStatus.NO_DATA, s.vm.state.value.status)
        assertNull(s.vm.state.value.chart)
        job.cancel()
    }

    // ---- the portfolio it is compared with ------------------------------------------------------------------------

    @Test
    fun `the fit is titled with the selected portfolio and compares with it`() = runTest(dispatcher) {
        val s = setup(
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("pf-hijos", "Hijos", 1)),
            selected = "pf-hijos",
        )
        val job = open(s)

        assertEquals("Encaje con «Hijos»", s.fit().title)
        assertEquals("pf-hijos", s.vm.state.value.scopePortfolioId)
        assertEquals(listOf<String?>("pf-hijos"), s.series.requests)
        job.cancel()
    }

    @Test
    fun `with no selection the fit compares with every portfolio`() = runTest(dispatcher) {
        val s = setup(
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("pf-hijos", "Hijos", 1)),
        )
        val job = open(s)

        assertEquals("Encaje con todas tus carteras", s.fit().title)
        assertNull(s.vm.state.value.scopePortfolioId)
        assertEquals(listOf<String?>(null), s.series.requests)
        job.cancel()
    }

    @Test
    fun `a selection that no longer exists falls back to every portfolio`() = runTest(dispatcher) {
        val s = setup(
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("pf-hijos", "Hijos", 1)),
            selected = "gone",
        )
        val job = open(s)

        assertEquals("Encaje con todas tus carteras", s.fit().title)
        assertNull(s.vm.state.value.scopePortfolioId)
        job.cancel()
    }

    @Test
    fun `with a single portfolio the fit is titled with its name`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertEquals("Encaje con «Mi cartera»", s.fit().title)
        assertNull(s.vm.state.value.scopePortfolioId)
        job.cancel()
    }

    // ---- verdict --------------------------------------------------------------------------------------------------

    @Test
    fun `a product that moves like the portfolio diversifies little`() = runTest(dispatcher) {
        val s = setup(history = longSeries)
        val job = open(s)

        val verdict = assertNotNull(s.fit().verdict)
        assertEquals("Diversifica poco", verdict.title)
        assertEquals("Se mueve casi igual que tu cartera (1,00).", verdict.detail)
        assertTrue(Regex("Correlación 1,00 · \\d+ semanas").matches(verdict.caption), verdict.caption)
        assertEquals(FitTone.ALERT, verdict.tone)
        assertNull(s.fit().message)
        job.cancel()
    }

    @Test
    fun `a product that partly moves like the portfolio diversifies moderately`() = runTest(dispatcher) {
        val candidate = weekly(mixedPattern(WEEKS))
        val s = setup(history = candidate)
        val job = open(s)

        val scope = s.series.scope
        val expected = instrumentFit(
            previewOf(morningstarEtf, candidate),
            scope.holdings,
            scope.eurSeries,
            rates,
            today,
            amount = null,
        ).portfolio
        val verdict = assertNotNull(s.fit().verdict)
        assertEquals("Diversifica moderadamente", verdict.title)
        assertEquals("Se parece en parte a tu cartera (${formatCorrelation(expected?.correlation)}).", verdict.detail)
        assertEquals(
            "Correlación ${formatCorrelation(expected?.correlation)} · ${weeksText(expected!!.weeks)}",
            verdict.caption,
        )
        assertEquals(FitTone.NEUTRAL, verdict.tone)
        job.cancel()
    }

    @Test
    fun `a product that moves against the portfolio brings diversification`() = runTest(dispatcher) {
        val s = setup(history = weekly(pattern(WEEKS, sign = -1.0)))
        val job = open(s)

        val verdict = assertNotNull(s.fit().verdict)
        assertEquals("Aporta diversificación", verdict.title)
        assertEquals("Se mueve de forma distinta a tu cartera (−1,00).", verdict.detail)
        assertEquals(FitTone.POSITIVE, verdict.tone)
        job.cancel()
    }

    @Test
    fun `the verdict names the holding when the portfolio as a whole cannot be told`() {
        val held = holding("a")
        val fit = InstrumentFit(
            holdings = listOf(HoldingSimilarity(held, CorrelationCell(0.95, 40))),
            portfolio = null,
            verdict = FitVerdict.LOW_DIVERSIFICATION,
            projection = null,
        )

        val section = fitSectionUi("Encaje", previewOf(morningstarEtf), scopeOf(held to longSeries), fit, today)

        assertEquals("Se mueve casi igual que «Holding a» (0,95).", section.verdict?.detail)
        assertEquals("Correlación 0,95 · 40 semanas", section.verdict?.caption)
    }

    // ---- similar holdings -----------------------------------------------------------------------------------------

    @Test
    fun `the most similar holdings are listed best first and at most three`() = runTest(dispatcher) {
        val s = setup(
            scope = scopeOf(
                holding("far") to weekly(otherPattern(WEEKS)),
                holding("same") to longSeries,
                holding("against") to weekly(pattern(WEEKS, sign = -1.0)),
                holding("near") to weekly(mixedPattern(WEEKS)),
            ),
        )
        val job = open(s)

        val similar = s.fit().similar
        assertEquals(listOf("Holding same", "Holding near", "Holding far"), similar.map { it.name })
        assertEquals("1,00", similar.first().correlationText)
        assertNull(similar.first().note)
        job.cancel()
    }

    @Test
    fun `a holding without enough history shows a dash and is listed last`() = runTest(dispatcher) {
        val s = setup(scope = scopeOf(holding("short") to weekly(pattern(10)), holding("same") to longSeries))
        val job = open(s)

        val similar = s.fit().similar
        assertEquals(listOf("Holding same", "Holding short"), similar.map { it.name })
        assertEquals(SimilarHoldingUi("Holding short", "—", "sin histórico suficiente"), similar.last())
        job.cancel()
    }

    // ---- states with nothing to compare ----------------------------------------------------------------------------

    @Test
    fun `a portfolio without products with history asks for some`() = runTest(dispatcher) {
        val s = setup(scope = scopeOf(holding("a") to null, manualHolding("m", 1_000) to null))
        val job = open(s)

        val fit = s.fit()
        assertEquals("Añade productos con histórico para ver cómo encaja.", fit.message)
        assertNull(fit.verdict)
        assertTrue(fit.similar.isEmpty())
        assertTrue(fit.canProject)
        job.cancel()
    }

    @Test
    fun `an empty portfolio has nothing to compare or to project`() = runTest(dispatcher) {
        val s = setup(scope = scopeOf())
        val job = open(s, amount = "1000")

        val fit = s.fit()
        assertEquals("Añade productos con histórico para ver cómo encaja.", fit.message)
        assertFalse(fit.canProject)
        assertNull(fit.projection)
        job.cancel()
    }

    @Test
    fun `a candidate with a short history cannot be compared but can still be projected`() = runTest(dispatcher) {
        val s = setup(history = weekly(pattern(10)))
        val job = open(s, amount = "1000")

        val fit = s.fit()
        assertEquals("Este producto no tiene histórico suficiente para compararlo.", fit.message)
        assertNull(fit.verdict)
        assertTrue(fit.similar.isEmpty())
        assertTrue(fit.canProject)
        assertNotNull(fit.projection)
        job.cancel()
    }

    @Test
    fun `a portfolio whose products have a short history cannot be compared either`() = runTest(dispatcher) {
        val s = setup(scope = scopeOf(holding("a") to weekly(pattern(10))))
        val job = open(s)

        assertEquals(
            "Los productos de tu cartera no tienen histórico suficiente para compararlos.",
            s.fit().message,
        )
        job.cancel()
    }

    // ---- projection -----------------------------------------------------------------------------------------------

    private val twoFundsScope
        get() = scopeOf(
            holding("a", fundamentals = InstrumentFundamentals(2_000, riskLevel = 4)) to longSeries,
            holding("b", fundamentals = InstrumentFundamentals(1_000, riskLevel = 6)) to weekly(otherPattern(WEEKS)),
        )

    @Test
    fun `an amount projects the weight, the average cost and risk, and the yearly cost of the product`() =
        runTest(dispatcher) {
            val s = setup(scope = twoFundsScope)
            val job = open(s, amount = "1000")

            assertEquals(
                ProjectionUi(
                    weightText = "Pesaría un 33,3 % de la cartera",
                    terText = "0,15 % → 0,16 %",
                    terCoverage = null,
                    riskText = "5,0 → 5,7 de 7",
                    riskCoverage = null,
                    warning = null,
                ),
                s.fit().projection,
            )
            assertEquals("≈ 1,80 €/año con 1.000,00 €", s.vm.state.value.product?.yearlyCostText)
            assertEquals("1000", s.vm.state.value.amountText)
            job.cancel()
        }

    @Test
    fun `the projection says when only some products have cost and risk data`() = runTest(dispatcher) {
        val s = setup(
            candidate = yahooEtf,
            scope = scopeOf(
                holding("a", fundamentals = InstrumentFundamentals(2_000, riskLevel = 4)) to longSeries,
                holding("b") to weekly(otherPattern(WEEKS)),
            ),
        )
        val job = open(s, amount = "1000")

        val projection = assertNotNull(s.fit().projection)
        assertEquals("0,20 % → 0,20 %", projection.terText)
        assertEquals("Con datos para 1 de 3 productos", projection.terCoverage)
        assertEquals("4,0 → 4,0 de 7", projection.riskText)
        assertEquals("Con datos para 1 de 3 productos", projection.riskCoverage)
        job.cancel()
    }

    @Test
    fun `a portfolio without cost data shows the cost of the new product alone`() = runTest(dispatcher) {
        val s = setup(scope = scopeOf(holding("a") to longSeries))
        val job = open(s, amount = "1000")

        val projection = assertNotNull(s.fit().projection)
        assertEquals("— → 0,18 %", projection.terText)
        assertEquals("— → 7,0 de 7", projection.riskText)
        job.cancel()
    }

    @Test
    fun `a product with no cost or risk data leaves those rows out`() = runTest(dispatcher) {
        val s = setup(candidate = yahooEtf, scope = scopeOf(holding("a") to longSeries))
        val job = open(s, amount = "1000")

        val projection = assertNotNull(s.fit().projection)
        assertNull(projection.terText)
        assertNull(projection.riskText)
        assertEquals("Pesaría un 50,0 % de la cartera", projection.weightText)
        job.cancel()
    }

    @Test
    fun `an amount that would make the new product the bulk of the portfolio warns about it`() = runTest(dispatcher) {
        val s = setup(scope = twoFundsScope)
        val job = open(s, amount = "5000")

        val projection = assertNotNull(s.fit().projection)
        assertEquals("Pesaría un 71,4 % de la cartera", projection.weightText)
        assertEquals("«iShares MSCI World» supondría el 71,4 % de tu cartera.", projection.warning)
        job.cancel()
    }

    @Test
    fun `the warning names the holding that stays above the limit`() = runTest(dispatcher) {
        val s = setup(scope = scopeOf(holding("big", wholeUnits = 90) to longSeries, holding("small") to longSeries))
        val job = open(s, amount = "1000")

        assertEquals("«Holding big» supondría el 81,8 % de tu cartera.", s.fit().projection?.warning)
        job.cancel()
    }

    @Test
    fun `clearing the amount removes the projection and the yearly cost`() = runTest(dispatcher) {
        val s = setup(scope = twoFundsScope)
        val job = open(s, amount = "1000")
        assertNotNull(s.fit().projection)

        s.vm.onAmountChange("")
        advanceUntilIdle()

        assertNull(s.fit().projection)
        assertNull(s.vm.state.value.product?.yearlyCostText)
        assertEquals("", s.vm.state.value.amountText)
        job.cancel()
    }

    @Test
    fun `an amount of zero projects nothing`() = runTest(dispatcher) {
        val s = setup(scope = twoFundsScope)
        val job = open(s, amount = "0")

        assertNull(s.fit().projection)
        job.cancel()
    }

    @Test
    fun `the amount field follows the typing at once and the projection a moment later`() = runTest(dispatcher) {
        val s = setup(scope = twoFundsScope)
        val job = open(s)

        s.vm.onAmountChange("1")
        s.vm.onAmountChange("10")
        s.vm.onAmountChange("100")
        runCurrent()
        assertEquals("100", s.vm.state.value.amountText)
        assertNull(s.fit().projection)

        advanceTimeBy(AMOUNT_DEBOUNCE_MS)
        runCurrent()
        assertNotNull(s.fit().projection)
        assertEquals("Pesaría un 4,8 % de la cartera", s.fit().projection?.weightText)
        job.cancel()
    }

    private companion object {
        const val WEEKS = 60
        const val WEEK_DAYS = 7
    }
}
