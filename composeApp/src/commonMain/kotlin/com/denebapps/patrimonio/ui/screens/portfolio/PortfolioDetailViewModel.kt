package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.quotedRefs
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.AllocationTargetRepository
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.ui.components.filterAmountInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private const val PORTFOLIO_DETAIL_STOP_TIMEOUT_MS = 5_000L

/** The state of the download of the portfolio's data, kept apart from the data itself. */
private data class PortfolioSyncState(val running: Boolean, val failed: Boolean = false)

/**
 * Detail of one portfolio, or of all of them when [portfolioId] is `null`; everything is computed from the holdings of
 * that scope only. It shows the total, the value of today's holdings at past prices over a selectable range with its
 * returns and risk, the correlation between the quoted products, how the portfolio splits (by type, bank or broker and
 * currency), its average cost and risk, and its largest holding. The figures of the products are those of their price
 * history with TODAY's composition, not the user's gains. The histories, the fundamentals and the exchange rates they
 * need are brought up to date when the screen opens; [onRefresh] forces them. Cached data is always shown, even when
 * an update fails. "Tu rentabilidad" is the user's own: it comes from their movements, for the products of the scope
 * that have any. The calculation runs on [computationDispatcher], off the main thread.
 *
 * "Objetivo de reparto" is about one portfolio (its targets and tolerance live in [allocationTargets]): with all the
 * portfolios shown it asks to pick one, unless there is only one, which is then the one it is about. The editor of the
 * targets and the calculator ("¿Cuánto vas a aportar?") keep their state here; the section is computed from the
 * holdings and the targets alone, apart from the heavy analysis.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioDetailViewModel(
    private val portfolioId: String?,
    private val portfolios: PortfolioCollectionRepository,
    private val allocationTargets: AllocationTargetRepository,
    private val portfolioRepository: PortfolioRepository,
    movementRepository: MovementRepository,
    private val fxRepository: FxRepository,
    private val fxHistoryRepository: FxHistoryRepository,
    private val historyRepository: InstrumentHistoryRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val selectedRange = MutableStateFlow(HistoryRange.ONE_YEAR)

    // Running from the start so the first frame says "updating" and not "no history yet".
    private val sync = MutableStateFlow(PortfolioSyncState(running = true))
    private var syncJob: Job? = null

    /** The holdings of the scope: those of [portfolioId], or all of them. */
    private val scopedHoldings: Flow<List<PortfolioHolding>> =
        portfolioRepository.observeAll().map { it.inPortfolio(portfolioId) }

    /** The title of the screen: the name of the portfolio, "Tu cartera" for all of them. */
    private val title: Flow<String> = portfolios.observePortfolios()
        .map { all -> all.firstOrNull { it.id == portfolioId }?.name ?: ALL_PORTFOLIOS_TITLE }
        .distinctUntilChanged()

    /** The stored history of every quoted holding, by holding id; follows holdings being added or removed. */
    private val histories: Flow<Map<String, List<PricePoint>>> = scopedHoldings
        .map { holdings -> holdings.quotedRefs() }
        .distinctUntilChanged()
        .flatMapLatest { refs ->
            if (refs.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(refs.map { (id, ref) -> historyRepository.observe(ref).map { id to it } }) { it.toMap() }
            }
        }

    private val analysis: Flow<PortfolioAnalysis> = combine(
        scopedHoldings,
        histories,
        fxHistoryRepository.observe(),
        fxRepository.observeRates(),
        movementRepository.observeAllMovements(),
        ::PortfolioInputs,
    )
        .map { analyzePortfolio(it, today()) }
        .flowOn(computationDispatcher)

    // ---- targets -------------------------------------------------------------------------------------------------

    /** The portfolio the targets belong to: the one shown, or the only one when all are shown and there is one. */
    private val targetScope: Flow<TargetScope> = portfolios.observePortfolios()
        .map { all ->
            val own = all.firstOrNull { it.id == portfolioId } ?: all.singleOrNull().takeIf { portfolioId == null }
            TargetScope(own, mustChoose = own == null && portfolioId == null && all.size >= MIN_PORTFOLIOS_TO_CHOOSE)
        }
        .distinctUntilChanged()

    private val currentTargets: Flow<AllocationTargets?> = targetScope.flatMapLatest { scope ->
        scope.portfolio?.let { allocationTargets.observeTargets(it.id) } ?: flowOf(null)
    }

    /** The split by class of the scope; cheap, so apart from [analysis] and available as soon as the data is. */
    private val classSplit: Flow<ClassSplit?> =
        combine(scopedHoldings, fxRepository.observeRates(), ::classSplitOf)

    private val calculatorText = MutableStateFlow("")
    private val editor = MutableStateFlow<TargetsDraft?>(null)

    private val targetsPanel: Flow<TargetsPanel> = combine(
        targetScope,
        classSplit,
        currentTargets,
        calculatorText,
        editor,
    ) { scope, split, targets, calculator, draft ->
        targetsPanel(scope, split, targets, calculator, draft)
    }

    val state: StateFlow<PortfolioDetailUiState> = combine(
        analysis,
        selectedRange,
        sync,
        title,
        targetsPanel,
    ) { data, range, syncState, screenTitle, targetsState ->
        buildState(data.copy(header = data.header.copy(title = screenTitle)), range, syncState, targetsState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(PORTFOLIO_DETAIL_STOP_TIMEOUT_MS),
        initialValue = PortfolioDetailUiState(),
    )

    init {
        startSync(force = false)
    }

    /** Shows [range]; ignored if that window has no data to draw. */
    fun onRangeSelect(range: HistoryRange) {
        if (state.value.evolution?.ranges?.firstOrNull { it.range == range }?.enabled == true) {
            selectedRange.value = range
        }
    }

    /** Downloads the histories, fundamentals and exchange rates now, whatever their age; ignored while one runs. */
    fun onRefresh() {
        startSync(force = true)
    }

    fun onSyncErrorDismiss() {
        sync.value = sync.value.copy(failed = false)
    }

    // ---- targets: calculator and editor --------------------------------------------------------------------------

    /** What the user typed in "¿Cuánto vas a aportar?"; only digits and a decimal comma are kept. */
    fun onCalculatorChange(text: String) {
        calculatorText.value = filterAmountInput(text)
    }

    /** Opens the editor on the targets of the portfolio, or on its current split when it has none; ignored with no portfolio. */
    fun onEditTargets() {
        viewModelScope.launch {
            val portfolio = targetScope.first().portfolio ?: return@launch
            editor.value = initialDraft(
                portfolioId = portfolio.id,
                split = classSplit.first(),
                targets = allocationTargets.observeTargets(portfolio.id).first(),
                portfolioToleranceBp = portfolio.rebalanceToleranceBp,
            )
        }
    }

    /** The class field of [assetClass] now holds [text], kept to what a share can be. */
    fun onTargetChange(assetClass: AssetClass, text: String) {
        editor.update { it?.withText(assetClass, filterPercentInput(text)) }
    }

    /** The tolerance field now holds [text] (points). */
    fun onToleranceChange(text: String) {
        editor.update { it?.withTolerance(filterPercentInput(text)) }
    }

    fun onDismissEditor() {
        editor.value = null
    }

    /** Stores the targets and the tolerance of the editor and closes it; ignored unless they are valid. */
    fun onSaveTargets() {
        val draft = editor.value ?: return
        val targets = draft.targetsBp()
        val tolerance = draft.toleranceBp()
        if (!editorUi(draft).canSave || targets == null || tolerance == null) return
        runEditorAction(draft) { allocationTargets.setTargets(draft.portfolioId, targets, tolerance) }
    }

    /** Removes the targets of the portfolio and closes the editor; ignored when it has none. */
    fun onClearTargets() {
        val draft = editor.value ?: return
        if (!editorUi(draft).canRemove) return
        runEditorAction(draft) { allocationTargets.clearTargets(draft.portfolioId) }
    }

    private fun runEditorAction(draft: TargetsDraft, action: suspend () -> Unit) {
        editor.value = draft.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                action()
                editor.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                editor.update { it?.copy(saving = false, error = targetsSaveErrorMessage(failure)) }
            }
        }
    }

    private fun today(): LocalDate = clock.todayIn(zoneProvider())

    private fun startSync(force: Boolean) {
        if (syncJob?.isActive == true) return
        sync.value = PortfolioSyncState(running = true)
        syncJob = viewModelScope.launch {
            val failed = try {
                runSync(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                true
            }
            sync.value = PortfolioSyncState(running = false, failed = failed)
        }
    }

    /** Returns `true` when a history or the exchange rates could not be downloaded. Manual holdings need nothing. */
    private suspend fun runSync(force: Boolean): Boolean {
        val quoted = scopedHoldings.first().mapNotNull { it.valuation as? HoldingValuation.Quoted }
        if (quoted.isEmpty()) return false
        val refs = quoted.map { it.ref }.toSet()
        return coroutineScope {
            val fundamentals = async { syncFundamentals(force) }
            // Both are tried: one failing download does not stop the other.
            val historiesFailed = attempt { historyRepository.syncAll(refs, force).failures.isNotEmpty() }
            val ratesFailed = attempt {
                syncRates(quoted, force)
                false
            }
            fundamentals.await()
            historiesFailed || ratesFailed
        }
    }

    /** The exchange rates the non-EUR quoted holdings need, from the day their history starts. */
    private suspend fun syncRates(quoted: List<HoldingValuation.Quoted>, force: Boolean) {
        val foreign = quoted.filter { it.currency != Currency.EUR }
        // Without a history there is nothing to convert yet.
        val from = foreign.mapNotNull { historyRepository.observe(it.ref).first().firstOrNull()?.date }.minOrNull()
            ?: return
        fxHistoryRepository.sync(foreign.map { it.currency }.toSet(), from, force)
    }

    /** What is stored stays when this fails, and it does not concern the history warning. */
    private suspend fun syncFundamentals(force: Boolean) {
        try {
            portfolioRepository.syncFundamentals(null, force)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Cached fundamentals stay; the next open tries again.
        }
    }

    private fun buildState(
        analysis: PortfolioAnalysis,
        range: HistoryRange,
        syncState: PortfolioSyncState,
        targets: TargetsPanel,
    ): PortfolioDetailUiState {
        val status = when {
            !analysis.hasQuoted -> HistoryStatus.NOT_APPLICABLE
            analysis.series.size >= MIN_CHART_POINTS -> HistoryStatus.AVAILABLE
            syncState.running -> HistoryStatus.LOADING
            else -> HistoryStatus.UNAVAILABLE
        }
        val available = status == HistoryStatus.AVAILABLE
        return PortfolioDetailUiState(
            loaded = true,
            header = analysis.header,
            yourReturns = analysis.yourReturns,
            historyStatus = status,
            evolution = if (available) evolutionOf(analysis, range) else null,
            correlation = analysis.correlation.takeIf { available },
            classAllocation = analysis.classAllocation,
            allocations = analysis.allocations,
            costsRisk = analysis.costsRisk,
            concentration = analysis.concentration,
            note = PORTFOLIO_NOTE.takeIf { available },
            isSyncing = syncState.running,
            syncError = syncState.failed,
            targetSection = targets.section,
            targetsEditor = targets.editor,
        )
    }

    private fun evolutionOf(analysis: PortfolioAnalysis, range: HistoryRange): EvolutionUi? {
        val risk = analysis.risk ?: return null
        val (ranges, chart) = priceChartUi(analysis.series, range, today())
        return EvolutionUi(
            subtitle = EVOLUTION_SUBTITLE,
            excludedText = analysis.excludedText,
            ranges = ranges,
            chart = chart,
            yearReturns = analysis.yearReturns,
            risk = risk,
        )
    }
}

