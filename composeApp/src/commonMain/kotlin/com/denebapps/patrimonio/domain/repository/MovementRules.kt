package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement

// The rules every MovementRepository applies before the ledger sees a movement, shared by the Room implementation and
// the test fakes so that they cannot drift apart.

/**
 * The [Movement] that [draft] describes on a holding in [holdingCurrency]: a holding in EUR gets its EUR amounts from
 * the amounts themselves, any other currency must bring them.
 *
 * @throws InvalidMovementException when the amount is not in [holdingCurrency], the note is too long, or the draft
 *   lacks what its type needs.
 * @throws MissingEurEquivalentException when the holding is not in EUR and the EUR amount (or the EUR fee of a fee that
 *   is charged) is missing.
 */
fun buildMovement(
    draft: MovementDraft,
    id: String,
    holdingId: String,
    holdingCurrency: Currency,
    sequence: Long,
    transferId: String? = null,
): Movement {
    val amount = draft.amount
    if (amount != null && amount.currency != holdingCurrency) {
        throw InvalidMovementException("El importe debe estar en ${holdingCurrency.code}, la divisa del producto.")
    }
    val amountEur: Money?
    val feeEur: Money
    if (holdingCurrency == Currency.EUR) {
        amountEur = amount?.amount
        feeEur = draft.fee
    } else {
        if ((amount != null && draft.amountEur == null) || (draft.fee > Money.ZERO && draft.feeEur == null)) {
            throw MissingEurEquivalentException()
        }
        amountEur = draft.amountEur
        feeEur = draft.feeEur ?: Money.ZERO
    }
    return try {
        Movement(
            id = id,
            holdingId = holdingId,
            type = draft.type,
            date = draft.date,
            units = draft.units,
            amount = amount,
            fee = draft.fee,
            amountEur = amountEur,
            feeEur = feeEur,
            transferId = transferId,
            note = normalizeMovementNote(draft.note),
            sequence = sequence,
        )
    } catch (error: IllegalArgumentException) {
        if (error is InvalidMovementException) throw error
        throw InvalidMovementException("El movimiento no está completo o no es válido.", error)
    }
}

/** The note as it is stored: trimmed, `null` when nothing is left. */
fun normalizeMovementNote(note: String?): String? {
    val trimmed = note?.trim().orEmpty()
    if (trimmed.length > MAX_MOVEMENT_NOTE_LENGTH) {
        throw InvalidMovementException("La nota admite como máximo $MAX_MOVEMENT_NOTE_LENGTH caracteres.")
    }
    return trimmed.ifEmpty { null }
}

/** The error of a holding that takes no movements: only quoted ones do (a manual holding has no units). */
fun notQuotedMovementError() = InvalidMovementException("Solo los productos con precio de mercado admiten movimientos.")

/** The error of a new holding whose first movements are not all purchases. */
fun notPurchasesOnNewHoldingError() = InvalidMovementException("Un producto nuevo solo puede empezar con compras.")

/** The error of a new history (a new holding's, or the one that replaces another) that has no movement at all. */
fun noMovementsError() = InvalidMovementException("Hace falta al menos un movimiento.")

/**
 * Checks that [fromId] and [toId] can exchange a fund transfer: two different holdings, both quoted funds.
 *
 * @throws TransferNotAllowedException when they cannot.
 */
fun requireTransferBetweenFunds(fromId: String, toId: String, fromIsQuotedFund: Boolean, toIsQuotedFund: Boolean) {
    if (fromId == toId) {
        throw TransferNotAllowedException("El origen y el destino del traspaso son el mismo producto.")
    }
    if (!fromIsQuotedFund || !toIsQuotedFund) {
        throw TransferNotAllowedException("Los traspasos solo se pueden hacer entre fondos de inversión.")
    }
}

/** Whether a holding of [type] that is quoted ([isQuoted]) can take part in a fund transfer. */
fun isQuotedFund(type: InstrumentType, isQuoted: Boolean): Boolean = isQuoted && type == InstrumentType.FUND
