package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.AllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeAllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "Objetivo de reparto" section of the portfolio detail and the sheet that edits it. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioDetailTargetsTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }
    private val other = Portfolio("pf-hijos", "Hijos", 1)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun manual(
        id: String,
        euros: Long,
        assetClass: AssetClass? = null,
        portfolioId: String = TEST_PORTFOLIO_ID,
    ) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Holding $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 1L),
        assetClass = assetClass,
    )

    private class Setup(
        val targets: FakeAllocationTargetRepository,
        val vm: PortfolioDetailViewModel,
    ) {
        val state get() = vm.state.value
        val section get() = state.targetSection
        val defined get() = assertIs<TargetSectionUi.Defined>(section)
        val editor get() = assertNotNull(state.targetsEditor)
    }

    private fun setup(
        holdings: List<PortfolioHolding>,
        portfolioId: String? = TEST_PORTFOLIO_ID,
        portfolios: List<Portfolio> = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), other),
        seeded: Map<AssetClass, Int>? = null,
        toleranceBp: Int = 500,
        targetsOverride: ((FakeAllocationTargetRepository) -> AllocationTargetRepository)? = null,
    ): Setup {
        val repository = FakePortfolioRepository(holdings)
        val collection = FakePortfolioCollectionRepository(portfolios, repository)
        val targets = FakeAllocationTargetRepository(collection)
        seeded?.let { targets.seed(TEST_PORTFOLIO_ID, it, toleranceBp) }
        val vm = PortfolioDetailViewModel(
            portfolioId = portfolioId,
            portfolios = collection,
            allocationTargets = targetsOverride?.invoke(targets) ?: targets,
            portfolioRepository = repository,
            movementRepository = FakeMovementRepository(repository),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            fxHistoryRepository = FakeFxHistoryRepository(FxHistory(emptyMap())),
            historyRepository = FakeInstrumentHistoryRepository(),
            preferences = FakePreferencesRepository(),
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
        return Setup(targets, vm)
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch { s.vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    private val seventyTwoTwentyEight = listOf(
        manual("a", 7_200, AssetClass.EQUITY),
        manual("b", 2_800, AssetClass.FIXED_INCOME),
    )
    private val eightyTwenty = mapOf(AssetClass.EQUITY to 8_000, AssetClass.FIXED_INCOME to 2_000)

    // ---- the section -------------------------------------------------------------------------------------------

    @Test
    fun `a portfolio without targets offers to define them`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight)
        val job = open(s)

        val section = assertIs<TargetSectionUi.Empty>(s.section)
        assertEquals(
            "Define qué porcentaje quieres en cada clase de activo y te avisaremos cuando la cartera se desvíe.",
            section.message,
        )
        assertNull(s.state.targetsEditor)
        job.cancel()
    }

    @Test
    fun `all the portfolios together have no targets to show, only a note to pick one`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, portfolioId = null)
        val job = open(s)

        val section = assertIs<TargetSectionUi.PickPortfolio>(s.section)
        assertEquals("Elige una cartera para definir su objetivo", section.message)
        job.cancel()
    }

    @Test
    fun `with a single portfolio the detail of all of them is the detail of that one`() = runTest(dispatcher) {
        val s = setup(
            seventyTwoTwentyEight,
            portfolioId = null,
            portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
            seeded = eightyTwenty,
        )
        val job = open(s)

        assertEquals(listOf("Renta variable", "Renta fija"), s.defined.rows.map { it.label })
        job.cancel()
    }

    @Test
    fun `a portfolio that is gone shows no section`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, portfolioId = "pf-gone")
        val job = open(s)

        assertNull(s.section)
        job.cancel()
    }

    @Test
    fun `out of band every class shows its actual share, target and drift`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)

        val section = s.defined
        assertEquals(
            listOf(
                TargetRowUi(
                    assetClass = AssetClass.EQUITY,
                    label = "Renta variable",
                    detailText = "Actual 72 % · Objetivo 80 %",
                    driftText = "−8 pts",
                    outOfBand = true,
                    actualFraction = 0.72f,
                    targetFraction = 0.8f,
                ),
                TargetRowUi(
                    assetClass = AssetClass.FIXED_INCOME,
                    label = "Renta fija",
                    detailText = "Actual 28 % · Objetivo 20 %",
                    driftText = "+8 pts",
                    outOfBand = true,
                    actualFraction = 0.28f,
                    targetFraction = 0.2f,
                ),
            ),
            section.rows,
        )
        assertEquals("Margen ±5 pts", section.toleranceText)
        assertEquals("Fuera del margen", section.statusText)
        assertTrue(section.outOfBand)
        job.cancel()
    }

    @Test
    fun `out of band it suggests where to contribute without selling`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)

        val suggestion = assertNotNull(s.defined.suggestion)
        assertEquals("Para volver al objetivo sin vender: aporta 4.000,00 €", suggestion.headline)
        // The class with a share of zero is left out.
        assertEquals(listOf("Renta variable: 4.000,00 €"), suggestion.lines.map { it.text })
        assertEquals(AssetClass.EQUITY, suggestion.lines.single().assetClass)
        job.cancel()
    }

    @Test
    fun `the contribution is split over every class that needs it`() = runTest(dispatcher) {
        val s = setup(
            listOf(
                manual("a", 9_200, AssetClass.EQUITY),
                manual("b", 600, AssetClass.FIXED_INCOME),
                manual("c", 200, AssetClass.MONEY_MARKET),
            ),
            seeded = mapOf(
                AssetClass.EQUITY to 8_000,
                AssetClass.FIXED_INCOME to 1_500,
                AssetClass.MONEY_MARKET to 500,
            ),
        )
        val job = open(s)

        val suggestion = assertNotNull(s.defined.suggestion)
        assertEquals("Para volver al objetivo sin vender: aporta 1.500,00 €", suggestion.headline)
        // Equity is already on target: it takes nothing.
        assertEquals(listOf("Renta fija: 1.125,00 €", "Monetario: 375,00 €"), suggestion.lines.map { it.text })
        job.cancel()
    }

    @Test
    fun `within the band it says so and suggests nothing`() = runTest(dispatcher) {
        val s = setup(
            listOf(manual("a", 7_800, AssetClass.EQUITY), manual("b", 2_200, AssetClass.FIXED_INCOME)),
            seeded = eightyTwenty,
        )
        val job = open(s)

        val section = s.defined
        assertEquals("Dentro del margen", section.statusText)
        assertFalse(section.outOfBand)
        assertNull(section.suggestion)
        assertEquals(listOf("−2 pts", "+2 pts"), section.rows.map { it.driftText })
        assertTrue(section.rows.none { it.outOfBand })
        job.cancel()
    }

    @Test
    fun `the tolerance shown is the one stored with the targets`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty, toleranceBp = 1_000)
        val job = open(s)

        val section = s.defined
        assertEquals("Margen ±10 pts", section.toleranceText)
        assertEquals("Dentro del margen", section.statusText)
        job.cancel()
    }

    @Test
    fun `a held class with no target is only corrected by selling`() = runTest(dispatcher) {
        val s = setup(
            listOf(
                manual("a", 6_000, AssetClass.EQUITY),
                manual("b", 2_000, AssetClass.FIXED_INCOME),
                manual("c", 2_000, AssetClass.CRYPTO),
            ),
            seeded = eightyTwenty,
        )
        val job = open(s)

        val section = s.defined
        assertEquals(listOf("Cripto no tiene objetivo: solo se corrige vendiendo."), section.onlySellingNotes)
        val crypto = section.rows.single { it.assetClass == AssetClass.CRYPTO }
        assertEquals("Actual 20 % · Objetivo 0 %", crypto.detailText)
        assertEquals("+20 pts", crypto.driftText)
        job.cancel()
    }

    @Test
    fun `a class with no target that is within the band needs no note`() = runTest(dispatcher) {
        val s = setup(
            listOf(
                manual("a", 7_800, AssetClass.EQUITY),
                manual("b", 2_000, AssetClass.FIXED_INCOME),
                manual("c", 200, AssetClass.CRYPTO),
            ),
            seeded = eightyTwenty,
        )
        val job = open(s)

        assertTrue(s.defined.onlySellingNotes.isEmpty())
        job.cancel()
    }

    @Test
    fun `products without a class are counted apart`() = runTest(dispatcher) {
        val one = setup(seventyTwoTwentyEight + manual("u", 1_000), seeded = eightyTwenty)
        val two = setup(seventyTwoTwentyEight + manual("u", 1_000) + manual("v", 500), seeded = eightyTwenty)
        val none = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val jobs = listOf(open(one), open(two), open(none))

        assertEquals("1 producto sin clase no cuenta.", one.defined.unclassifiedNote)
        assertEquals("2 productos sin clase no cuentan.", two.defined.unclassifiedNote)
        assertNull(none.defined.unclassifiedNote)
        // They are left out of the shares: the classified 100 % is what the drift is measured on.
        assertEquals("Actual 72 % · Objetivo 80 %", one.defined.rows.first().detailText)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `targets with no products to compare say so instead of within the band`() = runTest(dispatcher) {
        val s = setup(emptyList(), seeded = eightyTwenty)
        val job = open(s)

        val section = s.defined
        assertEquals("Aún no hay productos con clase para comparar", section.statusText)
        assertFalse(section.outOfBand)
        assertNull(section.suggestion)
        job.cancel()
    }

    @Test
    fun `only the holdings of the portfolio count`() = runTest(dispatcher) {
        val s = setup(
            seventyTwoTwentyEight + manual("x", 90_000, AssetClass.CRYPTO, portfolioId = other.id),
            seeded = eightyTwenty,
        )
        val job = open(s)

        assertEquals(listOf(AssetClass.EQUITY, AssetClass.FIXED_INCOME), s.defined.rows.map { it.assetClass })
        job.cancel()
    }

    // ---- the calculator ----------------------------------------------------------------------------------------

    @Test
    fun `the calculator starts empty`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)

        assertEquals(CalculatorUi(amountText = "", shares = emptyList(), error = null), s.defined.calculator)
        job.cancel()
    }

    @Test
    fun `an amount to contribute is split over the most underweight classes first`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)

        s.vm.onCalculatorChange("1000")
        advanceUntilIdle()
        val small = s.defined.calculator
        s.vm.onCalculatorChange("5000")
        advanceUntilIdle()
        val large = s.defined.calculator

        assertEquals("1000", small.amountText)
        assertEquals(listOf("Renta variable: 1.000,00 €"), small.shares.map { it.text })
        assertEquals(
            listOf("Renta variable: 4.800,00 €", "Renta fija: 200,00 €"),
            large.shares.map { it.text },
        )
        assertNull(large.error)
        job.cancel()
    }

    @Test
    fun `the calculator reads a decimal comma and ignores other characters`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)

        s.vm.onCalculatorChange("12.50x")
        advanceUntilIdle()

        val calculator = s.defined.calculator
        assertEquals("12,50", calculator.amountText)
        assertEquals(listOf("Renta variable: 12,50 €"), calculator.shares.map { it.text })
        job.cancel()
    }

    @Test
    fun `an amount that is not a valid amount asks for another one`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)

        s.vm.onCalculatorChange("0")
        advanceUntilIdle()
        val zero = s.defined.calculator
        s.vm.onCalculatorChange("1,234")
        advanceUntilIdle()
        val tooPrecise = s.defined.calculator

        assertEquals("Escribe un importe válido.", zero.error)
        assertTrue(zero.shares.isEmpty())
        assertEquals("Escribe un importe válido.", tooPrecise.error)
        job.cancel()
    }

    // ---- the editor --------------------------------------------------------------------------------------------

    @Test
    fun `the editor starts closed and opens on request`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight)
        val job = open(s)
        assertNull(s.state.targetsEditor)

        s.vm.onEditTargets()
        advanceUntilIdle()

        assertNotNull(s.state.targetsEditor)
        job.cancel()
    }

    @Test
    fun `the editor has a field per class in order, each with its label`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight)
        val job = open(s)

        s.vm.onEditTargets()
        advanceUntilIdle()

        assertEquals(AssetClass.entries, s.editor.fields.map { it.assetClass })
        assertEquals(AssetClass.entries.map(::assetClassLabel), s.editor.fields.map { it.label })
        job.cancel()
    }

    @Test
    fun `existing targets prefill the fields, with decimals`() = runTest(dispatcher) {
        val s = setup(
            seventyTwoTwentyEight,
            seeded = mapOf(AssetClass.EQUITY to 8_750, AssetClass.FIXED_INCOME to 1_250),
            toleranceBp = 300,
        )
        val job = open(s)

        s.vm.onEditTargets()
        advanceUntilIdle()

        val editor = s.editor
        assertEquals("87,5", editor.fields.first { it.assetClass == AssetClass.EQUITY }.text)
        assertEquals("12,5", editor.fields.first { it.assetClass == AssetClass.FIXED_INCOME }.text)
        val filled = setOf(AssetClass.EQUITY, AssetClass.FIXED_INCOME)
        val untouched = editor.fields.filter { it.assetClass !in filled }
        assertTrue(untouched.all { it.text.isEmpty() })
        assertEquals("3", editor.toleranceText)
        assertEquals("Suma: 100 %", editor.sumText)
        assertTrue(editor.sumValid)
        assertTrue(editor.canSave)
        assertTrue(editor.canRemove)
        job.cancel()
    }

    @Test
    fun `without targets the fields start with the current split in whole percents adding up to 100`() =
        runTest(dispatcher) {
            val s = setup(
                listOf(
                    manual("a", 1_000, AssetClass.EQUITY),
                    manual("b", 1_000, AssetClass.FIXED_INCOME),
                    manual("c", 1_000, AssetClass.MONEY_MARKET),
                ),
                portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0, rebalanceToleranceBp = 250), other),
            )
            val job = open(s)

            s.vm.onEditTargets()
            advanceUntilIdle()

            val editor = s.editor
            assertEquals(
                mapOf(AssetClass.EQUITY to "34", AssetClass.FIXED_INCOME to "33", AssetClass.MONEY_MARKET to "33"),
                editor.fields.filter { it.text.isNotEmpty() }.associate { it.assetClass to it.text },
            )
            assertEquals("Suma: 100 %", editor.sumText)
            assertTrue(editor.canSave)
            assertFalse(editor.canRemove)
            // The band the portfolio already has, 2,5 points.
            assertEquals("2,5", editor.toleranceText)
            job.cancel()
        }

    @Test
    fun `a portfolio with nothing to split starts with empty fields that cannot be saved`() = runTest(dispatcher) {
        val s = setup(emptyList())
        val job = open(s)

        s.vm.onEditTargets()
        advanceUntilIdle()

        val editor = s.editor
        assertTrue(editor.fields.all { it.text.isEmpty() })
        assertEquals("Suma: 0 %", editor.sumText)
        assertFalse(editor.sumValid)
        assertFalse(editor.canSave)
        assertEquals("5", editor.toleranceText)
        job.cancel()
    }

    @Test
    fun `the sum follows the fields and only 100 percent can be saved`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        s.vm.onTargetChange(AssetClass.EQUITY, "70")
        advanceUntilIdle()
        val short = s.editor
        s.vm.onTargetChange(AssetClass.CRYPTO, "10,5")
        advanceUntilIdle()
        val over = s.editor
        s.vm.onTargetChange(AssetClass.CRYPTO, "10")
        advanceUntilIdle()
        val exact = s.editor

        assertEquals("Suma: 90 %", short.sumText)
        assertFalse(short.sumValid)
        assertFalse(short.canSave)
        assertEquals("Suma: 100,5 %", over.sumText)
        assertFalse(over.canSave)
        assertEquals("Suma: 100 %", exact.sumText)
        assertTrue(exact.sumValid)
        assertTrue(exact.canSave)
        job.cancel()
    }

    @Test
    fun `a field only takes a percentage`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        s.vm.onTargetChange(AssetClass.EQUITY, "12.5a")
        advanceUntilIdle()

        assertEquals("12,5", s.editor.fields.first { it.assetClass == AssetClass.EQUITY }.text)
        job.cancel()
    }

    @Test
    fun `the tolerance must be a number of points from 0 to 100`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        val results = listOf("", "101", "0", "100", "2,5", ",").map { text ->
            s.vm.onToleranceChange(text)
            advanceUntilIdle()
            s.editor
        }

        val message = "Escribe un margen entre 0 y 100 puntos."
        assertEquals(message, results[0].toleranceError)
        assertFalse(results[0].canSave)
        assertEquals(message, results[1].toleranceError)
        assertFalse(results[1].canSave)
        assertNull(results[2].toleranceError)
        assertTrue(results[2].canSave)
        assertNull(results[3].toleranceError)
        assertNull(results[4].toleranceError)
        assertEquals(message, results[5].toleranceError)
        job.cancel()
    }

    @Test
    fun `saving stores the targets and the tolerance in basis points and closes the editor`() = runTest(dispatcher) {
        val s = setup(
            listOf(
                manual("a", 1_000, AssetClass.EQUITY),
                manual("b", 1_000, AssetClass.FIXED_INCOME),
                manual("c", 1_000, AssetClass.MONEY_MARKET),
            ),
        )
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        s.vm.onTargetChange(AssetClass.EQUITY, "33,5")
        s.vm.onTargetChange(AssetClass.FIXED_INCOME, "33,5")
        s.vm.onTargetChange(AssetClass.MONEY_MARKET, "33")
        s.vm.onToleranceChange("2,5")
        advanceUntilIdle()
        s.vm.onSaveTargets()
        advanceUntilIdle()

        assertEquals(
            listOf(
                AllocationTargets(
                    TEST_PORTFOLIO_ID,
                    mapOf(
                        AssetClass.EQUITY to 3_350,
                        AssetClass.FIXED_INCOME to 3_350,
                        AssetClass.MONEY_MARKET to 3_300,
                    ),
                    toleranceBp = 250,
                ),
            ),
            s.targets.setCalls,
        )
        assertNull(s.state.targetsEditor)
        assertEquals("Margen ±2,5 pts", s.defined.toleranceText)
        job.cancel()
    }

    @Test
    fun `an invalid editor does not save`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()
        s.vm.onTargetChange(AssetClass.EQUITY, "70")
        advanceUntilIdle()

        s.vm.onSaveTargets()
        advanceUntilIdle()

        assertTrue(s.targets.setCalls.isEmpty())
        assertNotNull(s.state.targetsEditor)
        job.cancel()
    }

    @Test
    fun `removing the targets clears them and closes the editor`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        s.vm.onClearTargets()
        advanceUntilIdle()

        assertEquals(listOf(TEST_PORTFOLIO_ID), s.targets.clearCalls)
        assertNull(s.state.targetsEditor)
        assertIs<TargetSectionUi.Empty>(s.section)
        job.cancel()
    }

    @Test
    fun `there is nothing to remove when no targets were set`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        s.vm.onClearTargets()
        advanceUntilIdle()

        assertTrue(s.targets.clearCalls.isEmpty())
        assertNotNull(s.state.targetsEditor)
        job.cancel()
    }

    @Test
    fun `cancelling the editor changes nothing`() = runTest(dispatcher) {
        val s = setup(seventyTwoTwentyEight, seeded = eightyTwenty)
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()
        s.vm.onTargetChange(AssetClass.EQUITY, "10")
        advanceUntilIdle()

        s.vm.onDismissEditor()
        advanceUntilIdle()

        assertNull(s.state.targetsEditor)
        assertTrue(s.targets.setCalls.isEmpty())
        assertEquals("Actual 72 % · Objetivo 80 %", s.defined.rows.first().detailText)
        job.cancel()
    }

    @Test
    fun `a refused save keeps the editor open with the reason`() = runTest(dispatcher) {
        val s = setup(
            seventyTwoTwentyEight,
            seeded = eightyTwenty,
            targetsOverride = { fake ->
                object : AllocationTargetRepository by fake {
                    override suspend fun setTargets(
                        portfolioId: String,
                        targetsBp: Map<AssetClass, Int>,
                        toleranceBp: Int,
                    ) {
                        throw IllegalStateException("disk full")
                    }
                }
            },
        )
        val job = open(s)
        s.vm.onEditTargets()
        advanceUntilIdle()

        s.vm.onSaveTargets()
        advanceUntilIdle()

        val editor = s.editor
        assertEquals("No se pudo guardar el objetivo.", editor.error)
        assertFalse(editor.saving)
        assertTrue(editor.canSave)
        // Typing again clears the error.
        s.vm.onToleranceChange("4")
        advanceUntilIdle()
        assertNull(s.editor.error)
        job.cancel()
    }
}