/** Runs [download] and tells whether it failed: it answered `true`, or it threw (but never when cancelled). */
internal suspend fun attempt(download: suspend () -> Boolean): Boolean = try {
    download()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    true
}

/** The portfolio the targets of the screen belong to ([portfolio], `null` for none) and whether the user must pick one. */
private data class TargetScope(val portfolio: Portfolio?, val mustChoose: Boolean)

/** The targets part of the screen: the section and, while it is open, the editor. */
private data class TargetsPanel(val section: TargetSectionUi?, val editor: TargetsEditorUi?)

private fun targetsPanel(
    scope: TargetScope,
    split: ClassSplit?,
    targets: AllocationTargets?,
    calculatorText: String,
    draft: TargetsDraft?,
): TargetsPanel {
    val portfolio = scope.portfolio
    val section = when {
        portfolio == null -> TargetSectionUi.PickPortfolio(TARGETS_PICK_PORTFOLIO_TEXT).takeIf { scope.mustChoose }
        split == null -> null
        targets == null -> TargetSectionUi.Empty(TARGETS_EMPTY_TEXT)
        else -> rebalanceStatusOf(split, targets)?.let { definedSection(it, calculatorText) }
    }
    return TargetsPanel(section, draft?.takeIf { portfolio != null }?.let(::editorUi))
}
