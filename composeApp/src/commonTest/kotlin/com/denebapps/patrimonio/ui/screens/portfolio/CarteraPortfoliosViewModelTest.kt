package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.MAX_PORTFOLIO_NAME_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.testing.FakeFxRepository
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
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The portfolio-aware half of the Cartera tab: selection, filtering, selector flags and portfolio management. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarteraPortfoliosViewModelTest {
    private val dispatcher = StandardTestDispatcher()

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
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(euros * 100), Currency.EUR), 0L),
    )

    // Jubilación: 1.000 + 2.000; Hijos: 500; Ocio: empty.
    private val a = manual("a", "pf-jub", 1_000)
    private val b = manual("b", "pf-jub", 2_000)
    private val c = manual("c", "pf-hijos", 500)

    private class Setup(
        val holdings: FakePortfolioRepository,
        val portfolios: FakePortfolioCollectionRepository,
        val preferences: FakePreferencesRepository,
        val vm: CarteraViewModel,
    )

    private fun setup(
        holdings: List<PortfolioHolding> = listOf(a, b, c),
        portfolios: List<Portfolio> = listOf(jubilacion, hijos, ocio),
        selected: String? = null,
    ): Setup {
        val holdingsRepository = FakePortfolioRepository(holdings)
        val collection = FakePortfolioCollectionRepository(portfolios, holdingsRepository)
        val preferences = FakePreferencesRepository(selectedPortfolioId = selected)
        val vm = CarteraViewModel(
            portfolioRepository = holdingsRepository,
            portfolios = collection,
            preferences = preferences,
            fxRepository = FakeFxRepository(FxRates(emptyMap())),
            zoneProvider = { TimeZone.UTC },
        )
        return Setup(holdingsRepository, collection, preferences, vm)
    }

    private fun TestScope.open(s: Setup): Job {
        val job = launch { s.vm.state.collect {} }
        advanceUntilIdle()
        return job
    }

    // ---- selection and filtering -------------------------------------------------------------------------------

    @Test
    fun `with nothing remembered the tab shows every portfolio`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val state = s.vm.state.value
        assertNull(state.selectedPortfolioId)
        assertEquals("Todas", state.scopeLabel)
        assertEquals(listOf("a", "b", "c"), state.rows.map { it.id })
        assertEquals(Money(350_000), state.total)
        assertEquals("3 productos", state.countLabel)
        job.cancel()
    }

    @Test
    fun `the remembered portfolio filters the list, the count and the total`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)

        val state = s.vm.state.value
        assertEquals("pf-jub", state.selectedPortfolioId)
        assertEquals("Jubilación", state.scopeLabel)
        assertEquals(listOf("a", "b"), state.rows.map { it.id })
        assertEquals(Money(300_000), state.total)
        assertEquals("2 productos", state.countLabel)
        assertFalse(state.isEmpty)
        job.cancel()
    }

    @Test
    fun `a selected portfolio without holdings is empty with a zero total`() = runTest(dispatcher) {
        val s = setup(selected = "pf-ocio")
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.isEmpty)
        assertEquals(Money.ZERO, state.total)
        assertEquals("Sin productos", state.countLabel)
        job.cancel()
    }

    @Test
    fun `a remembered portfolio that no longer exists falls back to every portfolio`() = runTest(dispatcher) {
        val s = setup(selected = "pf-deleted")
        val job = open(s)

        val state = s.vm.state.value
        assertNull(state.selectedPortfolioId)
        assertEquals("Todas", state.scopeLabel)
        assertEquals(3, state.rows.size)
        job.cancel()
    }

    @Test
    fun `selecting a portfolio remembers it and selecting all forgets it`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onSelectPortfolio("pf-hijos")
        advanceUntilIdle()
        assertEquals("pf-hijos", s.preferences.observeSelectedPortfolioId().value)
        assertEquals(listOf("c"), s.vm.state.value.rows.map { it.id })
        assertEquals(Money(50_000), s.vm.state.value.total)

        s.vm.onSelectPortfolio(null)
        advanceUntilIdle()
        assertNull(s.preferences.observeSelectedPortfolioId().value)
        assertEquals(3, s.vm.state.value.rows.size)
        job.cancel()
    }

    @Test
    fun `the totals of the portfolios add up to the total of all`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val all = s.vm.state.value.total.minorUnits
        val perPortfolio = listOf("pf-jub", "pf-hijos", "pf-ocio").sumOf { id ->
            s.vm.onSelectPortfolio(id)
            advanceUntilIdle()
            s.vm.state.value.total.minorUnits
        }
        assertEquals(all, perPortfolio)
        job.cancel()
    }

    @Test
    fun `the list follows a holding moved to another portfolio`() = runTest(dispatcher) {
        val s = setup(selected = "pf-hijos")
        val job = open(s)

        s.portfolios.moveHolding("a", "pf-hijos")
        advanceUntilIdle()

        assertEquals(listOf("a", "c"), s.vm.state.value.rows.map { it.id })
        job.cancel()
    }

    // ---- selector flags ------------------------------------------------------------------------------------------

    @Test
    fun `with several portfolios the selector lists them in repository order`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        val state = s.vm.state.value
        assertTrue(state.showSelector)
        assertEquals(listOf("Jubilación", "Hijos", "Ocio"), state.portfolios.map { it.name })
        assertEquals(listOf("pf-jub", "pf-hijos", "pf-ocio"), state.portfolios.map { it.id })
        job.cancel()
    }

    @Test
    fun `with a single portfolio there is no selector and nothing but that portfolio to manage`() =
        runTest(dispatcher) {
            val s = setup(portfolios = listOf(jubilacion), holdings = listOf(a, b), selected = "pf-hijos")
            val job = open(s)

            val state = s.vm.state.value
            assertFalse(state.showSelector)
            assertNull(state.selectedPortfolioId)
            assertNull(state.scopeLabel)
            assertEquals("pf-jub", state.managedPortfolioId)
            assertEquals(2, state.rows.size)
            assertEquals("Necesitas al menos una cartera.", state.deleteBlockedReason)
            job.cancel()
        }

    @Test
    fun `there is nothing to manage while all the portfolios are shown`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertNull(s.vm.state.value.managedPortfolioId)
        assertNull(s.vm.state.value.deleteBlockedReason)

        s.vm.onSelectPortfolio("pf-hijos")
        advanceUntilIdle()
        assertEquals("pf-hijos", s.vm.state.value.managedPortfolioId)
        assertNull(s.vm.state.value.deleteBlockedReason)
        job.cancel()
    }

    @Test
    fun `with all portfolios shown each row names its portfolio`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        assertEquals(listOf("Jubilación", "Jubilación", "Hijos"), s.vm.state.value.rows.map { it.portfolioName })
        job.cancel()
    }

    @Test
    fun `rows of a selected portfolio or of a single portfolio do not repeat its name`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)
        assertTrue(s.vm.state.value.rows.all { it.portfolioName == null })
        job.cancel()

        val single = setup(portfolios = listOf(jubilacion), holdings = listOf(a))
        val singleJob = open(single)
        assertTrue(single.vm.state.value.rows.all { it.portfolioName == null })
        singleJob.cancel()
    }

    // ---- create ---------------------------------------------------------------------------------------------------

    @Test
    fun `creating a portfolio selects the new one`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onNewPortfolio()
        advanceUntilIdle()
        val dialog = assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog)
        assertEquals("Nueva cartera", dialog.title)
        assertEquals("", dialog.name)
        assertNull(dialog.error)

        s.vm.onDialogNameChange("  Viajes ")
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val created = s.portfolios.current.single { it.name == "Viajes" }
        assertNull(s.vm.state.value.dialog)
        assertEquals(created.id, s.preferences.observeSelectedPortfolioId().value)
        assertEquals(created.id, s.vm.state.value.selectedPortfolioId)
        assertEquals("Viajes", s.vm.state.value.scopeLabel)
        assertTrue(s.vm.state.value.rows.isEmpty())
        job.cancel()
    }

    @Test
    fun `creating from a single portfolio reveals the selector with the new one selected`() = runTest(dispatcher) {
        val s = setup(portfolios = listOf(jubilacion), holdings = listOf(a))
        val job = open(s)

        s.vm.onNewPortfolio()
        advanceUntilIdle()
        s.vm.onDialogNameChange("Hijos")
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val state = s.vm.state.value
        assertTrue(state.showSelector)
        assertEquals("Hijos", state.scopeLabel)
        assertEquals(listOf("Jubilación", "Hijos"), state.portfolios.map { it.name })
        job.cancel()
    }

    @Test
    fun `a duplicate name is reported in the dialog and nothing is created`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onNewPortfolio()
        advanceUntilIdle()
        s.vm.onDialogNameChange("hijos")

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog)
        assertEquals("Ya tienes una cartera con ese nombre.", dialog.error)
        assertEquals("hijos", dialog.name)
        assertEquals(3, s.portfolios.current.size)
        job.cancel()
    }

    @Test
    fun `a blank name is reported in the dialog`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onNewPortfolio()
        advanceUntilIdle()
        s.vm.onDialogNameChange("   ")

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog)
        assertEquals("Escribe un nombre para la cartera.", dialog.error)
        job.cancel()
    }

    @Test
    fun `a name that is too long is reported in the dialog`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onNewPortfolio()
        advanceUntilIdle()
        s.vm.onDialogNameChange("x".repeat(MAX_PORTFOLIO_NAME_LENGTH + 1))

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog)
        assertEquals("El nombre no puede tener más de $MAX_PORTFOLIO_NAME_LENGTH caracteres.", dialog.error)
        job.cancel()
    }

    @Test
    fun `typing after an error clears it and dismissing closes the dialog`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onNewPortfolio()
        advanceUntilIdle()
        s.vm.onDialogConfirm()
        advanceUntilIdle()
        assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog).also { assertTrue(it.error != null) }

        s.vm.onDialogNameChange("Viajes")
        advanceUntilIdle()
        assertNull(assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog).error)

        s.vm.onDialogDismiss()
        advanceUntilIdle()
        assertNull(s.vm.state.value.dialog)
        job.cancel()
    }

    // ---- rename ---------------------------------------------------------------------------------------------------

    @Test
    fun `renaming starts from the current name and saves the new one`() = runTest(dispatcher) {
        val s = setup(selected = "pf-hijos")
        val job = open(s)

        s.vm.onRenamePortfolio("pf-hijos")
        advanceUntilIdle()
        val dialog = assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog)
        assertEquals("Renombrar cartera", dialog.title)
        assertEquals("Hijos", dialog.name)

        s.vm.onDialogNameChange(" Los peques ")
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        assertNull(s.vm.state.value.dialog)
        assertEquals("Los peques", s.portfolios.current.single { it.id == "pf-hijos" }.name)
        assertEquals("Los peques", s.vm.state.value.scopeLabel)
        job.cancel()
    }

    @Test
    fun `renaming to the name of another portfolio is reported`() = runTest(dispatcher) {
        val s = setup(selected = "pf-hijos")
        val job = open(s)
        s.vm.onRenamePortfolio("pf-hijos")
        advanceUntilIdle()
        s.vm.onDialogNameChange("JUBILACIÓN")

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Name>(s.vm.state.value.dialog)
        assertEquals("Ya tienes una cartera con ese nombre.", dialog.error)
        assertEquals("Hijos", s.portfolios.current.single { it.id == "pf-hijos" }.name)
        job.cancel()
    }

    @Test
    fun `renaming a portfolio that does not exist opens nothing`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onRenamePortfolio("pf-deleted")
        advanceUntilIdle()

        assertNull(s.vm.state.value.dialog)
        job.cancel()
    }

    // ---- delete ---------------------------------------------------------------------------------------------------

    @Test
    fun `deleting an empty portfolio asks for a plain confirmation`() = runTest(dispatcher) {
        val s = setup(selected = "pf-ocio")
        val job = open(s)

        s.vm.onDeletePortfolio("pf-ocio")
        advanceUntilIdle()
        val dialog = assertIs<PortfolioDialogUi.Delete>(s.vm.state.value.dialog)
        assertEquals("Eliminar «Ocio»", dialog.title)
        assertEquals("Se eliminará la cartera.", dialog.message)
        assertEquals("Eliminar", dialog.confirmLabel)
        assertTrue(dialog.targets.isEmpty())
        assertNull(dialog.targetId)

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        assertNull(s.vm.state.value.dialog)
        assertEquals(listOf("pf-jub", "pf-hijos"), s.portfolios.current.map { it.id })
        assertNull(s.vm.state.value.selectedPortfolioId)
        assertNull(s.preferences.observeSelectedPortfolioId().value)
        job.cancel()
    }

    @Test
    fun `deleting a portfolio with holdings asks where they go, the first other one by default`() =
        runTest(dispatcher) {
            val s = setup(selected = "pf-jub")
            val job = open(s)

            s.vm.onDeletePortfolio("pf-jub")
            advanceUntilIdle()

            val dialog = assertIs<PortfolioDialogUi.Delete>(s.vm.state.value.dialog)
            assertEquals("Eliminar «Jubilación»", dialog.title)
            assertEquals("Sus 2 productos pasarán a:", dialog.message)
            assertEquals("Mover y eliminar", dialog.confirmLabel)
            assertEquals(listOf("Hijos", "Ocio"), dialog.targets.map { it.name })
            assertEquals("pf-hijos", dialog.targetId)
            job.cancel()
        }

    @Test
    fun `a single product reads in the singular`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)

        s.vm.onDeletePortfolio("pf-hijos")
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Delete>(s.vm.state.value.dialog)
        assertEquals("Su producto pasará a:", dialog.message)
        job.cancel()
    }

    @Test
    fun `confirming moves the holdings to the chosen portfolio and deletes the portfolio`() = runTest(dispatcher) {
        val s = setup(selected = "pf-jub")
        val job = open(s)
        s.vm.onDeletePortfolio("pf-jub")
        advanceUntilIdle()
        s.vm.onDeleteTargetChange("pf-ocio")
        advanceUntilIdle()
        assertEquals("pf-ocio", assertIs<PortfolioDialogUi.Delete>(s.vm.state.value.dialog).targetId)

        s.vm.onDialogConfirm()
        advanceUntilIdle()

        assertNull(s.vm.state.value.dialog)
        assertEquals(listOf("pf-hijos", "pf-ocio"), s.portfolios.current.map { it.id })
        assertEquals(
            mapOf("a" to "pf-ocio", "b" to "pf-ocio", "c" to "pf-hijos"),
            s.holdings.current.associate { it.id to it.portfolioId },
        )
        // The selected portfolio is gone: back to all of them, with nothing lost.
        assertNull(s.vm.state.value.selectedPortfolioId)
        assertEquals(3, s.vm.state.value.rows.size)
        assertEquals(Money(350_000), s.vm.state.value.total)
        job.cancel()
    }

    @Test
    fun `deleting a portfolio that is not selected keeps the selection`() = runTest(dispatcher) {
        val s = setup(selected = "pf-hijos")
        val job = open(s)

        s.vm.onDeletePortfolio("pf-ocio")
        advanceUntilIdle()
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        assertEquals("pf-hijos", s.vm.state.value.selectedPortfolioId)
        assertEquals("pf-hijos", s.preferences.observeSelectedPortfolioId().value)
        job.cancel()
    }

    @Test
    fun `a target that disappears is replaced by the first other portfolio`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onDeletePortfolio("pf-jub")
        advanceUntilIdle()
        s.vm.onDeleteTargetChange("pf-ocio")
        advanceUntilIdle()

        s.portfolios.deletePortfolio("pf-ocio", null)
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Delete>(s.vm.state.value.dialog)
        assertEquals("pf-hijos", dialog.targetId)
        job.cancel()
    }

    @Test
    fun `the last portfolio cannot be deleted`() = runTest(dispatcher) {
        val s = setup(portfolios = listOf(jubilacion), holdings = listOf(a))
        val job = open(s)

        s.vm.onDeletePortfolio("pf-jub")
        advanceUntilIdle()

        assertNull(s.vm.state.value.dialog)
        assertEquals(1, s.portfolios.current.size)
        job.cancel()
    }

    @Test
    fun `a delete refused by the repository is reported in the dialog`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onDeletePortfolio("pf-ocio")
        advanceUntilIdle()

        // Everything else disappears while the dialog is open: the only portfolio left cannot go.
        s.portfolios.deletePortfolio("pf-jub", "pf-hijos")
        s.portfolios.deletePortfolio("pf-hijos", "pf-ocio")
        advanceUntilIdle()
        s.vm.onDialogConfirm()
        advanceUntilIdle()

        val dialog = assertIs<PortfolioDialogUi.Delete>(s.vm.state.value.dialog)
        assertEquals("Necesitas al menos una cartera.", dialog.error)
        assertEquals(1, s.portfolios.current.size)
        job.cancel()
    }

    @Test
    fun `a dialog about a portfolio that disappears closes by itself`() = runTest(dispatcher) {
        val s = setup()
        val job = open(s)
        s.vm.onRenamePortfolio("pf-ocio")
        advanceUntilIdle()

        s.portfolios.deletePortfolio("pf-ocio", null)
        advanceUntilIdle()

        assertNull(s.vm.state.value.dialog)
        job.cancel()
    }
}
