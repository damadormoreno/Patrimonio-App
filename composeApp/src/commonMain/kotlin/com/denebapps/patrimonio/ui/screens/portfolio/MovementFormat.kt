package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.MovementValidationException
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.repository.HoldingHasMovementsException
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.InvalidMovementException
import com.denebapps.patrimonio.domain.repository.MovementNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import com.denebapps.patrimonio.ui.components.formatMoney

// Pure text of the movements (es-ES, neutral Spanish): the rows of the list and the reasons a write was refused.

private const val MINUS = "−"

/** Shown instead of the cost of an opening balance whose cost the user does not know. */
internal const val UNKNOWN_COST_TEXT = "coste desconocido"

/**
 * One row of the movements list, every text ready to show. [unitsText] carries its sign ("+10,00 part.") and is `null`
 * for the movements that have no units; [amountText] is `null` for a transfer, which carries no amount.
 */
data class MovementRowUi(
    val id: String,
    val title: String,
    val dateText: String,
    val unitsText: String?,
    val amountText: String?,
    val noteText: String?,
)

/** Spanish name of a movement type; a transfer is just "Traspaso" (its row says to or from which fund). */
internal fun movementTypeLabel(type: MovementType): String = when (type) {
    MovementType.BUY -> "Compra"
    MovementType.SELL -> "Venta"
    MovementType.OPENING_BALANCE -> "Saldo inicial"
    MovementType.DIVIDEND -> "Dividendo"
    MovementType.FEE -> "Comisión"
    MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN -> "Traspaso"
}

/**
 * The rows for [movements] (given in the order they happened), newest first. Amounts are shown in the holding
 * [currency]. [counterpartName] names the fund on the other side of a transfer leg (`null` when it is not known).
 */
internal fun movementRows(
    movements: List<Movement>,
    currency: Currency,
    counterpartName: (Movement) -> String?,
): List<MovementRowUi> = movements.asReversed().map { movement ->
    MovementRowUi(
        id = movement.id,
        title = movementTitle(movement, counterpartName(movement)),
        dateText = formatShortDate(movement.date),
        unitsText = signedUnitsText(movement),
        amountText = movementAmountText(movement, currency),
        noteText = movement.note,
    )
}

private fun movementTitle(movement: Movement, counterpart: String?): String = when {
    counterpart == null -> movementTypeLabel(movement.type)
    movement.type == MovementType.TRANSFER_OUT -> "Traspaso a «$counterpart»"
    movement.type == MovementType.TRANSFER_IN -> "Traspaso desde «$counterpart»"
    else -> movementTypeLabel(movement.type)
}

/** "+10,00 part." for units that come in, "−5,00 part." for units that go out; `null` without units. */
private fun signedUnitsText(movement: Movement): String? {
    val units = movement.units ?: return null
    val sign = when (movement.type) {
        MovementType.SELL, MovementType.TRANSFER_OUT -> MINUS
        else -> "+"
    }
    return "$sign${formatUnits(units)} part."
}

private fun movementAmountText(movement: Movement, currency: Currency): String? {
    val amount = movement.amount
    return when (movement.type) {
        MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN -> null
        MovementType.OPENING_BALANCE -> amount?.let { formatMoney(it.amount, currency) } ?: UNKNOWN_COST_TEXT
        MovementType.BUY, MovementType.SELL -> amount?.let {
            val base = formatMoney(it.amount, currency)
            if (movement.fee > Money.ZERO) "$base · comisión ${formatMoney(movement.fee, currency)}" else base
        }
        MovementType.DIVIDEND, MovementType.FEE -> amount?.let { formatMoney(it.amount, currency) }
    }
}

/** Why a movement write was refused, in words for the user. */
internal fun movementErrorMessage(failure: Exception): String = when (failure) {
    is MovementValidationException -> failure.message.orEmpty()
    is InvalidMovementException -> failure.message.orEmpty()
    is HoldingHasMovementsException -> failure.message.orEmpty()
    is HoldingHasTransfersException -> failure.message.orEmpty()
    is MovementNotFoundException, is PortfolioHoldingNotFoundException, is PortfolioNotFoundException ->
        "Este movimiento o su producto ya no existe."
    else -> "No se pudo guardar el cambio."
}
