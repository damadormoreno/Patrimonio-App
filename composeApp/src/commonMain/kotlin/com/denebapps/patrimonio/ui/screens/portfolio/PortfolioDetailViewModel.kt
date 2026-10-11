package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
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
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private const val PORTFOLIO_DETAIL_STOP_TIMEOUT_MS = 5_000L

/** The state of the download of the portfolio's data, kept apart from the data itself. */
private data class PortfolioSyncState(val running: Boolean, val failed: Boolean = false)

/**
 * Portfolio detail: the total, the value of today's holdings at past prices over a selectable range with its returns
 * and risk, the correlation between the quoted products, how the portfolio splits (by type, bank or broker and
 * currency), its average cost and risk, and its largest holding. The figures of the products are those of their price
 * history with TODAY's composition, not the user's gains. The histories, the fundamentals and the exchange rates they
 * need are brought up to date when the screen opens; [onRefresh] forces them. Cached data is always shown, even when
 * an update fails. The calculation runs on [computationDispatcher], off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioDetailViewModel(
    private val portfolioRepository: PortfolioRepository,
    fxRepository: FxRepository,
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

    /** The stored history of every quoted holding, by holding id; follows holdings being added or removed. */
    private val histories: Flow<Map<String, List<PricePoint>>> = portfolioRepository.observeAll()
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
        portfolioRepository.observeAll(),
        histories,
        fxHistoryRepository.observe(),
        fxRepository.observeRates(),
        ::PortfolioInputs,
    )
        .map { analyzePortfolio(it, today()) }
        .flowOn(computationDispatcher)

    val state: StateFlow<PortfolioDetailUiState> = combine(analysis, selectedRange, sync) { data, range, syncState ->
        buildState(data, range, syncState)
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
        val quoted = portfolioRepository.observeAll().first().mapNotNull { it.valuation as? HoldingValuation.Quoted }
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
            historyStatus = status,
            evolution = if (available) evolutionOf(analysis, range) else null,
            correlation = analysis.correlation.takeIf { available },
            allocations = analysis.allocations,
            costsRisk = analysis.costsRisk,
            concentration = analysis.concentration,
            note = PORTFOLIO_NOTE.takeIf { available },
            isSyncing = syncState.running,
            syncError = syncState.failed,
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

/** The instrument of every quoted holding, by holding id. */
private fun List<PortfolioHolding>.quotedRefs(): Map<String, InstrumentRef> =
    mapNotNull { holding -> (holding.valuation as? HoldingValuation.Quoted)?.let { holding.id to it.ref } }.toMap()

/** Runs [download] and tells whether it failed: it answered `true`, or it threw (but never when cancelled). */
private suspend fun attempt(download: suspend () -> Boolean): Boolean = try {
    download()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    true
}
