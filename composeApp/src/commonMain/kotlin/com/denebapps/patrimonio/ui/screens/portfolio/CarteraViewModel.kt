package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.portfolioTotalEur
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PriceRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

private const val CARTERA_STOP_TIMEOUT_MS = 5_000L

/**
 * One line of the Cartera list: texts ready to show. [valueEurText] is only set for a holding not in euros, [broker]
 * for one that has a bank or broker.
 */
data class HoldingRowUi(
    val id: String,
    val name: String,
    val typeLabel: String,
    val broker: String?,
    val detail: String,
    val valueText: String,
    val valueEurText: String?,
)

/**
 * The Cartera tab: the [rows], the [total] in EUR, a [countLabel] for the header, whether a price refresh is running
 * and, once one ended with failures, the short [refreshMessage] to show.
 */
data class CarteraUiState(
    val rows: List<HoldingRowUi>,
    val total: Money,
    val countLabel: String,
    val isRefreshing: Boolean,
    val refreshMessage: String?,
    val isEmpty: Boolean,
)

/** What the user is told about the last price refresh, kept apart from the data so a refresh never touches rows. */
private data class RefreshState(val running: Boolean = false, val message: String? = null)

/**
 * Cartera tab: every holding valued at its latest price, the portfolio total in EUR and the manual price refresh.
 * The repository already refreshes stale prices on start; [onRefresh] forces every quoted holding, shows progress
 * through [CarteraUiState.isRefreshing] and reports failures without blocking anything.
 */
class CarteraViewModel(
    private val portfolioRepository: PortfolioRepository,
    fxRepository: FxRepository,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val refresh = MutableStateFlow(RefreshState())

    val state: StateFlow<CarteraUiState> = combine(
        portfolioRepository.observeAll(),
        fxRepository.observeRates(),
        refresh,
    ) { holdings, rates, refreshState ->
        buildState(holdings, rates, refreshState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(CARTERA_STOP_TIMEOUT_MS),
        initialValue = buildState(emptyList(), FxRates(emptyMap()), RefreshState()),
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

    private fun buildState(holdings: List<PortfolioHolding>, rates: FxRates, refreshState: RefreshState) =
        CarteraUiState(
            rows = holdings.map { it.toRow(rates) },
            total = portfolioTotalEur(holdings, rates),
            countLabel = countLabel(holdings.size),
            isRefreshing = refreshState.running,
            refreshMessage = refreshState.message,
            isEmpty = holdings.isEmpty(),
        )

    private fun PortfolioHolding.toRow(rates: FxRates): HoldingRowUi {
        val (valueText, valueEurText) = holdingValueTexts(this, rates)
        return HoldingRowUi(
            id = id,
            name = name,
            typeLabel = instrumentTypeLabel(type),
            broker = broker,
            detail = holdingDetailLine(this, zoneProvider()),
            valueText = valueText,
            valueEurText = valueEurText,
        )
    }
}

private const val REFRESH_FAILED = "No se pudieron actualizar los precios"

private fun countLabel(count: Int): String = when (count) {
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
