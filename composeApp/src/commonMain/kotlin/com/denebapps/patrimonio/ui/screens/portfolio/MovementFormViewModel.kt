package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.unitsInputText
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.MAX_MOVEMENT_NOTE_LENGTH
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.isQuotedFund
import com.denebapps.patrimonio.ui.components.amountInputText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** What kind of movement the type selector offers. Transfers are made in their own form. */
enum class MovementChoice(val label: String) {
    BUY("Compra"),
    SELL("Venta"),
    DIVIDEND("Dividendo"),
    FEE("Comisión"),
    TRANSFER("Traspaso"),
    ;

    /** The type recorded for this choice; `null` for a transfer, which is two linked movements. */
    internal val movementType: MovementType?
        get() = when (this) {
            BUY -> MovementType.BUY
            SELL -> MovementType.SELL
            DIVIDEND -> MovementType.DIVIDEND
            FEE -> MovementType.FEE
            TRANSFER -> null
        }
}

private const val LAST_MOVEMENT_WARNING = "La posición quedará con 0 participaciones y volverá a editarse a mano."
private const val DELETE_WARNING = "Se recalcularán las participaciones con los demás movimientos."
private const val MISSING_EUR_HINT = "No hay cambio de esa fecha: escribe el equivalente en euros."

/**
 * The movement sheet, every text ready to show. [choices] is empty for an opening balance; [choice] is the selected
 * one. [showUnits], [showFee] and [showEur] say which fields the type needs; [amountLabel] names the amount field.
 * [eurText] is what the euro field holds (typed, or the amount at the rate of the day), [eurHint] why it needs to be
 * typed, and [impliedPriceText] the price per unit the amount and units come to. [error] is why the last save or
 * delete was refused, [canDelete] is set when editing and [deleteMessage] is what the confirmation says.
 */
data class MovementFormUiState(
    val loaded: Boolean = false,
    val title: String = "",
    val holdingName: String = "",
    val currency: Currency = Currency.EUR,
    val isOpening: Boolean = false,
    val choices: List<MovementChoice> = emptyList(),
    val choice: MovementChoice = MovementChoice.BUY,
    val date: LocalDate = LocalDate(1970, 1, 1),
    val unitsText: String = "",
    val amountText: String = "",
    val amountLabel: String = "Importe",
    val feeText: String = "",
    val eurText: String = "",
    val noteText: String = "",
    val showUnits: Boolean = true,
    val showFee: Boolean = true,
    val showEur: Boolean = false,
    val eurHint: String? = null,
    val impliedPriceText: String? = null,
    val error: String? = null,
    val canSave: Boolean = false,
    val canDelete: Boolean = false,
    val deleteMessage: String = "",
)

/** What the position and its history say, once loaded: it does not change while the sheet is open. */
private class FormContext(
    val holdingName: String,
    val currency: Currency,
    val isFund: Boolean,
    val editing: Movement?,
    val isOpening: Boolean,
    val movementCount: Int,
)

/** What the user typed or picked; [eurText] is `null` while it follows the rate of the day. */
private data class Typed(
    val choice: MovementChoice = MovementChoice.BUY,
    val date: LocalDate = LocalDate(1970, 1, 1),
    val unitsText: String = "",
    val amountText: String = "",
    val feeText: String = "",
    val eurText: String? = null,
    val noteText: String = "",
)

/**
 * The sheet that adds, edits and deletes a movement of the quoted holding [holdingId]: buys, sells, dividends and fees,
 * or (with [openingBalance], or when [movementId] is one) the opening balance of a position that already had units.
 * A holding in another currency asks for the euro equivalent of the amount, prefilled from the rate of that day,
 * which is downloaded when the date asks for it. What the repository refuses (selling more than is held, a movement
 * before the position started) is shown in [MovementFormUiState.error] and the sheet stays open; a save or a delete
 * closes it through [navigateBack]. Choosing a transfer asks to open the transfer form through [openTransfer].
 */
