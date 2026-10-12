package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val POSITION_MOVEMENTS_STOP_TIMEOUT_MS = 5_000L
private const val MANUAL_MOVEMENTS_TEXT = "Este producto se valora a mano: no tiene movimientos."

/** The movements of one calendar [year], newest first. */
data class MovementYearUi(val year: Int, val rows: List<MovementRowUi>)

/**
 * The screen with every movement of a position. [holdingName] is the product (the subtitle of the screen) and
 * [groups] its movements by year, the latest year and the latest movement first; [emptyText] says why there are none.
 * [canAdd] is set for a quoted position, the only kind with movements, and [hasUnits] when it holds units. [choice] is
 * the question asked when the first movement is added to a position that already has units.
 */
data class PositionMovementsUiState(
    val loaded: Boolean = false,
    val holdingName: String = "",
    val groups: List<MovementYearUi> = emptyList(),
    val emptyText: String? = null,
    val canAdd: Boolean = false,
    val hasUnits: Boolean = false,
    val choice: OpeningChoiceUi? = null,
)

/**
 * Every movement of the holding [holdingId], newest first and grouped by year, which can be opened to edit (a transfer
 * leg opens its transfer) and added to. The list is the same the position detail summarises and follows the
 * repository, so an edit or an added movement shows up here. [navigateBack] emits once if the holding disappears.
 */
class PositionMovementsViewModel(
    private val holdingId: String,
    portfolioRepository: PortfolioRepository,
    private val movementRepository: MovementRepository,
) : ViewModel() {
    private val navigateChannel = Channel<PositionNavigation>(Channel.BUFFERED)

    /** One-shot requests to open a movement or transfer sheet. */
    val navigate: Flow<PositionNavigation> = navigateChannel.receiveAsFlow()

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to leave the screen. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val addPrompt = AddMovementPrompt { navigateChannel.trySend(it) }

    val state: StateFlow<PositionMovementsUiState> = combine(
        portfolioRepository.observeAll(),
        movementRepository.observeAllMovements(),
        addPrompt.step,
    ) { holdings, movements, step ->
        holdings.firstOrNull { it.id == holdingId }?.let { buildState(it, holdings, movements, step) }
            ?: PositionMovementsUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(POSITION_MOVEMENTS_STOP_TIMEOUT_MS),
        initialValue = PositionMovementsUiState(),
    )

    init {
        // Gone meanwhile (deleted elsewhere, or never existed): there is nothing left to show.
        viewModelScope.launch {
            portfolioRepository.observeAll().first { holdings -> holdings.none { it.id == holdingId } }
            navigateBackChannel.trySend(Unit)
        }
    }

    /** "Añadir movimiento": asks how to start for a position with units but no movements, otherwise opens the form. */
    fun onAddMovement() {
        val current = state.value
        if (!current.canAdd) return
        addPrompt.onAdd(needsChoice = current.groups.isEmpty() && current.hasUnits)
    }

    /** The user keeps the units the position has as its opening balance. */
    fun onChooseOpeningBalance() = addPrompt.onChooseOpeningBalance()

    /** The user will enter the purchases one by one; shows what happens to the units they have now. */
    fun onChooseOneByOne() = addPrompt.onChooseOneByOne()

    /** The user accepts that the units will come from the movements and goes on to the first one. */
    fun onConfirmOneByOne() = addPrompt.onConfirmOneByOne()

    fun onDismissChoice() = addPrompt.onDismiss()

    /** Opens the sheet that edits the movement [movementId]; a transfer leg opens its transfer. */
    fun onOpenMovement(movementId: String) {
        viewModelScope.launch {
            val movement = movementRepository.observeMovements(holdingId).first().firstOrNull { it.id == movementId }
                ?: return@launch
            navigateChannel.send(movement.editTarget())
        }
    }

    private fun buildState(
        holding: PortfolioHolding,
        holdings: List<PortfolioHolding>,
        movements: List<Movement>,
        step: OpeningChoiceStep?,
    ): PositionMovementsUiState {
        val valuation = holding.valuation as? HoldingValuation.Quoted ?: return PositionMovementsUiState(
            loaded = true,
            holdingName = holding.name,
            emptyText = MANUAL_MOVEMENTS_TEXT,
        )
        val groups = movementYears(
            movements = movements.filter { it.holdingId == holding.id },
            currency = valuation.currency,
            counterpartName = counterpartNames(holdings, movements),
        )
        return PositionMovementsUiState(
            loaded = true,
            holdingName = holding.name,
            groups = groups,
            emptyText = EMPTY_MOVEMENTS_TEXT.takeIf { groups.isEmpty() },
            canAdd = true,
            hasUnits = !valuation.isClosed,
            choice = step?.let { openingChoiceUi(holding, it) },
        )
    }
}
