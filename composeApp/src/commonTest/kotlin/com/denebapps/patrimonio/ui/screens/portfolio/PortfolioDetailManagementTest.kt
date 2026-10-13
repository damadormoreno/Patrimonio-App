package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.MAX_PORTFOLIO_NAME_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.testing.FakeAllocationTargetRepository
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeInstrumentHistoryRepository
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Gestionar cartera" in the portfolio detail: rename and delete, with the rules the Cartera selector used to have. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioDetailManagementTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-11T12:00:00Z")
    }

    private val jubilacion = Portfolio("pf-jub", "Jubilación", 0)
    private val hijos = Portfolio("pf-hijos", "Hijos", 1)
    private val ocio = Portfolio("pf-ocio", "Ocio", 2)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun manual(id: String, portfolioId: String, euros: Long) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = "Producto $id",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 1L),
    )

    // Jubilación: a + b; Hijos: c; Ocio: empty.
    private val a = manual("a", "pf-jub", 1_000)
    private val b = manual("b", "pf-jub", 2_000)
    private val c = manual("c", "pf-hijos", 500)

    private class Setup(
        val holdings: FakePortfolioRepository,
        val portfolios: FakePortfolioCollectionRepository,
        val preferences: FakePreferencesRepository,
        val vm: PortfolioDetailViewModel,
    ) {
        val state get() = vm.state.value
        val backEvents = mutableListOf<Unit>()
    }

    private fun setup(
        portfolioId: String?,
        portfolios: List<Portfolio> = listOf(jubilacion, hijos, ocio),
        holdings: List<PortfolioHolding> = listOf(a, b, c),
        selected: String? = portfolioId,
    ): Setup {
        val holdingsRepository = FakePortfolioRepository(holdings)
        val collection = FakePortfolioCollectionRepository(portfolios, holdingsRepository)
        val preferences = FakePreferencesRepository(selectedPortfolioId = selected)
        val vm = PortfolioDetailViewModel(
            portfolioId = portfolioId,
            portfolios = collection,
            allocationTargets = FakeAllocationTargetRepository(collection),
            portfolioRepository = holdingsRepository,
            movementRepository = FakeMovementRepository(holdingsRepository),
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            fxHistoryRepository = FakeFxHistoryRepository(FxHistory(emptyMap())),
            historyRepository = FakeInstrumentHistoryRepository(),
            preferences = preferences,
            clock = clock,
            zoneProvider = { TimeZone.UTC },
            computationDispatcher = dispatcher,
        )
        return Setup(holdingsRepository, collection, preferences, vm)
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch {
            launch { s.vm.state.collect {} }
            launch { s.vm.navigateBack.collect { s.backEvents += it } }
        }
        advanceUntilIdle()
        return job
    }

    // ---- what can be managed -------------------------------------------------------------------------------------

    @Test
    fun `the detail of one portfolio offers to manage it, and to delete it while there are others`() =
        runTest(dispatcher) {
            val s = setup("pf-hijos")
            val job = open(s)

            val management = assertNotNull(s.state.management)
            assertNull(management.deleteBlockedReason)
            assertEquals("Hijos", s.state.header.title)
            job.cancel()
        }

    @Test
    fun `the detail of all the portfolios offers nothing to manage`() = runTest(dispatcher) {
        val s = setup(portfolioId = null, selected = null)
        val job = open(s)

        assertNull(s.state.management)
        job.cancel()
    }

    @Test
    fun `with a single portfolio its detail manages it but cannot delete it`() = runTest(dispatcher) {
        val shownForAll = setup(null, portfolios = listOf(jubilacion), holdings = listOf(a), selected = null)
        val allJob = open(shownForAll)
        assertEquals("Necesitas al menos una cartera.", shownForAll.state.management?.deleteBlockedReason)
        allJob.cancel()

        val shownForOne = setup("pf-jub", portfolios = listOf(jubilacion), holdings = listOf(a))
        val oneJob = open(shownForOne)
        assertEquals("Necesitas al menos una cartera.", shownForOne.state.management?.deleteBlockedReason)
        oneJob.cancel()
    }

    @Test
    fun `renaming or deleting from the detail of all the portfolios opens nothing`() = runTest(dispatcher) {
        val s = setup(portfolioId = null, selected = null)
        val job = open(s)

        s.vm.onRenamePortfolio()
        s.vm.onDeletePortfolio()
        advanceUntilIdle()

        assertNull(s.state.dialog)
        job.cancel()
    }

    // ---- rename ---------------------------------------------------------------------------------------------------

    @Test
    fun `renaming starts from the current name and saves the new one`() = runTest(dispatcher) {
        val s = setup("pf-hijos")
        val job = open(s)

        s.vm.onRenamePortfolio()
        advanceUntilIdle()
        val dialog = assertIs<PortfolioDialogUi.Name>(s.state.dialog)
        assertEquals("Renombrar cartera", dialog.title)
        assertEquals("Guardar", dialog.confirmLabel)
        assertEquals("Hijos", dialog.name)

        s.vm.onDialogNameChange(" Los peques ")
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        assertNull(s.state.dialog)
        assertEquals("Los peques", s.portfolios.current.single { it.id == "pf-hijos" }.name)
        assertEquals("Los peques", s.state.header.title)
        assertTrue(s.backEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `renaming to the name of another portfolio is reported and nothing changes`() = runTest(dispatcher) {
        val s = setup("pf-hijos")
        val job = open(s)
        s.vm.onRenamePortfolio()
        advanceUntilIdle()
        s.vm.onDialogNameChange("JUBILACIÓN")

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Name>(s.state.dialog)
        assertEquals("Ya tienes una cartera con ese nombre.", dialog.error)
        assertEquals("Hijos", s.portfolios.current.single { it.id == "pf-hijos" }.name)
        job.cancel()
    }

    @Test
    fun `a blank or too long new name is reported in the dialog`() = runTest(dispatcher) {
        val s = setup("pf-hijos")
        val job = open(s)
        s.vm.onRenamePortfolio()
        advanceUntilIdle()

        s.vm.onDialogNameChange("   ")
        s.vm.onDialogConfirm()
        advanceUntilIdle()
        assertEquals("Escribe un nombre para la cartera.", assertIs<PortfolioDialogUi.Name>(s.state.dialog).error)

        s.vm.onDialogNameChange("x".repeat(MAX_PORTFOLIO_NAME_LENGTH + 1))
        advanceUntilIdle()
        assertNull(assertIs<PortfolioDialogUi.Name>(s.state.dialog).error)
        s.vm.onDialogConfirm()
        advanceUntilIdle()
        assertEquals(
            "El nombre no puede tener más de $MAX_PORTFOLIO_NAME_LENGTH caracteres.",
            assertIs<PortfolioDialogUi.Name>(s.state.dialog).error,
        )
        job.cancel()
    }

    @Test
    fun `dismissing the dialog closes it without changing anything`() = runTest(dispatcher) {
        val s = setup("pf-hijos")
        val job = open(s)
        s.vm.onRenamePortfolio()
        advanceUntilIdle()
        s.vm.onDialogNameChange("Otro")

        s.vm.onDialogDismiss()
        advanceUntilIdle()

        assertNull(s.state.dialog)
        assertEquals("Hijos", s.portfolios.current.single { it.id == "pf-hijos" }.name)
        job.cancel()
    }

    // ---- delete ---------------------------------------------------------------------------------------------------

    @Test
    fun `deleting an empty portfolio asks for a plain confirmation, forgets the selection and goes back`() =
        runTest(dispatcher) {
            val s = setup("pf-ocio")
            val job = open(s)

            s.vm.onDeletePortfolio()
            advanceUntilIdle()
            val dialog = assertIs<PortfolioDialogUi.Delete>(s.state.dialog)
            assertEquals("Eliminar «Ocio»", dialog.title)
            assertEquals("Se eliminará la cartera.", dialog.message)
            assertEquals("Eliminar", dialog.confirmLabel)
            assertTrue(dialog.targets.isEmpty())
            assertNull(dialog.targetId)

            s.vm.onDialogConfirm()
            advanceUntilIdle()

            assertNull(s.state.dialog)
            assertEquals(listOf("pf-jub", "pf-hijos"), s.portfolios.current.map { it.id })
            assertNull(s.preferences.observeSelectedPortfolioId().value)
            assertEquals(1, s.backEvents.size)
            job.cancel()
        }

    @Test
    fun `deleting a portfolio with holdings asks where they go, the first other one by default`() =
        runTest(dispatcher) {
            val s = setup("pf-jub")
            val job = open(s)

            s.vm.onDeletePortfolio()
            advanceUntilIdle()

            val dialog = assertIs<PortfolioDialogUi.Delete>(s.state.dialog)
            assertEquals("Eliminar «Jubilación»", dialog.title)
            assertEquals("Sus 2 productos pasarán a:", dialog.message)
            assertEquals("Mover y eliminar", dialog.confirmLabel)
            assertEquals(listOf("Hijos", "Ocio"), dialog.targets.map { it.name })
            assertEquals("pf-hijos", dialog.targetId)
            job.cancel()
        }

    @Test
    fun `a single product reads in the singular`() = runTest(dispatcher) {
        val s = setup("pf-hijos")
        val job = open(s)

        s.vm.onDeletePortfolio()
        advanceUntilIdle()

        assertEquals("Su producto pasará a:", assertIs<PortfolioDialogUi.Delete>(s.state.dialog).message)
        job.cancel()
    }

    @Test
    fun `confirming moves the holdings to the chosen portfolio, deletes the portfolio and goes back`() =
        runTest(dispatcher) {
            val s = setup("pf-jub")
            val job = open(s)
            s.vm.onDeletePortfolio()
            advanceUntilIdle()
            s.vm.onDeleteTargetChange("pf-ocio")
            advanceUntilIdle()
            assertEquals("pf-ocio", assertIs<PortfolioDialogUi.Delete>(s.state.dialog).targetId)

            s.vm.onDialogConfirm()
            advanceUntilIdle()

            assertNull(s.state.dialog)
            assertEquals(listOf("pf-hijos", "pf-ocio"), s.portfolios.current.map { it.id })
            assertEquals(
                mapOf("a" to "pf-ocio", "b" to "pf-ocio", "c" to "pf-hijos"),
                s.holdings.current.associate { it.id to it.portfolioId },
            )
            // Cartera was showing the deleted portfolio: it goes back to all of them.
            assertNull(s.preferences.observeSelectedPortfolioId().value)
            assertEquals(1, s.backEvents.size)
            job.cancel()
        }

    @Test
    fun `deleting a portfolio that Cartera was not showing keeps the selection`() = runTest(dispatcher) {
        val s = setup("pf-ocio", selected = "pf-hijos")
        val job = open(s)

        s.vm.onDeletePortfolio()
        advanceUntilIdle()
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        assertEquals("pf-hijos", s.preferences.observeSelectedPortfolioId().value)
        assertEquals(1, s.backEvents.size)
        job.cancel()
    }

    @Test
    fun `a target that disappears is replaced by the first other portfolio`() = runTest(dispatcher) {
        val s = setup("pf-jub")
        val job = open(s)
        s.vm.onDeletePortfolio()
        advanceUntilIdle()
        s.vm.onDeleteTargetChange("pf-ocio")
        advanceUntilIdle()

        s.portfolios.deletePortfolio("pf-ocio", null)
        advanceUntilIdle()

        assertEquals("pf-hijos", assertIs<PortfolioDialogUi.Delete>(s.state.dialog).targetId)
        job.cancel()
    }

    @Test
    fun `the last portfolio cannot be deleted`() = runTest(dispatcher) {
        val s = setup("pf-jub", portfolios = listOf(jubilacion), holdings = listOf(a))
        val job = open(s)

        s.vm.onDeletePortfolio()
        advanceUntilIdle()

        assertNull(s.state.dialog)
        assertEquals(1, s.portfolios.current.size)
        assertTrue(s.backEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `a delete refused by the repository is reported in the dialog and does not go back`() = runTest(dispatcher) {
        val s = setup("pf-ocio")
        val job = open(s)
        s.vm.onDeletePortfolio()
        advanceUntilIdle()

        // Everything else disappears while the dialog is open: the only portfolio left cannot go.
        s.portfolios.deletePortfolio("pf-jub", "pf-hijos")
        s.portfolios.deletePortfolio("pf-hijos", "pf-ocio")
        advanceUntilIdle()
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Delete>(s.state.dialog)
        assertEquals("Necesitas al menos una cartera.", dialog.error)
        assertEquals(1, s.portfolios.current.size)
        assertTrue(s.backEvents.isEmpty())
        job.cancel()
    }
}
