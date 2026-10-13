package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.portfolioTotalEur
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.AllocationTargetRepository
import com.denebapps.patrimonio.domain.repository.CarteraFilterSelection
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.PriceRefreshResult
import com.denebapps.patrimonio.ui.components.formatEur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone

private const val CARTERA_STOP_TIMEOUT_MS = 5_000L

private const val ALL_PORTFOLIOS_LABEL = "Todas"

/**
 * One row of the Cartera list: texts ready to show. [meta] is its muted second line (type · class · bank or broker ·
 * units, see [holdingMetaLine]). [valueEurText] is only set for a holding not in euros, [broker]
 * for one that has a bank or broker, and [portfolioName] while the list mixes several portfolios (so it says which one
 * each holding is in). [assetClass] and [assetClassText] are the holding's class and its name; both `null` for one that
 * has none, which the list leaves unlabelled instead of repeating "Sin clasificar" on every such row. A position that
 * sold everything is [isClosed]: it reads "Cerrada" and is worth zero.
 */
data class HoldingRowUi(
    val id: String,
    val name: String,
    val typeLabel: String,
    val broker: String?,
    val meta: String,
    val valueText: String,
    val valueEurText: String?,
    val portfolioName: String? = null,
    val assetClass: AssetClass? = null,
    val assetClassText: String? = null,
    val isClosed: Boolean = false,
)

/**
 * The Cartera tab: the [rows] of the current scope, their [total] in EUR, a [countLabel] for the header, whether a
 * price refresh is running and, once one ended with failures, the short [refreshMessage] to show.
 *
 * The scope is every portfolio ("Todas", [selectedPortfolioId] `null`) or one of them. [portfolios] lists them in order
 * for the selector, which is only shown ([showSelector]) with several of them; [scopeLabel] names the scope then.
 * [dialog] is the dialog that creates a portfolio, while it is open (renaming and deleting live in the detail of the
 * portfolio, see `PortfolioDetailViewModel`).
 *
 * The list can be filtered by class, bank or broker and type ([filter]). Only the [rows] and the [total] are filtered:
 * [countLabel] and [isEmpty] still describe the scope. While a filter is active [filterSummary] says how many of the
 * scope's products are shown, and [noMatches] says that the scope has products but none passes the filter.
 *
 * [rebalanceBanner] warns that a portfolio drifted out of its tolerance band (the selected one, or those that need it
 * while all are shown). It is computed from the whole portfolios: the filters do not touch it.
 */
data class CarteraUiState(
    val rows: List<HoldingRowUi>,
    val total: Money,
    val countLabel: String,
    val isRefreshing: Boolean,
    val refreshMessage: String?,
    val isEmpty: Boolean,
    val portfolios: List<PortfolioOptionUi> = emptyList(),
    val selectedPortfolioId: String? = null,
    val scopeLabel: String? = null,
    val showSelector: Boolean = false,
    val dialog: PortfolioDialogUi? = null,
    val filter: CarteraFilterUi = CarteraFilterUi(),
    val filterSummary: String? = null,
    val noMatches: Boolean = false,
    val rebalanceBanner: RebalanceBannerUi? = null,
)

/** What the user is told about the last price refresh, kept apart from the data so a refresh never touches rows. */
private data class RefreshState(val running: Boolean = false, val message: String? = null)

/** What the repositories and the preference say; the part of the state that does not come from the user's taps. */
private data class CarteraData(
    val holdings: List<PortfolioHolding> = emptyList(),
    val portfolios: List<Portfolio> = emptyList(),
    val storedSelection: String? = null,
    val storedFilter: CarteraFilterSelection = CarteraFilterSelection(),
    val rates: FxRates = FxRates(emptyMap()),
    val targets: Map<String, AllocationTargets> = emptyMap(),
)

/** The dialog that creates a portfolio as the ViewModel keeps it; [CarteraUiState.dialog] is derived from it. */
private data class CreateDialogState(val name: String = "", val error: String? = null)

