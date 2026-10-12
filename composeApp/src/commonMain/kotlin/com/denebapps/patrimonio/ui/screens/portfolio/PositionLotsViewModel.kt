package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.MovementValidationException
import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Lot
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val POSITION_LOTS_STOP_TIMEOUT_MS = 5_000L
private const val NO_LOTS_CLOSED_TEXT = "No hay lotes abiertos: la posición está cerrada."
private const val NO_LOTS_TEXT = "No hay lotes abiertos: las participaciones no vienen de movimientos."

/**
 * The screen with every open lot of a position. [holdingName] is the product (the subtitle of the screen), [lots] the
 * open lots oldest first and [note] what to know about the ones received in a transfer; [emptyText] says why there
 * are none.
 */
data class PositionLotsUiState(
    val loaded: Boolean = false,
    val holdingName: String = "",
    val lots: List<LotUi> = emptyList(),
    val note: String? = null,
    val emptyText: String? = null,
)

/**
 * Every open lot of the holding [holdingId], oldest first, as the ledger of the movements of the app gives them (it
 * replays them all, so it runs on [computationDispatcher]). It follows the repository, so an edit or an added movement
 * shows up here. [navigateBack] emits once if the holding disappears.
 */
class PositionLotsViewModel(
    private val holdingId: String,
    portfolioRepository: PortfolioRepository,
    movementRepository: MovementRepository,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to leave the screen. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    val state: StateFlow<PositionLotsUiState> = combine(
        portfolioRepository.observeAll(),
        movementRepository.observeAllMovements(),
    ) { holdings, movements ->
        holdings.firstOrNull { it.id == holdingId }?.let { buildState(it, movements) } ?: PositionLotsUiState()
    }.flowOn(computationDispatcher).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(POSITION_LOTS_STOP_TIMEOUT_MS),
        initialValue = PositionLotsUiState(),
    )

    init {
        // Gone meanwhile (deleted elsewhere, or never existed): there is nothing left to show.
        viewModelScope.launch {
            portfolioRepository.observeAll().first { holdings -> holdings.none { it.id == holdingId } }
            navigateBackChannel.trySend(Unit)
        }
    }

    private fun buildState(holding: PortfolioHolding, movements: List<Movement>): PositionLotsUiState {
        val own = movements.filter { it.holdingId == holding.id }
        val loaded = PositionLotsUiState(loaded = true, holdingName = holding.name)
        val isQuoted = holding.valuation is HoldingValuation.Quoted
        val lots = if (isQuoted && own.isNotEmpty()) openLots(holding.id, movements) else null
        return when {
            lots == null -> loaded.copy(emptyText = NO_LOTS_TEXT)
            lots.isEmpty() -> loaded.copy(emptyText = NO_LOTS_CLOSED_TEXT)
            else -> loaded.copy(lots = lots.map(::lotUi), note = lotsTransferNote(own))
        }
    }

    /** The open lots of [holdingId], oldest first; `null` for a history that cannot be replayed. */
    private fun openLots(holdingId: String, movements: List<Movement>): List<Lot>? = try {
        computeLedgers(movements)[holdingId]?.lots.orEmpty()
    } catch (invalid: MovementValidationException) {
        null
    } catch (overflow: ArithmeticException) {
        null
    }
}
