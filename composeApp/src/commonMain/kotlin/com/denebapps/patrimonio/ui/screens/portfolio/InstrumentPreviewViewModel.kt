package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.instrumentFit
import com.denebapps.patrimonio.domain.calc.mulDivHalfEven
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.repository.InstrumentPreviewLoader
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioScopeSeries
import com.denebapps.patrimonio.domain.repository.PortfolioSeriesLoader
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.PreviewFailure
import com.denebapps.patrimonio.domain.repository.PreviewUnavailableException
import com.denebapps.patrimonio.ui.components.formatEur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

private const val PREVIEW_STOP_TIMEOUT_MS = 5_000L

/** The portfolio the product is compared with: [portfolioId] (null for all) and, if there is one, its [name]. */
private data class Scope(val portfolioId: String?, val name: String?)

/** Where the download of the preview is. */
private sealed interface Download {
    data object Running : Download

    data class Failed(val failure: PreviewFailure) : Download

    data class Done(val preview: InstrumentPreviewData, val scope: PortfolioScopeSeries) : Download
}

/** Everything the screen is built from. [amount] is the one the projection is computed for. */
private data class PreviewInputs(
    val download: Download,
    val scope: Scope?,
    val range: HistoryRange,
    val amount: Money?,
)

/**
 * Preview of the search result [candidate], before it is added: the product's price chart over a selectable range,
 * the return per calendar year, annualized returns, risk and the provider's product data (the same figures as the
 * position detail, from the downloaded history), and how it fits the portfolio selected in Cartera, or all of them. The
 * history is downloaded for the preview only; the portfolio is read from what is stored. An amount (EUR) projects the
 * portfolio after adding it; the projection follows the typing after a short pause. A download that fails is
 * [PreviewStatus.UNAVAILABLE] (or [PreviewStatus.NO_DATA] when the provider has no history) and [onRetry] tries again;
 * adding the product never depends on it. The calculation runs on [computationDispatcher], off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class InstrumentPreviewViewModel(
    private val candidate: InstrumentCandidate,
    private val previewLoader: InstrumentPreviewLoader,
    private val seriesLoader: PortfolioSeriesLoader,
    private val portfolios: PortfolioCollectionRepository,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val header = PreviewHeaderUi(
        name = candidate.holdingName(),
        typeLabel = instrumentTypeLabel(candidate.type),
        identifier = listOf(identifierText(candidate.isin, candidate.ticker), providerLabel(candidate.ref.provider))
            .filter { it.isNotEmpty() }
            .joinToString(" · "),
        exchange = candidate.exchange,
    )

    private val download = MutableStateFlow<Download>(Download.Running)
    private val scope = MutableStateFlow<Scope?>(null)
    private val selectedRange = MutableStateFlow(HistoryRange.ONE_YEAR)
    private val amountText = MutableStateFlow("")
    private var downloadJob: Job? = null

    // Clearing the field is not typing: it takes effect at once.
    private val projectedAmount: Flow<Money?> = amountText
        .debounce { text -> if (text.isEmpty()) 0L else AMOUNT_DEBOUNCE_MS }
        .map(::parseAmount)
        .distinctUntilChanged()

    private val content: Flow<InstrumentPreviewUiState> = combine(
        download,
        scope,
        selectedRange,
        projectedAmount,
        ::PreviewInputs,
    )
        .mapLatest(::buildState)
        .flowOn(computationDispatcher)

    val state: StateFlow<InstrumentPreviewUiState> = combine(content, amountText) { built, typed ->
        built.copy(amountText = typed)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(PREVIEW_STOP_TIMEOUT_MS),
        initialValue = InstrumentPreviewUiState(header),
    )

    init {
        startDownload()
    }

    /** Shows [range]; ignored if that window has no data to draw. */
    fun onRangeSelect(range: HistoryRange) {
        if (state.value.ranges.firstOrNull { it.range == range }?.enabled == true) selectedRange.value = range
    }

    /** The amount, in euros, as typed; "" clears it. */
    fun onAmountChange(text: String) {
        amountText.value = text
    }

    /** Downloads the preview again after it failed; ignored while a download is running. */
    fun onRetry() {
        if (download.value is Download.Failed) startDownload()
    }

    private fun startDownload() {
        if (downloadJob?.isActive == true) return
        download.value = Download.Running
        downloadJob = viewModelScope.launch {
            val selected = scope.value ?: resolveScope().also { scope.value = it }
            download.value = try {
                fetch(selected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: PreviewUnavailableException) {
                Download.Failed(unavailable.failure)
            } catch (failure: Exception) {
                Download.Failed(PreviewFailure.UNAVAILABLE)
            }
        }
    }

    /** The portfolio selected in Cartera while there is a choice and it exists; otherwise all of them. */
    private suspend fun resolveScope(): Scope {
        val all = portfolios.observePortfolios().first()
        val stored = preferences.observeSelectedPortfolioId().first()
        val several = all.size >= MIN_PORTFOLIOS_TO_CHOOSE
        val selected = all.firstOrNull { several && it.id == stored }
        // With a single portfolio "all of them" is that one, and says its name.
        return Scope(portfolioId = selected?.id, name = (selected ?: all.singleOrNull())?.name)
    }

    private suspend fun fetch(selected: Scope): Download.Done = coroutineScope {
        val preview = async { previewLoader.load(candidate) }
        val series = async { seriesLoader.load(selected.portfolioId) }
        Download.Done(preview.await(), series.await())
    }

    private fun buildState(inputs: PreviewInputs): InstrumentPreviewUiState {
        val base = InstrumentPreviewUiState(header, scopePortfolioId = inputs.scope?.portfolioId)
        return when (val current = inputs.download) {
            Download.Running -> base
            is Download.Failed -> base.copy(
                status = when (current.failure) {
                    PreviewFailure.NO_DATA -> PreviewStatus.NO_DATA
                    PreviewFailure.UNAVAILABLE -> PreviewStatus.UNAVAILABLE
                },
            )
            is Download.Done -> ready(base, current, inputs)
        }
    }

    private fun ready(
        base: InstrumentPreviewUiState,
        done: Download.Done,
        inputs: PreviewInputs,
    ): InstrumentPreviewUiState {
        val history = done.preview.history
        // A chart needs a line; with fewer points there is nothing of the history to show.
        if (history.size < MIN_CHART_POINTS) return base.copy(status = PreviewStatus.NO_DATA)
        val today = clock.todayIn(zoneProvider())
        val (ranges, chart) = priceChartUi(history, inputs.range, today)
        val last = history.last()
        val fit = instrumentFit(
            candidate = done.preview,
            holdings = done.scope.holdings,
            eurSeriesById = done.scope.eurSeries,
            rates = done.scope.rates,
            today = today,
            amount = inputs.amount,
        )
        return base.copy(
            header = header.copy(priceText = formatPrice(last.price), dateText = formatShortDate(last.date)),
            status = PreviewStatus.READY,
            ranges = ranges,
            chart = chart,
            yearReturns = yearReturnsUi(history, today),
            annualized = annualizedFigures(history),
            risk = riskUi(history, today),
            product = productDataUi(done.preview.fundamentals) { charge -> yearlyCostText(inputs.amount, charge) },
            fit = fitSectionUi(fitTitle(inputs.scope), done.preview, done.scope, fit, today),
        )
    }

    private fun fitTitle(scope: Scope?): String = "Encaje con " + (scope?.name?.let { "«$it»" } ?: "todas tus carteras")

    /** "≈ 1,80 €/año con 1.000,00 €": what the ongoing charge costs a year on [amount]; null without an amount. */
    private fun yearlyCostText(amount: Money?, chargePercentScaled: Long): String? {
        amount ?: return null
        return try {
            val cost = Money(mulDivHalfEven(amount.minorUnits, chargePercentScaled, PERCENT_TO_FRACTION))
            "≈ ${formatEur(cost)}/año con ${formatEur(amount)}"
        } catch (overflow: ArithmeticException) {
            null
        }
    }

    private fun parseAmount(text: String): Money? =
        parseAmountToMinor(text, Currency.EUR)?.takeIf { it > 0 }?.let(::Money)
}
