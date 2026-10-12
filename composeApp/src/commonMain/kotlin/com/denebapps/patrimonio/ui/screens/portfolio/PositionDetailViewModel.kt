package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.calc.holdingWeights
import com.denebapps.patrimonio.domain.calc.mulDivHalfEven
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PERCENT_SCALE
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.isQuotedFund
import com.denebapps.patrimonio.ui.components.charts.ChartPoint
import com.denebapps.patrimonio.ui.components.formatEur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private const val POSITION_DETAIL_STOP_TIMEOUT_MS = 5_000L

/** `100 * PERCENT_SCALE`: a percent-scaled rate divided by this is the fraction to multiply an amount by. */
internal const val PERCENT_TO_FRACTION = 100L * PERCENT_SCALE

private const val QUOTED_NOTE = "Rentabilidades del producto, no de tus compras."
private const val MANUAL_NOTE = "Sin histórico: es un valor manual."
private const val WEIGHT_DECIMALS = 1
private const val EMPTY_MOVEMENTS_TEXT = "Sin movimientos: las participaciones se editan a mano."

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
 * portfolio the position is (null while it has no value). [classText] is its asset class and where it comes from.
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
    val classText: String = "",
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
    val chartNote: String? = null,
    val isSyncing: Boolean = false,
    val syncError: Boolean = false,
    val movements: MovementsUi = MovementsUi(),
    val choice: OpeningChoiceUi? = null,
    val myPosition: MyPositionUi? = null,
)

/**
 * The movements section. It is [visible] for a quoted position (a manual one has no units to move). Without movements
 * [emptyText] says that the units are edited by hand; [hasUnits] says that the position holds some, and [canTransfer]
 * is set for a fund that holds units and has movements, the only kind that can be transferred.
 */
data class MovementsUi(
    val visible: Boolean = false,
    val rows: List<MovementRowUi> = emptyList(),
    val emptyText: String? = null,
    val hasUnits: Boolean = false,
    val canTransfer: Boolean = false,
)

/** Where the question that opens the movements of a position that already has units is. */
enum class OpeningChoiceStep { CHOOSE, WARN_ONE_BY_ONE }

/**
 * The question asked when the first movement is added to a position that already has units: record them as an
 * opening balance ([openingLabel]) or enter the purchases one by one ([oneByOneLabel]), which first shows [warning].
 */
data class OpeningChoiceUi(
    val step: OpeningChoiceStep,
    val openingLabel: String,
    val oneByOneLabel: String,
    val warning: String?,
)

/** Where the position detail sends the user next. */
sealed interface PositionNavigation {
    /** The movement sheet: a new movement, or the one [movementId]; [openingBalance] opens the opening balance form. */
    data class MovementForm(val movementId: String?, val openingBalance: Boolean) : PositionNavigation

    /** The transfer sheet: a new transfer, or the one [transferId]. */
    data class Transfer(val transferId: String?) : PositionNavigation
}

/** The state of the download of this position's data, kept apart from the data itself. */
private data class SyncState(val running: Boolean, val failed: Boolean = false)

/**
 * Every holding, every portfolio and every movement: together they say how much of its portfolio and of the total a
 * position is, and who is on the other side of its transfers.
 */
private data class Placement(
    val holdings: List<PortfolioHolding>,
    val portfolios: List<Portfolio>,
    val movements: List<Movement>,
)

/** What the repositories say about the position, apart from what the user is doing on the screen. */
private data class PositionData(val placed: Placement, val points: List<PricePoint>, val rates: FxRates)

/** What the user is doing on the screen: both chart ranges, the download, and the question about the units. */
private data class Interaction(
    val range: HistoryRange,
    val myRange: HistoryRange,
    val sync: SyncState,
    val choice: OpeningChoiceStep?,
)

