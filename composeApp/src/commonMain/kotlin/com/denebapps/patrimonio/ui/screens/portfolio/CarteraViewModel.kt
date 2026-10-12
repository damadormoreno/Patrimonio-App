package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.portfolioTotalEur
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InvalidPortfolioNameException
import com.denebapps.patrimonio.domain.model.MAX_PORTFOLIO_NAME_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PortfolioNameProblem
import com.denebapps.patrimonio.domain.repository.DuplicatePortfolioNameException
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LastPortfolioException
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioNotEmptyException
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
import kotlinx.datetime.TimeZone

private const val CARTERA_STOP_TIMEOUT_MS = 5_000L

private const val ALL_PORTFOLIOS_LABEL = "Todas"
private const val LAST_PORTFOLIO_TEXT = "Necesitas al menos una cartera."

/**
 * One line of the Cartera list: texts ready to show. [valueEurText] is only set for a holding not in euros, [broker]
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
    val detail: String,
    val valueText: String,
    val valueEurText: String?,
    val portfolioName: String? = null,
    val assetClass: AssetClass? = null,
    val assetClassText: String? = null,
    val isClosed: Boolean = false,
)

/**
 * A dialog of portfolio management. [Name] creates or renames a portfolio ([name] is what the field holds); [Delete]
 * confirms deleting one and, when it has holdings, asks which of the other portfolios ([targets]) receives them
 * ([targetId] is the chosen one). [error] is the reason the last confirmation was refused, ready to show.
 */
sealed interface PortfolioDialogUi {
    val error: String?

    data class Name(
        val title: String,
        val confirmLabel: String,
        val name: String,
        override val error: String?,
    ) : PortfolioDialogUi

    data class Delete(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val targets: List<PortfolioOptionUi>,
        val targetId: String?,
        override val error: String?,
    ) : PortfolioDialogUi
}

/**
 * The Cartera tab: the [rows] of the current scope, their [total] in EUR, a [countLabel] for the header, whether a
 * price refresh is running and, once one ended with failures, the short [refreshMessage] to show.
 *
 * The scope is every portfolio ("Todas", [selectedPortfolioId] `null`) or one of them. [portfolios] lists them in order
 * for the selector, which is only shown ([showSelector]) with several of them; [scopeLabel] names the scope then.
 * [managedPortfolioId] is the portfolio the rename and delete actions apply to: the selected one, or the only one;
 * `null` while all are shown. When the only portfolio cannot be deleted, [deleteBlockedReason] says why. [dialog] is
 * the management dialog being shown, if any.
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
    val managedPortfolioId: String? = null,
    val deleteBlockedReason: String? = null,
    val dialog: PortfolioDialogUi? = null,
)

/** What the user is told about the last price refresh, kept apart from the data so a refresh never touches rows. */
private data class RefreshState(val running: Boolean = false, val message: String? = null)

/** What the repositories and the preference say; the part of the state that does not come from the user's taps. */
private data class CarteraData(
    val holdings: List<PortfolioHolding> = emptyList(),
    val portfolios: List<Portfolio> = emptyList(),
    val storedSelection: String? = null,
    val rates: FxRates = FxRates(emptyMap()),
)

/** The management dialog as the ViewModel keeps it; [CarteraUiState.dialog] is derived from it and the data. */
private sealed interface DialogState {
    data class Create(val name: String = "", val error: String? = null) : DialogState

    data class Rename(val portfolioId: String, val name: String, val error: String? = null) : DialogState

    data class Delete(val portfolioId: String, val targetId: String? = null, val error: String? = null) : DialogState
}

/**
 * Cartera tab: the holdings of the selected portfolio (or of all) valued at their latest price, their total in EUR and
 * the manual price refresh. The selection is remembered through [PreferencesRepository]; one that names a portfolio
 * that no longer exists reads as "Todas". Portfolios are created, renamed and deleted through dialogs the ViewModel
 * drives ([onNewPortfolio], [onRenamePortfolio], [onDeletePortfolio] and the `onDialog...` actions).
 *
 * The repository already refreshes stale prices on start; [onRefresh] forces every quoted holding, shows progress
 * through [CarteraUiState.isRefreshing] and reports failures without blocking anything.
 */
