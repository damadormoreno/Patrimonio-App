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

/**
 * The movement sheet, every text ready to show. [choices] is empty for an opening balance; [choice] is the selected
 * one. [showUnits], [showFee] and [showEur] say which fields the type needs; [amountLabel] names the amount field.
 * [amountText] and [eurText] are what the two amount fields hold: what the user typed in each, or what follows from the
 * other at the rate of the day. [eurHint] and [amountHint] say which of them needs to be typed because that day has no
 * rate, [impliedPriceText] is the price per unit the amount and units come to and [impliedRateText] the rate the two
 * amounts come to. [amountPlaceholder] and [eurPlaceholder] are the value a field follows, to show muted inside it while
 * it is empty (a field held blank while the user edits it). [feeText] is in euros when [feeInEur] (the default for a
 * holding in another currency, which shows the switch [showFeeCurrency]) and in the holding currency otherwise;
 * [feePreviewText] is the fee in the other one. [error] is why the last save or delete was refused, [canDelete] is set
 * when editing and [deleteMessage] is what the confirmation says.
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
    val feeInEur: Boolean = false,
    val showFeeCurrency: Boolean = false,
    val feePreviewText: String? = null,
    val eurText: String = "",
    val noteText: String = "",
    val showUnits: Boolean = true,
    val showFee: Boolean = true,
    val showEur: Boolean = false,
    val eurHint: String? = null,
    val amountHint: String? = null,
    val amountPlaceholder: String? = null,
    val eurPlaceholder: String? = null,
    val impliedPriceText: String? = null,
    val impliedRateText: String? = null,
    val error: String? = null,
    val canSave: Boolean = false,
    val canDelete: Boolean = false,
    val deleteMessage: String = "",
) {
    /** The currency the fee field is typed in: euros when [feeInEur], else the holding [currency]. */
    val feeCurrency: Currency get() = if (feeInEur) Currency.EUR else currency
}

/** What the position and its history say, once loaded: it does not change while the sheet is open. */
private class FormContext(
    val holdingName: String,
    val currency: Currency,
    val isFund: Boolean,
    val editing: Movement?,
    val isOpening: Boolean,
    val movementCount: Int,
)

/** The fee field of a stored movement: its [text] in the currency it is shown in, euros when [inEur]. */
private class StoredFee(val text: String, val inEur: Boolean)

/** What the user typed or picked; an amount field that is not in [amounts] follows the other one at the day rate. */
private data class Typed(
    val choice: MovementChoice = MovementChoice.BUY,
    val date: LocalDate = LocalDate(1970, 1, 1),
    val unitsText: String = "",
    val amounts: TypedAmounts = TypedAmounts(),
    val feeText: String = "",
    val feeInEur: Boolean = true,
    val noteText: String = "",
)

