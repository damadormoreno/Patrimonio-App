package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalHoldingNotFoundException
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import com.denebapps.patrimonio.testing.manualTestHolding
import com.denebapps.patrimonio.testing.quotedTestHolding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The goal form's three independent link lists (accounts, portfolio products, groups) and the copy around them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavingsGoalsViewModelMixedLinksTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun account(id: String, name: String, minor: Long, currency: Currency = Currency.EUR) =
        Asset(id, Asset.AssetGroup.BANK, name, null, CurrencyAmount(Money(minor), currency))

    private val cuenta = account("a1", "Cuenta", 100_00)
    private val ahorro = account("a2", "Ahorro", 50_00)

    /** 30,00 EUR. */
    private val fund = manualTestHolding("fund", 30_00)

    /** A quoted product with no price yet. */
    private val bond = quotedTestHolding("bond", units = 10, price = null)

    private fun group(id: String, name: String, assetIds: Set<String>, holdingIds: Set<String> = emptySet()) =
        AccountGroup(
            id = id,
            name = name,
            showBalance = true,
            sortOrder = 1,
            memberAssetIds = assetIds,
            memberHoldingIds = holdingIds,
        )

    /** «Casa»: the account plus the fund. */
    private val casa = group("g1", "Casa", setOf("a1"), setOf("fund"))
    private val viaje = group("g2", "Viaje", setOf("a2"))

    private fun goal(
        id: String,
        assets: Set<String> = emptySet(),
        holdings: Set<String> = emptySet(),
        groups: Set<String> = emptySet(),
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = "Goal $id",
        target = CurrencyAmount(Money(1_000_00), Currency.EUR),
        targetDate = null,
        linkedAssetIds = assets,
        lifecycle = lifecycle,
        progress = Money.ZERO,
        linkedHoldingIds = holdings,
        linkedGroupIds = groups,
    )

    private fun viewModel(
        goals: FakeSavingsGoalRepository = FakeSavingsGoalRepository(),
        assets: List<Asset> = listOf(cuenta, ahorro),
        holdings: List<PortfolioHolding> = listOf(fund, bond),
        groups: List<AccountGroup> = listOf(casa, viaje),
        portfolios: List<Portfolio> = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
        fx: FxRates = FxRates(mapOf(Currency.USD to 500_000L)),
    ) = SavingsGoalsViewModel(
        savingsGoalRepository = goals,
        assetRepository = FakeAssetRepository(assets),
        accountGroupRepository = FakeAccountGroupRepository(listOf(AccountGroup.allAccounts()) + groups),
        portfolioRepository = FakePortfolioRepository(holdings),
        portfolios = FakePortfolioCollectionRepository(portfolios),
        fxRepository = FakeFxRepository(fx),
        clock = fixedClock("2026-10-09T10:00:00Z"),
        zoneProvider = { TimeZone.UTC },
    )

    private fun knowingEverything(initial: List<SavingsGoal> = emptyList()) = FakeSavingsGoalRepository(
        initial,
        knownAssetIds = setOf("a1", "a2"),
        persistedGroupIds = setOf("g1", "g2"),
        knownHoldingIds = setOf("fund", "bond"),
    )

    @Test
    fun `accounts products and groups toggle independently and combine`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalAssetToggle("a1")
        vm.onNewGoalHoldingToggle("fund")
        vm.onNewGoalGroupToggle("g2")
        advanceUntilIdle()
        assertEquals(setOf("a1"), vm.state.value.newGoalLinkedAssetIds)
        assertEquals(setOf("fund"), vm.state.value.newGoalLinkedHoldingIds)
        assertEquals(setOf("g2"), vm.state.value.newGoalLinkedGroupIds)

        vm.onNewGoalHoldingToggle("bond")
        vm.onNewGoalAssetToggle("a1")
        advanceUntilIdle()
        assertEquals(emptySet(), vm.state.value.newGoalLinkedAssetIds)
        assertEquals(setOf("fund", "bond"), vm.state.value.newGoalLinkedHoldingIds)
        assertEquals(setOf("g2"), vm.state.value.newGoalLinkedGroupIds)

        vm.onNewGoalGroupToggle("g1")
        vm.onNewGoalHoldingToggle("fund")
        advanceUntilIdle()
        assertEquals(setOf("bond"), vm.state.value.newGoalLinkedHoldingIds)
        assertEquals(setOf("g1", "g2"), vm.state.value.newGoalLinkedGroupIds)
        job.cancel()
    }

    @Test
    fun `switching currency keeps accounts products and groups`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalAssetToggle("a1")
        vm.onNewGoalHoldingToggle("fund")
        vm.onNewGoalGroupToggle("g2")
        vm.onNewGoalCurrencyChange(Currency.USD)
        advanceUntilIdle()

        assertEquals(setOf("a1"), vm.state.value.newGoalLinkedAssetIds)
        assertEquals(setOf("fund"), vm.state.value.newGoalLinkedHoldingIds)
        assertEquals(setOf("g2"), vm.state.value.newGoalLinkedGroupIds)
        job.cancel()
    }

    @Test
    fun `editing loads the accounts products and groups of the goal`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = knowingEverything(listOf(goal("goal-1", setOf("a1"), setOf("bond"), setOf("g2")))),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onStartEditing("goal-1")
        advanceUntilIdle()

        assertEquals(setOf("a1"), vm.state.value.newGoalLinkedAssetIds)
        assertEquals(setOf("bond"), vm.state.value.newGoalLinkedHoldingIds)
        assertEquals(setOf("g2"), vm.state.value.newGoalLinkedGroupIds)
        job.cancel()
    }

    @Test
    fun `saving passes the three sets to the repository and the row carries them`() = runTest(dispatcher) {
        val vm = viewModel(goals = knowingEverything())
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalNameChange("Casa nueva")
        vm.onNewGoalTargetChange("1000")
        vm.onNewGoalAssetToggle("a1")
        vm.onNewGoalAssetToggle("a2")
        vm.onNewGoalHoldingToggle("fund")
        vm.onNewGoalGroupToggle("g2")
        vm.onSaveNewGoal()
        advanceUntilIdle()

        val row = vm.state.value.goals.single()
        assertEquals(setOf("a1", "a2"), row.linkedAssetIds)
        assertEquals(setOf("fund"), row.linkedHoldingIds)
        assertEquals(setOf("g2"), row.linkedGroupIds)
        assertTrue(row.tracksBalance)
        job.cancel()
    }

    @Test
    fun `the summary counts each kind and totals what is followed once`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertNull(vm.state.value.newGoalLinkSummary)

        // The account is also inside «Casa» (account + fund): 100 + 30 and not 100 + 100 + 30.
        vm.onNewGoalAssetToggle("a1")
        vm.onNewGoalGroupToggle("g1")
        advanceUntilIdle()

        val summary = assertNotNull(vm.state.value.newGoalLinkSummary)
        assertEquals(GoalLinkSummaryUi(1, 0, 1, Money(130_00), Currency.EUR, overlap = true), summary)
        assertEquals(
            "Sigue 1 cuenta y 1 grupo · total 130,00 € (sin contar dos veces lo que se repite)",
            summary.text,
        )
        job.cancel()
    }

    @Test
    fun `a product picked directly and through a group counts once`() = runTest(dispatcher) {
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalHoldingToggle("fund")
        vm.onNewGoalHoldingToggle("bond")
        vm.onNewGoalGroupToggle("g1")
        advanceUntilIdle()

        // fund (30) once, the unpriced bond adds nothing, plus the group's account (100).
        assertEquals(
            GoalLinkSummaryUi(0, 2, 1, Money(130_00), Currency.EUR, overlap = true),
            vm.state.value.newGoalLinkSummary,
        )
        job.cancel()
    }

    @Test
    fun `the summary has no overlap note when nothing repeats and converts to the goal currency`() =
        runTest(dispatcher) {
            val vm = viewModel(assets = listOf(cuenta, ahorro, account("usd", "Dólares", 40_00, Currency.USD)))
            val job = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onNewGoalAssetToggle("a2")
            vm.onNewGoalAssetToggle("usd")
            vm.onNewGoalGroupToggle("g1")
            advanceUntilIdle()

            // 50 + (40 USD at 0.5 = 20) + «Casa» (100 + 30).
            val summary = assertNotNull(vm.state.value.newGoalLinkSummary)
            assertEquals(GoalLinkSummaryUi(2, 0, 1, Money(200_00), Currency.EUR, overlap = false), summary)
            assertEquals("Sigue 2 cuentas y 1 grupo · total 200,00 €", summary.text)
            job.cancel()
        }

    @Test
    fun `the summary total is unavailable when a needed exchange rate is missing`() = runTest(dispatcher) {
        val vm = viewModel(
            assets = listOf(cuenta, account("usd", "Dólares", 40_00, Currency.USD)),
            fx = FxRates(mapOf(Currency.USD to 0L)),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onNewGoalAssetToggle("usd")
        advanceUntilIdle()

        val summary = assertNotNull(vm.state.value.newGoalLinkSummary)
        assertNull(summary.total)
        assertEquals("Sigue 1 cuenta · sin tipo de cambio para calcular el total", summary.text)
        job.cancel()
    }

    @Test
    fun `the summary text pluralises each kind and leaves out the empty ones`() {
        assertEquals(
            "Sigue 2 cuentas, 1 producto y 3 grupos · total 123,45 €",
            GoalLinkSummaryUi(2, 1, 3, Money(123_45), Currency.EUR, overlap = false).text,
        )
        assertEquals(
            "Sigue 2 productos · total 5,00 $",
            GoalLinkSummaryUi(0, 2, 0, Money(5_00), Currency.USD, overlap = false).text,
        )
        assertEquals(
            "Sigue 1 grupo · sin tipo de cambio para calcular el total",
            GoalLinkSummaryUi(0, 0, 1, null, Currency.EUR, overlap = true).text,
        )
    }

    @Test
    fun `usage tags on products and groups leave out the goal being edited`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = knowingEverything(
                listOf(
                    goal("mine", holdings = setOf("fund"), groups = setOf("g2")),
                    goal("direct", holdings = setOf("fund")),
                    goal("via-group", groups = setOf("g1")),
                    goal("closed", holdings = setOf("fund"), lifecycle = SavingsGoalLifecycle.CLOSED),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onStartEditing("mine")
        advanceUntilIdle()

        val products = vm.state.value.linkableHoldings.associateBy { it.id }
        assertEquals(listOf("Goal direct", "Goal via-group"), products.getValue("fund").goalNames)
        assertEquals(listOf("Casa"), products.getValue("fund").groupNames)
        assertEquals(emptyList(), products.getValue("bond").goalNames)
        val groups = vm.state.value.linkableGroups.associateBy { it.id }
        assertEquals(listOf("Goal via-group"), groups.getValue("g1").goalNames)
        assertEquals(emptyList(), groups.getValue("g2").goalNames)
        job.cancel()
    }

    @Test
    fun `a list with nothing to pick is empty so the form hides its section`() = runTest(dispatcher) {
        val vm = viewModel(assets = emptyList(), holdings = emptyList(), groups = emptyList())
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.linkableAssets)
        assertEquals(emptyList(), vm.state.value.linkableHoldings)
        // The builtin «Todas las cuentas» group is never offered.
        assertEquals(emptyList(), vm.state.value.linkableGroups)
        job.cancel()
    }

    @Test
    fun `products show their EUR value or sin precio and the portfolio only when there are several`() =
        runTest(dispatcher) {
            val holdings = listOf(fund, bond, manualTestHolding("usd", 40_00, Currency.USD))
            val single = viewModel(holdings = holdings, groups = emptyList())
            val several = viewModel(
                holdings = holdings,
                groups = emptyList(),
                portfolios = listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio("p2", "Retiro", 1)),
            )
            val singleJob = launch { single.state.collect {} }
            val severalJob = launch { several.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                listOf(
                    LinkableHoldingUi("fund", "Holding fund", "30,00 €"),
                    LinkableHoldingUi("bond", "Fund bond", "sin precio"),
                    // 40 USD at 0.5.
                    LinkableHoldingUi("usd", "Holding usd", "20,00 €"),
                ),
                single.state.value.linkableHoldings,
            )
            assertEquals(
                listOf("Mi cartera · 30,00 €", "Mi cartera · sin precio", "Mi cartera · 20,00 €"),
                several.state.value.linkableHoldings.map { it.subtitle },
            )
            singleJob.cancel()
            severalJob.cancel()
        }

    @Test
    fun `a goal following a mix is captioned with the names of what it follows`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = knowingEverything(
                listOf(
                    goal("few", setOf("a1"), setOf("fund"), setOf("g2")),
                    goal("many", setOf("a1", "a2"), setOf("fund", "bond"), setOf("g1")),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val rows = vm.state.value.goals.associateBy { it.id }
        assertEquals("«Cuenta», «Holding fund» y «Viaje»", rows.getValue("few").linkedTargetLabel)
        assertEquals(
            "Sigue el saldo de «Cuenta», «Holding fund» y «Viaje»",
            trackedBalanceCaption(rows.getValue("few")),
        )
        assertEquals("2 cuentas, 2 productos y 1 grupo", rows.getValue("many").linkedTargetLabel)
        assertEquals(
            "Sigue el saldo de 2 cuentas, 2 productos y 1 grupo",
            trackedBalanceCaption(rows.getValue("many")),
        )
        job.cancel()
    }

    @Test
    fun `link labels name up to three targets of any kind and count the rest by kind`() {
        assertNull(linkedTargetLabel(emptyList(), emptyList(), emptyList()))
        assertEquals("«Casa»", linkedTargetLabel(emptyList(), emptyList(), listOf("Casa")))
        assertEquals(
            "«Ahorro», «Casa» y «Fondo»",
            linkedTargetLabel(listOf("Ahorro"), listOf("Fondo"), listOf("Casa")),
        )
        assertEquals("4 cuentas", linkedTargetLabel(listOf("A", "B", "C", "D")))
        assertEquals("4 productos", linkedTargetLabel(emptyList(), listOf("A", "B", "C", "D")))
        assertEquals("1 cuenta y 3 grupos", linkedTargetLabel(listOf("A"), emptyList(), listOf("X", "Y", "Z")))
        assertEquals(
            "2 cuentas, 1 producto y 1 grupo",
            linkedTargetLabel(listOf("A", "B"), listOf("P"), listOf("G")),
        )
    }

    @Test
    fun `sharing notices name products and groups`() = runTest(dispatcher) {
        val vm = viewModel(
            goals = knowingEverything(
                listOf(
                    goal("p-1", holdings = setOf("bond")),
                    goal("p-2", holdings = setOf("bond")),
                    goal("g-1", groups = setOf("g2")),
                    goal("g-2", groups = setOf("g2")),
                    goal("mix-1", assets = setOf("a1"), holdings = setOf("fund")),
                    goal("mix-2", groups = setOf("g1")),
                ),
            ),
        )
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val notices = vm.state.value.sharedBalanceNotices.associateBy({ it.goalNames }, { it.targetLabel })
        assertEquals("«Fund bond»", notices.getValue(listOf("Goal p-1", "Goal p-2")))
        assertEquals("«Viaje»", notices.getValue(listOf("Goal g-1", "Goal g-2")))
        // One goal follows the account and the fund directly, the other through «Casa» that holds both.
        assertEquals("«Cuenta» y «Holding fund»", notices.getValue(listOf("Goal mix-1", "Goal mix-2")))
        job.cancel()
    }

    @Test
    fun `saving with a missing product shows its message and stays on the form`() = runTest(dispatcher) {
        val vm = viewModel(goals = FakeSavingsGoalRepository())
        val job = launch { vm.state.collect {} }
        var navigations = 0
        val navigationJob = launch { vm.navigateBack.collect { navigations++ } }
        advanceUntilIdle()

        vm.onNewGoalNameChange("Casa")
        vm.onNewGoalTargetChange("100")
        vm.onNewGoalHoldingToggle("fund")
        vm.onSaveNewGoal()
        advanceUntilIdle()

        assertEquals(
            "Uno de los productos elegidos ya no existe. Quítalo de la selección.",
            vm.state.value.errorMessage,
        )
        assertEquals(0, navigations)
        job.cancel()
        navigationJob.cancel()
    }

    @Test
    fun `repository link errors map to Spanish messages`() {
        assertEquals(
            "Uno de los productos elegidos ya no existe. Quítalo de la selección.",
            goalSaveErrorMessage(SavingsGoalHoldingNotFoundException("x"), editing = false),
        )
        assertEquals(
            "Una de las cuentas elegidas ya no existe. Quítala de la selección.",
            goalSaveErrorMessage(SavingsGoalAssetNotFoundException("x"), editing = true),
        )
        assertEquals(
            "Uno de los grupos elegidos ya no existe. Quítalo de la selección.",
            goalSaveErrorMessage(SavingsGoalGroupNotFoundException("x"), editing = false),
        )
        assertEquals(
            "«Todas las cuentas» no se puede seguir. Elige cuentas, productos u otros grupos.",
            goalSaveErrorMessage(SavingsGoalBuiltinGroupException(), editing = false),
        )
        assertEquals(
            "No se pudo crear la meta. Revisa el importe y la divisa.",
            goalSaveErrorMessage(IllegalStateException("boom"), editing = false),
        )
        assertEquals("No se pudo guardar la meta.", goalSaveErrorMessage(IllegalStateException("boom"), editing = true))
    }
}
