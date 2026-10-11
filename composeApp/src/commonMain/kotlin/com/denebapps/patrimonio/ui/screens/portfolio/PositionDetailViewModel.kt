package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.holdingWeights
import com.denebapps.patrimonio.domain.calc.mulDivHalfEven
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PERCENT_SCALE
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.ui.components.charts.ChartPoint
import com.denebapps.patrimonio.ui.components.formatEur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private const val POSITION_DETAIL_STOP_TIMEOUT_MS = 5_000L

/** `100 * PERCENT_SCALE`: a percent-scaled rate divided by this is the fraction to multiply an amount by. */
internal const val PERCENT_TO_FRACTION = 100L * PERCENT_SCALE

private const val QUOTED_NOTE = "Rentabilidades del producto, no de tus compras."
private const val MANUAL_NOTE = "Sin histórico: es un valor manual."
private const val WEIGHT_DECIMALS = 1

/** Whether there is a price history to draw: not applicable (manual), being downloaded, missing, or ready. */
enum class HistoryStatus { NOT_APPLICABLE, LOADING, UNAVAILABLE, AVAILABLE }

/** One chip of the range selector: [enabled] when that window has at least two points, [selected] when it is shown. */
data class RangeChipUi(val range: HistoryRange, val label: String, val enabled: Boolean, val selected: Boolean)

/**
 * The price chart. [points] are the [prices] of the shown range as chart samples (the same order, so a scrubbed
 * index finds its price). [changeText] is the change over the range (null if it cannot be computed) and
 * [changeTone] its sign; the other texts label the axes. Prices are in [currency], the product's own.
 */
data class ChartUi(
    val points: List<ChartPoint>,
    val prices: List<PricePoint>,
    val currency: Currency,
    val changeText: String?,
    val changeTone: PerformanceTone,
    val minText: String,
    val maxText: String,
    val firstDateText: String,
    val lastDateText: String,
)

/**
 * A calendar [year] of the return chart: its return as a ratio ([ratio], 0.10 is +10 %) and as [valueText] ("5,84 %"),
 * and the sign as [tone]. [partialSince] is the day and month ("14/03") the first year of the history starts at (null
 * for any other year) and [yearToDate] marks the running year; both are figures that are not a whole year.
 */
data class YearReturnUi(
    val year: Int,
    val ratio: Double,
    val valueText: String,
    val tone: PerformanceTone,
    val partialSince: String?,
    val yearToDate: Boolean,
)

/** A labelled figure ("3 años", "+7,10 %"), "—" when it could not be computed. */
data class FigureUi(val label: String, val valueText: String, val tone: PerformanceTone = PerformanceTone.NEUTRAL)

/** The risk section: the volatility row ([volatilityLabel], [volatilityText]) and the deepest fall with its dates. */
data class RiskUi(
    val volatilityLabel: String,
    val volatilityText: String,
    val drawdownText: String,
    val drawdownDates: String?,
)

/**
 * What the provider says about the product; every field is null when it is missing and the section is left out when
 * all are. [yearlyCostText] is what the ongoing charge costs a year at the current value, in euros.
 */
data class ProductDataUi(
    val terText: String?,
    val yearlyCostText: String?,
    val riskLevel: Int?,
    val category: String?,
    val manager: String?,
)

/**
 * The top of the screen. [identifier] is whatever identifies the product plus its data source (empty for a manual
 * one), [detailText] the units, last price and date (or the manual valuation date), [weightText] how much of the
 * portfolio the position is (null while it has no value).
 */
data class PositionHeaderUi(
    val name: String = "",
    val typeLabel: String = "",
    val broker: String? = null,
    val identifier: String = "",
    val valueText: String = "",
    val valueEurText: String? = null,
    val detailText: String = "",
    val weightText: String? = null,
)

/**
 * The whole position detail screen, every text ready to show. [historyStatus] says what the chart area holds; the
 * sections below it are empty unless it is [HistoryStatus.AVAILABLE]. [isSyncing] is a download in flight, and
 * [syncError] a failed one while cached history is shown.
 */