class CarteraViewModel(
    private val portfolioRepository: PortfolioRepository,
    private val portfolios: PortfolioCollectionRepository,
    private val preferences: PreferencesRepository,
    fxRepository: FxRepository,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val refresh = MutableStateFlow(RefreshState())
    private val dialog = MutableStateFlow<DialogState?>(null)
    private var confirming = false

    private val repositoryData = combine(
        portfolioRepository.observeAll(),
        portfolios.observePortfolios(),
        preferences.observeSelectedPortfolioId(),
        fxRepository.observeRates(),
        ::CarteraData,
    )

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

    // ---- management ----------------------------------------------------------------------------------------------

    /** Opens the dialog that asks for the name of a new portfolio. */
    fun onNewPortfolio() {
        dialog.value = DialogState.Create()
    }

    /** Opens the dialog that renames [portfolioId]; nothing happens if it does not exist. */
    fun onRenamePortfolio(portfolioId: String) {
        viewModelScope.launch {
            val portfolio = portfolios.observePortfolios().first().firstOrNull { it.id == portfolioId } ?: return@launch
            dialog.value = DialogState.Rename(portfolio.id, portfolio.name)
        }
    }

    /** Opens the confirmation to delete [portfolioId]; nothing happens for the last portfolio or one that is gone. */
    fun onDeletePortfolio(portfolioId: String) {
        viewModelScope.launch {
            val all = portfolios.observePortfolios().first()
            if (all.size < MIN_PORTFOLIOS_TO_CHOOSE || all.none { it.id == portfolioId }) return@launch
            dialog.value = DialogState.Delete(portfolioId)
        }
    }

    /** What the name field of the create and rename dialogs holds; a refused name's error goes away. */
    fun onDialogNameChange(text: String) {
        dialog.update { current ->
            when (current) {
                is DialogState.Create -> current.copy(name = text, error = null)
                is DialogState.Rename -> current.copy(name = text, error = null)
                else -> current
            }
        }
    }

    /** The portfolio that receives the holdings of the one being deleted. */
    fun onDeleteTargetChange(portfolioId: String) {
        dialog.update { current ->
            if (current is DialogState.Delete) {
                current.copy(
                    targetId = portfolioId,
                )
            } else {
                current
            }
        }
    }

    fun onDialogDismiss() {
        dialog.value = null
    }

    /** Does what the open dialog asks; a refusal keeps the dialog open with its reason in [CarteraUiState.dialog]. */
    fun onDialogConfirm() {
        val current = dialog.value ?: return
        if (confirming) return
        confirming = true
        viewModelScope.launch {
            try {
                confirm(current)
                dialog.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dialog.update { open ->
                    if (open == current) {
                        current.withError(
                            portfolioErrorMessage(failure),
                        )
                    } else {
                        open
                    }
                }
            } finally {
                confirming = false
            }
        }
    }

    private suspend fun confirm(current: DialogState) {
        when (current) {
            is DialogState.Create -> {
                val created = portfolios.createPortfolio(current.name)
                preferences.setSelectedPortfolioId(created.id)
            }
            is DialogState.Rename -> portfolios.renamePortfolio(current.portfolioId, current.name)
            is DialogState.Delete -> deletePortfolio(current)
        }
    }

    private suspend fun deletePortfolio(current: DialogState.Delete) {
        val others = portfolios.observePortfolios().first().filter { it.id != current.portfolioId }
        val holdingCount = portfolioRepository.observeAll().first().count { it.portfolioId == current.portfolioId }
        val target = if (holdingCount > 0) targetOf(others, current.targetId) else null
        portfolios.deletePortfolio(current.portfolioId, target)
        // The selected portfolio is gone: back to all of them.
        if (preferences.observeSelectedPortfolioId().first() == current.portfolioId) {
            preferences.setSelectedPortfolioId(null)
        }
    }

    // ---- state ---------------------------------------------------------------------------------------------------

    private fun buildState(data: CarteraData, refreshState: RefreshState, dialogState: DialogState?): CarteraUiState {
        val several = data.portfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE
        val selected = data.portfolios.firstOrNull { several && it.id == data.storedSelection }
        val holdings = data.holdings.inPortfolio(selected?.id)
        val namesById = data.portfolios.associate { it.id to it.name }.takeIf { several && selected == null }
        val managed = selected ?: data.portfolios.singleOrNull()
        return CarteraUiState(
            // Closed positions go after the open ones, each group keeping its order.
            rows = holdings.map { it.toRow(data.rates, namesById?.get(it.portfolioId)) }.sortedBy { it.isClosed },
            total = portfolioTotalEur(holdings, data.rates),
            countLabel = countLabel(holdings.size),
            isRefreshing = refreshState.running,
            refreshMessage = refreshState.message,
            isEmpty = holdings.isEmpty(),
            portfolios = data.portfolios.toOptions(),
            selectedPortfolioId = selected?.id,
            scopeLabel = if (several) selected?.name ?: ALL_PORTFOLIOS_LABEL else null,
            showSelector = several,
            managedPortfolioId = managed?.id,
            deleteBlockedReason = LAST_PORTFOLIO_TEXT.takeIf { managed != null && !several },
            dialog = dialogState?.let { dialogUi(it, data) },
        )
    }

    private fun dialogUi(dialogState: DialogState, data: CarteraData): PortfolioDialogUi? = when (dialogState) {
        is DialogState.Create -> PortfolioDialogUi.Name(
            title = "Nueva cartera",
            confirmLabel = "Crear",
            name = dialogState.name,
            error = dialogState.error,
        )
        is DialogState.Rename ->
            if (data.portfolios.none { it.id == dialogState.portfolioId }) {
                null
            } else {
                PortfolioDialogUi.Name(
                    title = "Renombrar cartera",
                    confirmLabel = "Guardar",
                    name = dialogState.name,
                    error = dialogState.error,
                )
            }
        is DialogState.Delete -> deleteDialogUi(dialogState, data)
    }

    private fun deleteDialogUi(dialogState: DialogState.Delete, data: CarteraData): PortfolioDialogUi? {
        val portfolio = data.portfolios.firstOrNull { it.id == dialogState.portfolioId } ?: return null
        val others = data.portfolios.filter { it.id != portfolio.id }
        val holdingCount = data.holdings.count { it.portfolioId == portfolio.id }
        val withHoldings = holdingCount > 0
        return PortfolioDialogUi.Delete(
            title = "Eliminar «${portfolio.name}»",
            message = if (withHoldings) movedHoldingsText(holdingCount) else "Se eliminará la cartera.",
            confirmLabel = if (withHoldings) "Mover y eliminar" else "Eliminar",
            targets = if (withHoldings) others.toOptions() else emptyList(),
            targetId = if (withHoldings) targetOf(others, dialogState.targetId) else null,
            error = dialogState.error,
        )
    }

    private fun PortfolioHolding.toRow(rates: FxRates, portfolioName: String?): HoldingRowUi {
        val closed = (valuation as? HoldingValuation.Quoted)?.isClosed == true
        val (valueText, valueEurText) = if (closed) formatEur(Money.ZERO) to null else holdingValueTexts(this, rates)
        return HoldingRowUi(
            id = id,
            name = name,
            typeLabel = instrumentTypeLabel(type),
            broker = broker,
            detail = if (closed) CLOSED_TEXT else holdingDetailLine(this, zoneProvider()),
            valueText = valueText,
            valueEurText = valueEurText,
            portfolioName = portfolioName,
            assetClass = effectiveAssetClass(),
            assetClassText = effectiveAssetClass()?.let(::assetClassLabel),
            isClosed = closed,
        )
    }
}