class MovementFormViewModel(
    private val holdingId: String,
    private val movementId: String?,
    private val openingBalance: Boolean,
    private val portfolioRepository: PortfolioRepository,
    private val movementRepository: MovementRepository,
    fxHistoryRepository: FxHistoryRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MovementFormUiState())
    val state: StateFlow<MovementFormUiState> = mutableState.asStateFlow()

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the sheet, after a save or a delete, or when there is nothing to show. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val openTransferChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot request to continue in the transfer form, emitted when the user chooses a transfer. */
    val openTransfer: Flow<Unit> = openTransferChannel.receiveAsFlow()

    private var context: FormContext? = null
    private var typed = Typed()
    private var errorText: String? = null
    private var busy = false
    private val fx = FxRateLookup(fxHistoryRepository, viewModelScope, ::render)

    init {
        fx.start()
        viewModelScope.launch { load() }
    }

    fun onChoiceChange(choice: MovementChoice) {
        if (choice == MovementChoice.TRANSFER) {
            openTransferChannel.trySend(Unit)
            return
        }
        change { it.copy(choice = choice, eurText = null) }
    }

    fun onDateChange(date: LocalDate) {
        change { it.copy(date = date, eurText = null) }
        context?.let { fx.ensure(it.currency, date) }
    }

    fun onUnitsChange(text: String) = change { it.copy(unitsText = text) }

    fun onAmountChange(text: String) = change { it.copy(amountText = text, eurText = null) }

    fun onFeeChange(text: String) = change { it.copy(feeText = text) }

    /** The euro equivalent as typed; a blank field follows the rate of the day again. */
    fun onEurChange(text: String) = change { it.copy(eurText = text) }

    /** The note holds at most [MAX_MOVEMENT_NOTE_LENGTH] characters. */
    fun onNoteChange(text: String) = change { it.copy(noteText = text.take(MAX_MOVEMENT_NOTE_LENGTH)) }

    fun onSave() {
        val form = context ?: return
        val draft = entry(form).draft ?: return
        if (busy) return
        busy = true
        errorText = null
        render()
        viewModelScope.launch {
            try {
                when {
                    form.editing != null -> movementRepository.updateMovement(form.editing.id, draft)
                    form.isOpening -> movementRepository.addOpeningBalance(
                        holdingId = holdingId,
                        date = draft.date,
                        units = checkNotNull(draft.units),
                        cost = draft.amount,
                        costEur = draft.amountEur,
                        note = draft.note,
                    )
                    else -> movementRepository.addMovement(holdingId, draft)
                }
                navigateBackChannel.send(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure)
            }
        }
    }

    fun onDelete() {
        val editing = context?.editing ?: return
        if (busy) return
        busy = true
        errorText = null
        render()
        viewModelScope.launch {
            try {
                movementRepository.deleteMovement(editing.id)
                navigateBackChannel.send(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure)
            }
        }
    }

    private fun fail(failure: Exception) {
        busy = false
        errorText = movementErrorMessage(failure)
        render()
    }

    private fun change(transform: (Typed) -> Typed) {
        typed = transform(typed)
        errorText = null
        render()
    }

    private suspend fun load() {
        val holding = portfolioRepository.observeAll().first().firstOrNull { it.id == holdingId }
        val valuation = holding?.valuation as? HoldingValuation.Quoted
        if (holding == null || valuation == null) return close()
        val movements = movementRepository.observeMovements(holdingId).first()
        val editing = movementId?.let { id -> movements.firstOrNull { it.id == id } ?: return close() }
        if (editing?.transferId != null) return close()
        val isOpening = editing?.type == MovementType.OPENING_BALANCE || (editing == null && openingBalance)
        val today = clock.todayIn(zoneProvider())
        context = FormContext(
            holdingName = holding.name,
            currency = valuation.currency,
            isFund = isQuotedFund(holding.type, isQuoted = true),
            editing = editing,
            isOpening = isOpening,
            movementCount = movements.size,
        )
        typed = when {
            editing != null -> typedFrom(editing, valuation.currency)
            isOpening -> Typed(date = today, unitsText = unitsInputText(valuation.units))
            else -> Typed(date = today)
        }
        fx.ensure(valuation.currency, typed.date)
        render()
    }

    private fun typedFrom(movement: Movement, currency: Currency) = Typed(
        choice = when (movement.type) {
            MovementType.SELL -> MovementChoice.SELL
            MovementType.DIVIDEND -> MovementChoice.DIVIDEND
            MovementType.FEE -> MovementChoice.FEE
            else -> MovementChoice.BUY
        },
        date = movement.date,
        unitsText = movement.units?.let(::unitsInputText).orEmpty(),
        amountText = movement.amount?.let { amountInputText(it.amount, currency) }.orEmpty(),
        feeText = movement.fee.takeIf { it.minorUnits > 0 }?.let { amountInputText(it, currency) }.orEmpty(),
        // The equivalent that was recorded stands until the amount or the date changes.
        eurText = movement.amountEur?.takeIf { currency != Currency.EUR }?.let { amountInputText(it, Currency.EUR) },
        noteText = movement.note.orEmpty(),
    )

    private fun close() {
        navigateBackChannel.trySend(Unit)
    }

    private fun typeOf(form: FormContext): MovementType =
        if (form.isOpening) MovementType.OPENING_BALANCE else typed.choice.movementType ?: MovementType.BUY

    private fun entry(form: FormContext): MovementEntry {
        val rate = form.currency.takeIf { it != Currency.EUR }?.let { fx.rateOn(it, typed.date) }
        return resolveMovementEntry(
            MovementEntryInput(
                type = typeOf(form),
                date = typed.date,
                currency = form.currency,
                unitsText = typed.unitsText,
                amountText = typed.amountText,
                feeText = typed.feeText,
                eurText = typed.eurText,
                noteText = typed.noteText,
                rateScaled = rate,
            ),
        )
    }

    private fun render() {
        val form = context ?: return
        val type = typeOf(form)
        val resolved = entry(form)
        val inEuros = form.currency == Currency.EUR
        val hasCost = type != MovementType.OPENING_BALANCE || typed.amountText.isNotBlank()
        val showEur = !inEuros && hasCost
        val missingRate = !inEuros && fx.rateOn(form.currency, typed.date) == null && typed.eurText.isNullOrBlank()
        mutableState.value = MovementFormUiState(
            loaded = true,
            title = titleOf(form),
            holdingName = form.holdingName,
            currency = form.currency,
            isOpening = form.isOpening,
            choices = choicesOf(form),
            choice = typed.choice,
            date = typed.date,
            unitsText = typed.unitsText,
            amountText = typed.amountText,
            amountLabel = if (form.isOpening) "Coste total (opcional)" else "Importe",
            feeText = typed.feeText,
            eurText = typed.eurText ?: resolved.eurPrefillText.orEmpty(),
            noteText = typed.noteText,
            showUnits = type == MovementType.BUY || type == MovementType.SELL || type == MovementType.OPENING_BALANCE,
            showFee = type == MovementType.BUY || type == MovementType.SELL,
            showEur = showEur,
            eurHint = MISSING_EUR_HINT.takeIf { showEur && missingRate },
            impliedPriceText = resolved.impliedPriceText,
            error = errorText,
            canSave = resolved.draft != null && !busy,
            canDelete = form.editing != null,
            deleteMessage = if (form.movementCount <= 1) LAST_MOVEMENT_WARNING else DELETE_WARNING,
        )
    }

    private fun titleOf(form: FormContext): String = when {
        form.isOpening && form.editing != null -> "Editar saldo inicial"
        form.isOpening -> "Saldo inicial"
        form.editing != null -> "Editar movimiento"
        else -> "Nuevo movimiento"
    }

    private fun choicesOf(form: FormContext): List<MovementChoice> = when {
        form.isOpening -> emptyList()
        form.isFund && form.editing == null -> MovementChoice.entries
        else -> MovementChoice.entries - MovementChoice.TRANSFER
    }
}