/**
 * The sheet that adds, edits and deletes a movement of the quoted holding [holdingId]: buys, sells, dividends and fees,
 * or (with [openingBalance], or when [movementId] is one) the opening balance of a position that already had units.
 * A holding in another currency has two amounts, in its currency and in euros: the user types either one and the other
 * follows from the rate of that day (downloaded when the date asks for it), or types both and both are kept. What the repository refuses (selling more than is held, a movement
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
        change { it.copy(choice = choice) }
    }

    fun onDateChange(date: LocalDate) {
        change { it.copy(date = date) }
        context?.let { fx.ensure(it.currency, date) }
    }

    fun onUnitsChange(text: String) = change { it.copy(unitsText = text) }

    /** The amount as typed; a blank field follows the euro equivalent again. */
    fun onAmountChange(text: String) = change { it.copy(amounts = it.amounts.withAmount(text)) }

    fun onFeeChange(text: String) = change { it.copy(feeText = text) }

    /**
     * The fee is typed in euros (`true`) or in the currency of a holding that is not in euros. The typed number stays as
     * it is and is read in the new currency, so no rounding creeps in by switching back and forth.
     */
    fun onFeeCurrencyChange(inEur: Boolean) {
        if (context?.currency == Currency.EUR) return
        change { it.copy(feeInEur = inEur) }
    }

    /** The euro equivalent as typed; a blank field follows the amount again. */
    fun onEurChange(text: String) = change { it.copy(amounts = it.amounts.withEur(text)) }

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
        // Any input but the amount fields lets one held blank follow the other again.
        typed = transform(typed.copy(amounts = typed.amounts.settled()))
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

    private fun typedFrom(movement: Movement, currency: Currency): Typed {
        val fee = storedFee(movement, currency)
        return Typed(
            choice = when (movement.type) {
                MovementType.SELL -> MovementChoice.SELL
                MovementType.DIVIDEND -> MovementChoice.DIVIDEND
                MovementType.FEE -> MovementChoice.FEE
                else -> MovementChoice.BUY
            },
            date = movement.date,
            unitsText = movement.units?.let(::unitsInputText).orEmpty(),
            // Both amounts that were recorded are kept as typed: nothing is recomputed unless the user blanks one.
            amounts = TypedAmounts(
                amount = movement.amount?.let { amountInputText(it.amount, currency) },
                eur = movement.amountEur
                    ?.takeIf { currency != Currency.EUR }
                    ?.let { amountInputText(it, Currency.EUR) },
            ),
            feeText = fee.text,
            feeInEur = fee.inEur,
            noteText = movement.note.orEmpty(),
        )
    }

    /** The fee field of a stored movement: which currency it shows ([feeWasTypedInEur]) and the text in it. */
    private fun storedFee(movement: Movement, currency: Currency): StoredFee {
        val inEur = feeWasTypedInEur(
            currency = currency,
            fee = movement.fee,
            feeEur = movement.feeEur,
            amount = movement.amount?.amount,
            amountEur = movement.amountEur,
        )
        val shown = if (inEur) movement.feeEur else movement.fee
        val text = shown.takeIf { it.minorUnits > 0 }?.let {
            amountInputText(
                it,
                if (inEur) Currency.EUR else currency,
            )
        }
        return StoredFee(text.orEmpty(), inEur)
    }

    private fun close() {
        navigateBackChannel.trySend(Unit)
    }

    private fun typeOf(form: FormContext): MovementType =
        if (form.isOpening) MovementType.OPENING_BALANCE else typed.choice.movementType ?: MovementType.BUY

    /** The rate of the day of a holding in another currency, `null` when it has none stored. */
    private fun rateOf(form: FormContext): Long? =
        form.currency.takeIf { it != Currency.EUR }?.let { fx.rateOn(it, typed.date) }

    /** Whether the fee field is read in euros: only a holding in another currency has the choice. */
    private fun feeInEurOf(form: FormContext): Boolean = form.currency != Currency.EUR && typed.feeInEur

    private fun entry(form: FormContext): MovementEntry = resolveMovementEntry(
        MovementEntryInput(
            type = typeOf(form),
            date = typed.date,
            currency = form.currency,
            unitsText = typed.unitsText,
            amountText = typed.amounts.amount.orEmpty(),
            feeText = typed.feeText,
            eurText = typed.amounts.eur,
            noteText = typed.noteText,
            rateScaled = rateOf(form),
            feeInEur = feeInEurOf(form),
        ),
    )

    private fun render() {
        val form = context ?: return
        val type = typeOf(form)
        val showFee = type == MovementType.BUY || type == MovementType.SELL
        val resolved = entry(form)
        val amountText = typed.amounts.amount ?: resolved.amountPrefillText.orEmpty()
        val eurText = typed.amounts.eur ?: resolved.eurPrefillText.orEmpty()
        val hints = missingRateHints(
            currency = form.currency,
            rateScaled = rateOf(form),
            amountText = typed.amounts.amount.orEmpty(),
            eurText = typed.amounts.eur,
            costOptional = type == MovementType.OPENING_BALANCE,
        )
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
            amountText = amountText,
            amountLabel = if (form.isOpening) "Coste total (opcional)" else "Importe",
            feeText = typed.feeText,
            feeInEur = feeInEurOf(form),
            showFeeCurrency = form.currency != Currency.EUR && showFee,
            feePreviewText = resolved.feePreviewText,
            eurText = eurText,
            noteText = typed.noteText,
            showUnits = type == MovementType.BUY || type == MovementType.SELL || type == MovementType.OPENING_BALANCE,
            showFee = showFee,
            showEur = form.currency != Currency.EUR,
            eurHint = hints.eurHint,
            amountHint = hints.amountHint,
            amountPlaceholder = resolved.amountPrefillText.takeIf { amountText.isEmpty() },
            eurPlaceholder = resolved.eurPrefillText.takeIf { eurText.isEmpty() },
            impliedPriceText = resolved.impliedPriceText,
            impliedRateText = resolved.impliedRateText,
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