/**
 * Cartera tab: the holdings of the selected portfolio (or of all) valued at their latest price, their total in EUR and
 * the manual price refresh. The selection is remembered through [PreferencesRepository]; one that names a portfolio
 * that no longer exists reads as "Todas". The filters of the list are remembered the same way and survive a change of
 * scope: the stored values stay as they are and only those the scope has apply. A portfolio is created through a dialog
 * the ViewModel drives ([onNewPortfolio] and the `onDialog...` actions); renaming and deleting one is done from its
 * detail.
 *
 * The repository already refreshes stale prices on start; [onRefresh] forces every quoted holding, shows progress
 * through [CarteraUiState.isRefreshing] and reports failures without blocking anything.
 */
class CarteraViewModel(
    private val portfolioRepository: PortfolioRepository,
    private val portfolios: PortfolioCollectionRepository,
    allocationTargets: AllocationTargetRepository,
    private val preferences: PreferencesRepository,
    fxRepository: FxRepository,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val refresh = MutableStateFlow(RefreshState())
    private val dialog = MutableStateFlow<CreateDialogState?>(null)
    private var confirming = false
    private val filterLock = Mutex()

    private val repositoryData = combine(
        portfolioRepository.observeAll(),
        portfolios.observePortfolios(),
        combine(preferences.observeSelectedPortfolioId(), preferences.observeCarteraFilter(), ::Pair),
        combine(fxRepository.observeRates(), allocationTargets.observeAllTargets(), ::Pair),
    ) { holdings, all, (selection, filter), (rates, targets) ->
        CarteraData(holdings, all, selection, filter, rates, targets)
    }

    val state: StateFlow<CarteraUiState> = combine(
        repositoryData,
        refresh,
        dialog,
    ) { current, refreshState, dialogState ->
        buildState(current, refreshState, dialogState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(CARTERA_STOP_TIMEOUT_MS),
        initialValue = buildState(CarteraData(), RefreshState(), null),
    )

    /** Fetches every quoted holding's price now; ignored while a refresh is already running. */
    fun onRefresh() {
        if (refresh.value.running) return
        refresh.value = RefreshState(running = true)
        viewModelScope.launch {
            val message = try {
                refreshMessage(portfolioRepository.refreshPrices(force = true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                REFRESH_FAILED
            }
            refresh.value = RefreshState(running = false, message = message)
        }
    }

    fun onMessageDismiss() {
        refresh.value = refresh.value.copy(message = null)
    }

    // ---- scope ---------------------------------------------------------------------------------------------------

    /** Shows the holdings of [portfolioId], or of every portfolio when it is `null`, and remembers the choice. */
    fun onSelectPortfolio(portfolioId: String?) {
        viewModelScope.launch { preferences.setSelectedPortfolioId(portfolioId) }
    }

    // ---- filters -------------------------------------------------------------------------------------------------

    /** Chooses [key] of [dimension] in the filter, or takes it out when it was chosen; remembers the result. */
    fun onToggleFilter(dimension: FilterDimension, key: String) = changeFilter { it.toggled(dimension, key) }

    /** Takes [key] out of [dimension]'s filter (the cross of an active chip). */
    fun onRemoveFilter(dimension: FilterDimension, key: String) = changeFilter { it.without(dimension, key) }

    /** Forgets every filter, including remembered values the current scope does not have. */
    fun onClearFilters() = changeFilter { CarteraFilterSelection() }

    /** One change at a time, each on the value the previous one left, so quick taps never overwrite each other. */
    private fun changeFilter(change: (CarteraFilterSelection) -> CarteraFilterSelection) {
        viewModelScope.launch {
            filterLock.withLock {
                preferences.setCarteraFilter(change(preferences.observeCarteraFilter().first()))
            }
        }
    }

    // ---- creating a portfolio ------------------------------------------------------------------------------------

    /** Opens the dialog that asks for the name of a new portfolio. */
    fun onNewPortfolio() {
        dialog.value = CreateDialogState()
    }

    /** What the name field of the dialog holds; a refused name's error goes away. */
    fun onDialogNameChange(text: String) {
        dialog.update { it?.copy(name = text, error = null) }
    }

    fun onDialogDismiss() {
        dialog.value = null
    }

    /** Creates the portfolio and shows it; a refusal keeps the dialog open with its reason in the state. */
    fun onDialogConfirm() {
        val current = dialog.value ?: return
        if (confirming) return
        confirming = true
        viewModelScope.launch {
            try {
                val created = portfolios.createPortfolio(current.name)
                preferences.setSelectedPortfolioId(created.id)
                dialog.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = portfolioErrorMessage(failure)
                dialog.update { open -> if (open == current) current.copy(error = message) else open }
            } finally {
                confirming = false
            }
        }
    }

    // ---- state ---------------------------------------------------------------------------------------------------

    private fun buildState(
        data: CarteraData,
        refreshState: RefreshState,
        dialogState: CreateDialogState?,
    ): CarteraUiState {
        val several = data.portfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE
        val selected = data.portfolios.firstOrNull { several && it.id == data.storedSelection }
        val scope = data.holdings.inPortfolio(selected?.id)
        val effectiveFilter = data.storedFilter.effectiveFor(scope)
        val holdings = scope.matching(effectiveFilter)
        val filter = filterUi(scope, effectiveFilter)
        val namesById = data.portfolios.associate { it.id to it.name }.takeIf { several && selected == null }
        return CarteraUiState(
            // Closed positions go after the open ones, each group keeping its order.
            rows = holdings.map { it.toRow(data.rates, namesById?.get(it.portfolioId)) }.sortedBy { it.isClosed },
            total = portfolioTotalEur(holdings, data.rates),
            countLabel = countLabel(scope.size),
            isRefreshing = refreshState.running,
            refreshMessage = refreshState.message,
            isEmpty = scope.isEmpty(),
            portfolios = data.portfolios.toOptions(),
            selectedPortfolioId = selected?.id,
            scopeLabel = if (several) selected?.name ?: ALL_PORTFOLIOS_LABEL else null,
            showSelector = several,
            dialog = dialogState?.let(::dialogUi),
            filter = filter,
            filterSummary = filterSummaryText(holdings.size, scope.size).takeIf { filter.active.isNotEmpty() },
            noMatches = scope.isNotEmpty() && holdings.isEmpty(),
            // The only portfolio is the scope even though nothing is "selected" with one.
            rebalanceBanner = rebalanceBanner(
                portfolios = data.portfolios,
                holdings = data.holdings,
                rates = data.rates,
                targets = data.targets,
                scopePortfolioId = if (several) selected?.id else data.portfolios.singleOrNull()?.id,
            ),
        )
    }

    private fun dialogUi(dialogState: CreateDialogState) = PortfolioDialogUi.Name(
        title = "Nueva cartera",
        confirmLabel = "Crear",
        name = dialogState.name,
        error = dialogState.error,
    )

    private fun PortfolioHolding.toRow(rates: FxRates, portfolioName: String?): HoldingRowUi {
        val closed = (valuation as? HoldingValuation.Quoted)?.isClosed == true
        val (valueText, valueEurText) = if (closed) formatEur(Money.ZERO) to null else holdingValueTexts(this, rates)
        val assetClass = effectiveAssetClass()
        val assetClassText = assetClass?.let(::assetClassLabel)
        val typeLabel = instrumentTypeLabel(type)
        val tail = if (closed) CLOSED_TEXT else holdingRowTail(this, zoneProvider())
        return HoldingRowUi(
            id = id,
            name = name,
            typeLabel = typeLabel,
            broker = broker,
            meta = holdingMetaLine(typeLabel, assetClassText, broker, portfolioName, tail),
            valueText = valueText,
            valueEurText = valueEurText,
            portfolioName = portfolioName,
            assetClass = assetClass,
            assetClassText = assetClassText,
            isClosed = closed,
        )
    }
}

private const val REFRESH_FAILED = "No se pudieron actualizar los precios"

internal fun countLabel(count: Int): String = when (count) {
    0 -> "Sin productos"
    1 -> "1 producto"
    else -> "$count productos"
}

/** `null` when every price came through; otherwise which holding (or how many) could not be updated. */
private fun refreshMessage(result: PriceRefreshResult): String? = when (result.failures.size) {
    0 -> null
    1 -> "No se pudo actualizar el precio de ${result.failures.single().holdingName}"
    else -> "No se pudo actualizar el precio de ${result.failures.size} productos"
}