/** The chosen target when it is still one of the [others]; the first of them otherwise. */
private fun targetOf(others: List<Portfolio>, chosenId: String?): String? =
    others.firstOrNull { it.id == chosenId }?.id ?: others.firstOrNull()?.id

private fun movedHoldingsText(count: Int): String =
    if (count == 1) "Su producto pasará a:" else "Sus $count productos pasarán a:"

private fun DialogState.withError(message: String): DialogState = when (this) {
    is DialogState.Create -> copy(error = message)
    is DialogState.Rename -> copy(error = message)
    is DialogState.Delete -> copy(error = message)
}

/** Why a portfolio change was refused, in words for the user. */
private fun portfolioErrorMessage(failure: Exception): String = when (failure) {
    is DuplicatePortfolioNameException -> "Ya tienes una cartera con ese nombre."
    is InvalidPortfolioNameException -> when (failure.problem) {
        PortfolioNameProblem.BLANK -> "Escribe un nombre para la cartera."
        PortfolioNameProblem.TOO_LONG -> "El nombre no puede tener más de $MAX_PORTFOLIO_NAME_LENGTH caracteres."
    }
    is LastPortfolioException -> LAST_PORTFOLIO_TEXT
    is PortfolioNotEmptyException -> "Elige a qué cartera pasan sus productos."
    else -> "No se pudo guardar el cambio."
}

private const val CLOSED_TEXT = "Cerrada"
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