/**
 * Position detail of the holding [holdingId]: its header, the product's price chart over a selectable range, the
 * return per calendar year, annualized returns, volatility and deepest fall, and the provider's product data. Those
 * figures are the PRODUCT's (its price history), not the user's; "Mi posición" ([PositionDetailUiState.myPosition]) is
 * the user's own: cost, gains, annual return, lots and the value of their units over time, for a position with
 * movements. The history, the exchange rates and the fundamentals are brought up to date when the screen opens;
 * [onRefresh] forces them. The cached data is always shown, even when an update fails. [navigateBack] emits once if
 * the holding disappears while the screen is open. The own returns replay every movement on [computationDispatcher],
 * off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionDetailViewModel(
    private val holdingId: String,
    private val portfolioRepository: PortfolioRepository,
    portfolios: PortfolioCollectionRepository,
    private val movementRepository: MovementRepository,
    fxRepository: FxRepository,
    private val fxHistoryRepository: FxHistoryRepository,
    private val historyRepository: InstrumentHistoryRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val selectedRange = MutableStateFlow(HistoryRange.ONE_YEAR)
    private val myPositionRange = MutableStateFlow(HistoryRange.ONE_YEAR)

    // Running from the start so the first frame says "updating" and not "no history yet".
    private val sync = MutableStateFlow(SyncState(running = true))
    private var syncJob: Job? = null

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to leave the screen. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val navigateChannel = Channel<PositionNavigation>(Channel.BUFFERED)

    /** One-shot requests to open a movement or transfer sheet. */
    val navigate: Flow<PositionNavigation> = navigateChannel.receiveAsFlow()

    private val choiceStep = MutableStateFlow<OpeningChoiceStep?>(null)

    private var closed = false

    private val history: Flow<List<PricePoint>> = portfolioRepository.observeAll()
        .map { holdings -> (holdings.firstOrNull { it.id == holdingId }?.valuation as? HoldingValuation.Quoted)?.ref }
        .distinctUntilChanged()
        .flatMapLatest { ref -> if (ref == null) flowOf(emptyList()) else historyRepository.observe(ref) }

    private val placement = combine(
        portfolioRepository.observeAll(),
        portfolios.observePortfolios(),
        movementRepository.observeAllMovements(),
        ::Placement,
    )

    private val rates = fxRepository.observeRates()

    private val data = combine(placement, history, rates, ::PositionData)

    private val interaction = combine(selectedRange, myPositionRange, sync, choiceStep, ::Interaction)

    /** The user's own returns on this position: every movement replayed, off the main thread. */
    private val ownPosition: Flow<OwnPositionAnalysis?> = combine(
        portfolioRepository.observeAll(),
        movementRepository.observeAllMovements(),
        history,
        fxHistoryRepository.observe(),
        rates,
    ) { holdings, movements, points, fxHistory, currentRates ->
        holdings.firstOrNull { it.id == holdingId }
            ?.let { analyzeOwnPosition(OwnPositionInputs(it, movements, points, fxHistory, currentRates), today()) }
    }.flowOn(computationDispatcher)

    val state: StateFlow<PositionDetailUiState> = combine(data, ownPosition, interaction) { current, own, ui ->
        current.placed.holdings.firstOrNull { it.id == holdingId }
            ?.let { buildState(it, current, own, ui) }
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

    /** Shows [range] in the chart of the value of my units; ignored if that window has no data to draw. */
    fun onMyPositionRangeSelect(range: HistoryRange) {
        if (state.value.myPosition?.ranges?.firstOrNull { it.range == range }?.enabled == true) {
            myPositionRange.value = range
        }
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

    /**
     * "Añadir movimiento": a position that has units but no movements first asks whether to record them as an opening
     * balance or to enter the purchases one by one; any other goes straight to the movement sheet.
     */
    fun onAddMovement() {
        val section = state.value.movements
        if (!section.visible) return
        if (section.rows.isEmpty() && section.hasUnits) {
            choiceStep.value = OpeningChoiceStep.CHOOSE
        } else {
            navigateChannel.trySend(PositionNavigation.MovementForm(null, openingBalance = false))
        }
    }

    /** The user keeps the units the position has as its opening balance. */
    fun onChooseOpeningBalance() {
        choiceStep.value = null
        navigateChannel.trySend(PositionNavigation.MovementForm(null, openingBalance = true))
    }

    /** The user will enter the purchases one by one; shows what happens to the units they have now. */
    fun onChooseOneByOne() {
        choiceStep.value = OpeningChoiceStep.WARN_ONE_BY_ONE
    }

    /** The user accepts that the units will come from the movements and goes on to the first one. */
    fun onConfirmOneByOne() {
        choiceStep.value = null
        navigateChannel.trySend(PositionNavigation.MovementForm(null, openingBalance = false))
    }

    fun onDismissChoice() {
        choiceStep.value = null
    }

    /** Opens the sheet that edits the movement [movementId]; a transfer leg opens its transfer. */
    fun onOpenMovement(movementId: String) {
        viewModelScope.launch {
            val movement = movementRepository.observeMovements(holdingId).first().firstOrNull { it.id == movementId }
                ?: return@launch
            val transferId = movement.transferId
            navigateChannel.send(
                if (transferId != null) {
                    PositionNavigation.Transfer(transferId)
                } else {
                    PositionNavigation.MovementForm(movement.id, movement.type == MovementType.OPENING_BALANCE)
                },
            )
        }
    }

    /** "Traspasar": opens the transfer sheet; ignored for a position that cannot be transferred. */
    fun onTransfer() {
        if (!state.value.movements.canTransfer) return
        navigateChannel.trySend(PositionNavigation.Transfer(null))
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
            // Both are tried: one failing download does not stop the other.
            val historyFailed = attempt {
                historyRepository.sync(ref, force)
                false
            }
            val ratesFailed = attempt {
                syncRates(holding, force)
                false
            }
            fundamentals.await()
            historyFailed || ratesFailed
        }
    }

    /** The exchange rates the value of my units needs, from my first movement; none for a product in euros. */
    private suspend fun syncRates(holding: PortfolioHolding, force: Boolean) {
        val currency = (holding.valuation as? HoldingValuation.Quoted)?.currency ?: return
        if (currency == Currency.EUR) return
        val from = movementRepository.observeMovements(holdingId).first().firstOrNull()?.date ?: return
        fxHistoryRepository.sync(setOf(currency), from, force)
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

    private fun today(): LocalDate = clock.todayIn(zoneProvider())

    private fun buildState(
        holding: PortfolioHolding,
        current: PositionData,
        own: OwnPositionAnalysis?,
        ui: Interaction,
    ): PositionDetailUiState {
        val (placed, points, currentRates) = current
        val (range, myRange, syncState, choice) = ui
        val today = today()
        val movements = movementsUi(holding, placed)
        val base = PositionDetailUiState(
            loaded = true,
            header = buildHeader(holding, placed, currentRates),
            product = productData(holding, currentRates),
            movements = movements,
            choice = choice?.let { openingChoiceUi(holding, it) },
            myPosition = own?.toUi(myRange, today),
        )
        if (holding.valuation !is HoldingValuation.Quoted) return base.copy(note = MANUAL_NOTE)
        val withSync = base.copy(note = QUOTED_NOTE, isSyncing = syncState.running)
        if (points.size < 2) {
            return withSync.copy(
                historyStatus = if (syncState.running) HistoryStatus.LOADING else HistoryStatus.UNAVAILABLE,
            )
        }
        val (ranges, chart) = priceChartUi(points, range, today)
        return withSync.copy(
            historyStatus = HistoryStatus.AVAILABLE,
            ranges = ranges,
            chart = chart,
            yearReturns = yearReturnsUi(points, today),
            annualized = annualizedFigures(points),
            risk = riskUi(points, today),
            chartNote = historyNote(holding.valuation.ref.provider),
            syncError = syncState.failed,
        )
    }

    /** The list of this position's movements (newest first) and what can be done with them. */
    private fun movementsUi(holding: PortfolioHolding, placed: Placement): MovementsUi {
        val valuation = holding.valuation as? HoldingValuation.Quoted ?: return MovementsUi()
        val own = placed.movements.filter { it.holdingId == holding.id }
        val namesByHolding = placed.holdings.associate { it.id to it.name }
        val transfers = placed.movements.filter { it.transferId != null }.groupBy { it.transferId }
        val rows = movementRows(own, valuation.currency) { leg ->
            transfers[leg.transferId]?.firstOrNull { it.id != leg.id }?.let { namesByHolding[it.holdingId] }
        }
        return MovementsUi(
            visible = true,
            rows = rows,
            emptyText = EMPTY_MOVEMENTS_TEXT.takeIf { rows.isEmpty() },
            hasUnits = !valuation.isClosed,
            canTransfer = rows.isNotEmpty() && !valuation.isClosed && isQuotedFund(holding.type, isQuoted = true),
        )
    }

    private fun openingChoiceUi(holding: PortfolioHolding, step: OpeningChoiceStep): OpeningChoiceUi? {
        val units = (holding.valuation as? HoldingValuation.Quoted)?.units ?: return null
        val unitsText = "${formatUnits(units)} part."
        return OpeningChoiceUi(
            step = step,
            openingLabel = "Saldo inicial con tus $unitsText",
            oneByOneLabel = "Meter mis compras una a una",
            warning = if (step == OpeningChoiceStep.WARN_ONE_BY_ONE) {
                "Las participaciones pasarán a salir de tus movimientos: los $unitsText que tienes ahora se " +
                    "sustituirán por lo que registres."
            } else {
                null
            },
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
            classText = classText(holding),
        )
    }

    /** "Renta variable · automática" (from the category or type) or "... · elegida"; "Sin clasificar" for none. */
    private fun classText(holding: PortfolioHolding): String {
        val effective = holding.effectiveAssetClass() ?: return UNCLASSIFIED_LABEL
        val origin = if (holding.assetClass == null) "automática" else "elegida"
        return "${assetClassLabel(effective)} · $origin"
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