data class PositionDetailUiState(
    val loaded: Boolean = false,
    val header: PositionHeaderUi = PositionHeaderUi(),
    val historyStatus: HistoryStatus = HistoryStatus.NOT_APPLICABLE,
    val ranges: List<RangeChipUi> = emptyList(),
    val chart: ChartUi? = null,
    val yearReturns: List<YearReturnUi> = emptyList(),
    val annualized: List<FigureUi> = emptyList(),
    val risk: RiskUi? = null,
    val product: ProductDataUi? = null,
    val note: String = "",
    val isSyncing: Boolean = false,
    val syncError: Boolean = false,
)

/** The state of the download of this position's data, kept apart from the data itself. */
private data class SyncState(val running: Boolean, val failed: Boolean = false)

/** Every holding and every portfolio, which together say how much of its portfolio and of the total a position is. */
private data class Placement(val holdings: List<PortfolioHolding>, val portfolios: List<Portfolio>)

/**
 * Position detail of the holding [holdingId]: its header, the product's price chart over a selectable range, the
 * return per calendar year, annualized returns, volatility and deepest fall, and the provider's product data. The
 * figures are the PRODUCT's (its price history), not the user's. The history and the fundamentals are brought up to
 * date when the screen opens; [onRefresh] forces them. The cached data is always shown, even when an update fails.
 * [navigateBack] emits once if the holding disappears while the screen is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionDetailViewModel(
    private val holdingId: String,
    private val portfolioRepository: PortfolioRepository,
    portfolios: PortfolioCollectionRepository,
    fxRepository: FxRepository,
    private val historyRepository: InstrumentHistoryRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val selectedRange = MutableStateFlow(HistoryRange.ONE_YEAR)

    // Running from the start so the first frame says "updating" and not "no history yet".
    private val sync = MutableStateFlow(SyncState(running = true))
    private var syncJob: Job? = null

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to leave the screen. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private var closed = false

    private val history: Flow<List<PricePoint>> = portfolioRepository.observeAll()
        .map { holdings -> (holdings.firstOrNull { it.id == holdingId }?.valuation as? HoldingValuation.Quoted)?.ref }
        .distinctUntilChanged()
        .flatMapLatest { ref -> if (ref == null) flowOf(emptyList()) else historyRepository.observe(ref) }

    private val placement = combine(portfolioRepository.observeAll(), portfolios.observePortfolios(), ::Placement)

    val state: StateFlow<PositionDetailUiState> = combine(
        placement,
        history,
        fxRepository.observeRates(),
        selectedRange,
        sync,
    ) { placed, points, rates, range, syncState ->
        placed.holdings.firstOrNull { it.id == holdingId }
            ?.let { buildState(it, placed, points, rates, range, syncState) }
            ?: PositionDetailUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(POSITION_DETAIL_STOP_TIMEOUT_MS),
        initialValue = PositionDetailUiState(),
    )

    init {
        startSync(force = false)
        // Gone meanwhile (deleted elsewhere, or never existed): there is nothing left to show.
        viewModelScope.launch {
            portfolioRepository.observeAll().first { holdings -> holdings.none { it.id == holdingId } }
            close()
        }
    }

    /** Shows [range]; ignored if that window has no data to draw. */
    fun onRangeSelect(range: HistoryRange) {
        if (state.value.ranges.firstOrNull { it.range == range }?.enabled == true) selectedRange.value = range
    }

    /** Downloads the history and the fundamentals now, whatever their age; ignored while an update is running. */
    fun onRefresh() {
        // A manual value has no history to download.
        if (state.value.historyStatus == HistoryStatus.NOT_APPLICABLE && state.value.loaded) return
        startSync(force = true)
    }

    fun onSyncErrorDismiss() {
        sync.value = sync.value.copy(failed = false)
    }

    private fun startSync(force: Boolean) {
        if (syncJob?.isActive == true) return
        sync.value = SyncState(running = true)
        syncJob = viewModelScope.launch {
            val failed = try {
                runSync(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                true
            }
            sync.value = SyncState(running = false, failed = failed)
        }
    }

    /** Returns `true` when the history could not be downloaded. Manual holdings have nothing to download. */
    private suspend fun runSync(force: Boolean): Boolean {
        val holding = portfolioRepository.observeAll().first().firstOrNull { it.id == holdingId } ?: return false
        val ref = (holding.valuation as? HoldingValuation.Quoted)?.ref ?: return false
        return coroutineScope {
            val fundamentals = async { syncFundamentals(force) }
            val failed = try {
                historyRepository.sync(ref, force)
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                true
            }
            fundamentals.await()
            failed
        }
    }

    /** What is stored stays when this fails, and it does not concern the history warning. */
    private suspend fun syncFundamentals(force: Boolean) {
        try {
            portfolioRepository.syncFundamentals(holdingId, force)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Cached fundamentals stay; the next open tries again.
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        navigateBackChannel.trySend(Unit)
    }

    private fun buildState(
        holding: PortfolioHolding,
        placed: Placement,
        points: List<PricePoint>,
        rates: FxRates,
        range: HistoryRange,
        syncState: SyncState,
    ): PositionDetailUiState {
        val base = PositionDetailUiState(
            loaded = true,
            header = buildHeader(holding, placed, rates),
            product = productData(holding, rates),
        )
        if (holding.valuation !is HoldingValuation.Quoted) return base.copy(note = MANUAL_NOTE)
        val withSync = base.copy(note = QUOTED_NOTE, isSyncing = syncState.running)
        if (points.size < 2) {
            return withSync.copy(
                historyStatus = if (syncState.running) HistoryStatus.LOADING else HistoryStatus.UNAVAILABLE,
            )
        }
        val today = clock.todayIn(zoneProvider())
        val (ranges, chart) = priceChartUi(points, range, today)
        return withSync.copy(
            historyStatus = HistoryStatus.AVAILABLE,
            ranges = ranges,
            chart = chart,
            yearReturns = yearReturnsUi(points, today),
            annualized = annualizedFigures(points),
            risk = riskUi(points, today),
            syncError = syncState.failed,
        )
    }

    private fun buildHeader(holding: PortfolioHolding, placed: Placement, rates: FxRates): PositionHeaderUi {
        val (valueText, valueEurText) = holdingValueTexts(holding, rates)
        val source = (holding.valuation as? HoldingValuation.Quoted)?.let { providerLabel(it.ref.provider) }
        return PositionHeaderUi(
            name = holding.name,
            typeLabel = instrumentTypeLabel(holding.type),
            broker = holding.broker,
            identifier = listOf(identifierText(holding.isin, holding.ticker), source.orEmpty())
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            valueText = valueText,
            valueEurText = valueEurText,
            detailText = holdingDetailLine(holding, zoneProvider()),
            weightText = weightText(holding, placed, rates),
        )
    }

    /**
     * "Peso en «Hijos»: 49,5 %", and with several portfolios also " · 36,1 % del total": the weight of the position in
     * its own portfolio and in everything. `null` while it has no value.
     */
    private fun weightText(holding: PortfolioHolding, placed: Placement, rates: FxRates): String? {
        val inPortfolio = weightOf(holding, placed.holdings.inPortfolio(holding.portfolioId), rates) ?: return null
        val portfolioName = placed.portfolios.firstOrNull { it.id == holding.portfolioId }?.name
        val label = portfolioName?.let { "«$it»" } ?: "tu cartera"
        val text = "Peso en $label: ${percentText(inPortfolio)}"
        if (placed.portfolios.size < MIN_PORTFOLIOS_TO_CHOOSE) return text
        val inTotal = weightOf(holding, placed.holdings, rates) ?: return text
        return "$text · ${percentText(inTotal)} del total"
    }

    private fun weightOf(holding: PortfolioHolding, among: List<PortfolioHolding>, rates: FxRates) =
        holdingWeights(among, rates).firstOrNull { it.holding.id == holding.id }?.weight

    private fun percentText(weight: Double) = formatPercent(weight, signed = false, decimals = WEIGHT_DECIMALS)

    private fun productData(holding: PortfolioHolding, rates: FxRates): ProductDataUi? =
        productDataUi(holding.fundamentals) { charge -> yearlyCostText(holding, charge, rates) }

    /** "≈ 4,41 €/año con tu posición": the current value times the ongoing charge, in euros; null without a value. */
    private fun yearlyCostText(holding: PortfolioHolding, chargePercentScaled: Long, rates: FxRates): String? {
        val value = holding.value() ?: return null
        val cost = CurrencyAmount(
            Money(mulDivHalfEven(value.amount.minorUnits, chargePercentScaled, PERCENT_TO_FRACTION)),
            value.currency,
        )
        return "≈ ${formatEur(cost.toEur(rates))}/año con tu posición"
    }
}
