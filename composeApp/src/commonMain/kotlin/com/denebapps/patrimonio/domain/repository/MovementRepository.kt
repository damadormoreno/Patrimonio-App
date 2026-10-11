package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Units
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The movements of the positions: buys, sells, opening balances, fees, dividends and fund transfers. A position with
 * movements gets its units from them ([PortfolioRepository.updateUnits] refuses it), and every write here recomputes
 * the units of the positions it touches and the current month's net-worth snapshot in the same transaction.
 *
 * Every write first replays the whole history (all positions, since transfers link them) through the ledger. When
 * the result is impossible — selling or transferring more than is held, a fee before the position started, a
 * transfer that arrives before it leaves — it throws the ledger's
 * [com.denebapps.patrimonio.domain.calc.MovementValidationException] (a Spanish message fit to show) and writes
 * nothing. A position that ends up with no units stays listed as closed, at zero.
 */
interface MovementRepository {
    /** The movements of [holdingId] in the order they happened (date, then type, then entry order). */
    fun observeMovements(holdingId: String): Flow<List<Movement>>

    /** Every movement of every position in the order they happened. */
    fun observeAllMovements(): Flow<List<Movement>>

    /** The ids of the holdings that have movements: their units cannot be edited by hand. */
    fun observeHoldingIdsWithMovements(): Flow<Set<String>>

    /**
     * Records a movement on [holdingId]. The entry order is assigned here. A holding in EUR gets its EUR amounts from
     * the draft's amounts; any other currency must bring them ([MovementDraft.amountEur], [MovementDraft.feeEur]).
     * Transfers are recorded with [addTransfer], not here.
     *
     * @throws PortfolioHoldingNotFoundException when there is no such holding.
     * @throws InvalidMovementException when the holding is not quoted, the draft is incomplete or in another currency,
     *   or it is a transfer leg.
     * @throws MissingEurEquivalentException when the holding is not in EUR and the EUR amounts are missing.
     * @throws com.denebapps.patrimonio.domain.calc.MovementValidationException when the history becomes impossible.
     */
    suspend fun addMovement(holdingId: String, draft: MovementDraft): Movement

    /**
     * Replaces the data of movement [id] (its holding, entry order and transfer stay). A transfer leg keeps its type:
     * only its date, units and note change. Same EUR rules and validation as [addMovement].
     *
     * @throws MovementNotFoundException when there is no such movement.
     * @throws InvalidMovementException when the draft is incomplete or in another currency, or changes the type of a
     *   transfer leg, or turns another movement into one.
     * @throws MissingEurEquivalentException when the holding is not in EUR and the EUR amounts are missing.
     * @throws com.denebapps.patrimonio.domain.calc.MovementValidationException when the history becomes impossible.
     */
    suspend fun updateMovement(id: String, draft: MovementDraft): Movement

    /**
     * Deletes movement [id]; deleting one leg of a transfer deletes both. A position left without movements keeps no
     * units (its history was where they came from); it can be edited by hand again.
     *
     * @throws MovementNotFoundException when there is no such movement.
     * @throws com.denebapps.patrimonio.domain.calc.MovementValidationException when the rest of the history needs it.
     */
    suspend fun deleteMovement(id: String)

    /**
     * Moves [unitsOut] units of fund [fromHoldingId] to fund [toHoldingId], which receives [unitsIn], carrying the
     * acquisition dates and cost of the units: two linked movements, [outDate] and [inDate]. Holdings may be in
     * different portfolios.
     *
     * @return the id that links the two legs.
     * @throws PortfolioHoldingNotFoundException when either holding does not exist.
     * @throws TransferNotAllowedException when they are the same holding, or one is not a quoted fund.
     * @throws com.denebapps.patrimonio.domain.calc.MovementValidationException when the origin does not hold that many
     *   units on [outDate], or the units arrive before they leave.
     */
    suspend fun addTransfer(
        fromHoldingId: String,
        toHoldingId: String,
        outDate: LocalDate,
        inDate: LocalDate,
        unitsOut: Units,
        unitsIn: Units,
        note: String? = null,
    ): String

    /**
     * Records the units [holdingId] already had when its history starts, on [date]: [cost] is the total paid, `null`
     * when it is unknown (never zero). A holding in another currency than EUR brings [costEur] with its cost.
     *
     * @throws MissingEurEquivalentException when the holding is not in EUR and has a [cost] but no [costEur].
     * @see addMovement for the other errors.
     */
    suspend fun addOpeningBalance(
        holdingId: String,
        date: LocalDate,
        units: Units,
        cost: CurrencyAmount?,
        costEur: Money? = null,
        note: String? = null,
    ): Movement

    /**
     * Stores a new quoted [holding] together with its first purchase [buy], in one step: when the purchase is refused,
     * the holding is not created either. The units in the holding are replaced by those of the purchase.
     *
     * @throws InvalidMovementException when the holding is not quoted or [buy] is not a [MovementType.BUY].
     * @throws PortfolioNotFoundException when the holding's portfolio does not exist.
     * @see addMovement for the other errors.
     */
    suspend fun addHoldingWithPurchase(holding: PortfolioHolding, buy: MovementDraft): Movement
}

/**
 * What the user typed for a movement, before it has an id, an entry order or its EUR equivalent. [amount] is in the
 * holding's currency. [amountEur] and [feeEur] are only needed for a holding that is not in EUR (the caller computes
 * them from that day's FX; a missing [feeEur] is zero when there is no [fee]); for a holding in EUR they are
 * ignored and filled from [amount] and [fee]. Which fields a type needs is described in [Movement].
 */
data class MovementDraft(
    val type: MovementType,
    val date: LocalDate,
    val units: Units? = null,
    val amount: CurrencyAmount? = null,
    val fee: Money = Money.ZERO,
    val amountEur: Money? = null,
    val feeEur: Money? = null,
    val note: String? = null,
)

/** The longest note a movement keeps, after trimming. */
const val MAX_MOVEMENT_NOTE_LENGTH = 200

/** A movement that cannot be recorded as asked; the message is in Spanish and fit to show to the user. */
open class InvalidMovementException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** A holding in another currency than EUR needs the EUR equivalent of the amount (and of the fee, if any). */
class MissingEurEquivalentException :
    InvalidMovementException("Falta el importe en euros del movimiento, que no está en euros.")

/** A fund transfer that is not allowed; [reason] is in Spanish and fit to show to the user. */
class TransferNotAllowedException(reason: String) : InvalidMovementException(reason)

class MovementNotFoundException(movementId: String) :
    NoSuchElementException("Movement '$movementId' was not found")

/** The units of a holding with movements come from them: add a movement instead of editing the units. */
class HoldingHasMovementsException(val holdingId: String) :
    IllegalStateException("Este producto tiene movimientos: añade uno para cambiar sus unidades.")

/** A holding with a transfer to or from another holding cannot be deleted before that transfer is. */
class HoldingHasTransfersException(val holdingId: String) :
    IllegalStateException("Este producto tiene traspasos con otros productos; elimínalos antes.")
