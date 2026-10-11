package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Lot
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.toBalance
import kotlinx.datetime.LocalDate

// The FIFO ledger of a position, rebuilt from its movements. Pure and exact: every amount is a fixed-point `Money`
// in EUR and every quantity a scaled `Long`; proportional splits use `mulDivHalfEven` and always hand the remainder to
// the last piece, so no cent and no unit is ever created or lost. `Double` is not used here.

// ---- Validation errors ------------------------------------------------------------------------------------

/** A set of movements that cannot be a real history. The message is in Spanish and fit to show to the user. */
sealed class MovementValidationException(message: String) : IllegalStateException(message)

/** [requested] units were sold or transferred on [date] while the position held only [available]. */
class InsufficientUnitsException(
    val movementId: String,
    val date: LocalDate,
    val available: UnitsBalance,
    val requested: Units,
) : MovementValidationException(
    "El ${formatDate(date)} intentas dar salida a ${formatUnits(requested.scaled)} unidades " +
        "pero solo tienes ${formatUnits(available.scaled)}.",
)

/** A fee or a dividend on [date], before the position had its first buy, opening balance or transfer in. */
class MovementBeforeStartException(
    val movementId: String,
    val date: LocalDate,
    val type: MovementType,
) : MovementValidationException(
    "El ${formatDate(date)} hay un movimiento anterior a la primera compra o al saldo inicial.",
)

/** The two legs of transfer [transferId] do not form a valid pair. */
class InvalidTransferException(val transferId: String, reason: String) :
    MovementValidationException("El traspaso no es válido: $reason.")

private fun formatDate(date: LocalDate): String =
    "${date.dayOfMonth.toString().padStart(2, '0')}/${date.monthNumber.toString().padStart(2, '0')}/${date.year}"

private fun formatUnits(scaled: Long): String {
    val fraction = (scaled % UNITS_SCALE).toString().padStart(UNITS_DECIMALS, '0').trimEnd('0')
    val whole = scaled / UNITS_SCALE
    return if (fraction.isEmpty()) "$whole" else "$whole,$fraction"
}

private const val UNITS_DECIMALS = 6

// ---- Value types ------------------------------------------------------------------------------------------

/**
 * A gain split by whether the cost of the units involved is known. [knownGainEur] is the gain on the part with a
 * known cost, over [knownCostEur]. The part whose cost is unknown ([unknownUnits], worth [unknownValueEur] — the
 * proceeds of a sale, or the current value of the open lots) has no gain yet: it is never counted as zero. The total
 * gain exists only when [isComplete].
 */
data class GainBreakdown(
    val knownGainEur: Money,
    val knownCostEur: Money,
    val unknownValueEur: Money,
    val unknownUnits: UnitsBalance,
) {
    val isComplete: Boolean get() = unknownUnits.isZero

    /** The gain of everything involved, or `null` while some of it has an unknown cost. */
    val totalGainEur: Money? get() = knownGainEur.takeIf { isComplete }

    /** [knownGainEur] over [knownCostEur] (a fraction, 0.1 = 10 %); `null` when the known cost is zero. */
    val returnRatio: Double?
        get() = knownCostEur.minorUnits.takeIf { it > 0 }?.let { knownGainEur.minorUnits.toDouble() / it }

    operator fun plus(other: GainBreakdown) = GainBreakdown(
        knownGainEur + other.knownGainEur,
        knownCostEur + other.knownCostEur,
        unknownValueEur + other.unknownValueEur,
        unknownUnits + other.unknownUnits,
    )

    companion object {
        val ZERO = GainBreakdown(Money.ZERO, Money.ZERO, Money.ZERO, UnitsBalance.ZERO)
    }
}

/** The cost of the open lots: [knownCostEur] of those with a known cost, plus [unknownCostUnits] without one. */
data class CostBasis(val knownCostEur: Money, val unknownCostUnits: UnitsBalance) {
    val isComplete: Boolean get() = unknownCostUnits.isZero
}

/** The units held at the end of [date]. */
data class UnitsPoint(val date: LocalDate, val units: UnitsBalance)

/**
 * What a transfer leg moved: [units] of this position, and their [costEur] (`null` when any of the lots has an
 * unknown cost). The leg of a movement is found by the movement id.
 */
data class TransferLeg(val units: Units, val costEur: Money?)

// ---- The ledger of one position ---------------------------------------------------------------------------

/**
 * The state of one position after the movements recorded so far. Immutable: [record] and the transfer functions
 * return the next state. [lots] are open lots, oldest acquisition first (FIFO); a sell or a transfer out consumes
 * from the front. Build one from a whole history with [computeLedgers].
 *
 * Accounting choices: [realized] is the capital gain of sells (proceeds net of the sell fee against the cost of the
 * consumed lots, which includes the buy fee); [dividendsEur] is income and is not part of it; [standaloneFeesEur] are
 * [MovementType.FEE] costs, tracked apart; [tradeFeesEur] is the information of the fees already inside costs and
 * proceeds. A transfer realizes nothing.
 */
data class PositionLedger(
    val holdingId: String,
    val lots: List<Lot> = emptyList(),
    val realized: GainBreakdown = GainBreakdown.ZERO,
    val realizedBySale: Map<String, GainBreakdown> = emptyMap(),
    val dividendsEur: Money = Money.ZERO,
    val tradeFeesEur: Money = Money.ZERO,
    val standaloneFeesEur: Money = Money.ZERO,
    val transferLegs: Map<String, TransferLeg> = emptyMap(),
    val unitsHistory: List<UnitsPoint> = emptyList(),
) {
    /** Units held now (zero once everything was sold or transferred out). */
    val unitsHeld: UnitsBalance get() = UnitsBalance(lots.sumOf { it.units.scaled })

    /** Whether the position already had a first buy, opening balance or transfer in. */
    val started: Boolean get() = unitsHistory.isNotEmpty()

    val costBasis: CostBasis
        get() = CostBasis(
            knownCostEur = lots.mapNotNull { it.costEur }.fold(Money.ZERO) { sum, cost -> sum + cost },
            unknownCostUnits = UnitsBalance(lots.filter { it.costEur == null }.sumOf { it.units.scaled }),
        )

    /** Units held at the end of [date]: zero before the first movement. */
    fun unitsOn(date: LocalDate): UnitsBalance {
        val found = unitsHistory.binarySearchBy(date) { it.date }
        val index = if (found >= 0) found else -found - 2
        return if (index < 0) UnitsBalance.ZERO else unitsHistory[index].units
    }

    /**
     * Unrealized gain of the open lots when the whole position is worth [currentValueEur]. The value is shared among
     * the lots by units; the part held in lots of unknown cost is reported apart, not counted as a zero-cost gain.
     */
    fun unrealizedGain(currentValueEur: Money): GainBreakdown {
        val total = unitsHeld.scaled
        if (total == 0L) return GainBreakdown.ZERO
        val basis = costBasis
        val knownUnits = total - basis.unknownCostUnits.scaled
        val knownValue = if (basis.isComplete) {
            currentValueEur
        } else {
            Money(mulDivHalfEven(currentValueEur.minorUnits, knownUnits, total))
        }
        return GainBreakdown(
            knownGainEur = knownValue - basis.knownCostEur,
            knownCostEur = basis.knownCostEur,
            unknownValueEur = currentValueEur - knownValue,
            unknownUnits = basis.unknownCostUnits,
        )
    }

    /**
     * The state after [movement], which must belong to this position and not be older than the previous one.
     * Transfers go through [sendTransfer] and [receiveTransfer] instead, since they involve a second position.
     *
     * @throws InsufficientUnitsException when a sell takes more units than are held.
     * @throws MovementBeforeStartException when a fee or dividend comes before the first buy or opening balance.
     */
    fun record(movement: Movement): PositionLedger {
        requireOwn(movement)
        val units = movement.units
        val amountEur = movement.amountEur
        return when (movement.type) {
            MovementType.BUY -> {
                val cost = checkNotNull(amountEur) + movement.feeEur
                val withLot = addLot(Lot(movement.date, checkNotNull(units), cost))
                withLot.copy(tradeFeesEur = tradeFeesEur + movement.feeEur)
            }
            MovementType.OPENING_BALANCE -> addLot(Lot(movement.date, checkNotNull(units), amountEur))
            MovementType.SELL -> sell(movement)
            MovementType.FEE -> requireStarted(movement)
                .copy(standaloneFeesEur = standaloneFeesEur + checkNotNull(amountEur))
            MovementType.DIVIDEND -> requireStarted(movement)
                .copy(dividendsEur = dividendsEur + checkNotNull(amountEur))
            MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN ->
                throw IllegalArgumentException("a transfer leg is recorded with sendTransfer or receiveTransfer")
        }
    }

    /**
     * The state after giving up the units of [out] (a [MovementType.TRANSFER_OUT]) and the lots it carries away,
     * oldest first, with their dates and costs. Nothing is realized.
     *
     * @throws InsufficientUnitsException when more units are transferred than are held.
     */
    fun sendTransfer(out: Movement): Pair<PositionLedger, List<Lot>> {
        requireOwn(out)
        require(out.type == MovementType.TRANSFER_OUT) { "expected a transfer out, was ${out.type}" }
        val units = checkNotNull(out.units)
        val (remaining, consumed) = lots.consume(out, units)
        val leg = TransferLeg(units, consumed.totalCostOrNull())
        val next = copy(lots = remaining, transferLegs = transferLegs + (out.id to leg)).withHistory(out.date)
        return next to consumed
    }

    /**
     * The state after receiving [carried], the lots a matching transfer out gave up, for the units of [inn] (a
     * [MovementType.TRANSFER_IN]). Each lot keeps its acquisition date and cost; its units are rescaled by
     * `inn.units / carried units`, since the destination fund has its own NAV.
     */
    fun receiveTransfer(inn: Movement, carried: List<Lot>): PositionLedger {
        requireOwn(inn)
        require(inn.type == MovementType.TRANSFER_IN) { "expected a transfer in, was ${inn.type}" }
        require(carried.isNotEmpty()) { "a transfer in needs the lots of its transfer out" }
        val units = checkNotNull(inn.units)
        val received = rescaleLots(carried, units)
        val leg = TransferLeg(units, received.totalCostOrNull())
        return copy(transferLegs = transferLegs + (inn.id to leg)).addLots(received, inn.date)
    }

    private fun sell(movement: Movement): PositionLedger {
        val units = checkNotNull(movement.units)
        val (remaining, consumed) = lots.consume(movement, units)
        val proceeds = checkNotNull(movement.amountEur) - movement.feeEur
        val gain = realizedGain(consumed, proceeds, units)
        return copy(
            lots = remaining,
            realized = realized + gain,
            realizedBySale = realizedBySale + (movement.id to gain),
            tradeFeesEur = tradeFeesEur + movement.feeEur,
        ).withHistory(movement.date)
    }

    private fun addLot(lot: Lot): PositionLedger = addLots(listOf(lot), lot.acquiredOn)

    /** Adds [added] to the open lots, keeping them oldest first, and records the units held at the end of [date]. */
    private fun addLots(added: List<Lot>, date: LocalDate): PositionLedger =
        copy(lots = (lots + added).sortedBy { it.acquiredOn }).withHistory(date)

    private fun withHistory(date: LocalDate): PositionLedger {
        val last = unitsHistory.lastOrNull()
        require(last == null || last.date <= date) { "movements must be recorded in date order" }
        val point = UnitsPoint(date, unitsHeld)
        return copy(unitsHistory = if (last?.date == date) unitsHistory.dropLast(1) + point else unitsHistory + point)
    }

    private fun requireStarted(movement: Movement): PositionLedger {
        if (!started) throw MovementBeforeStartException(movement.id, movement.date, movement.type)
        return this
    }

    private fun requireOwn(movement: Movement) =
        require(movement.holdingId == holdingId) { "movement ${movement.id} belongs to ${movement.holdingId}" }
}

/**
 * Applies a transfer pair across two ledgers: [source] gives up the units of [out], [destination] receives them with
 * the lots' dates and cost, rescaled to the units of [inn]. Returns the new (source, destination); no gain is realized.
 *
 * @throws InsufficientUnitsException when [source] holds fewer units than [out] transfers.
 */
fun applyTransfer(
    source: PositionLedger,
    destination: PositionLedger,
    out: Movement,
    inn: Movement,
): Pair<PositionLedger, PositionLedger> {
    require(out.transferId == inn.transferId) { "the legs belong to different transfers" }
    val (newSource, carried) = source.sendTransfer(out)
    return newSource to destination.receiveTransfer(inn, carried)
}

// ---- Rebuilding every position from its movements ---------------------------------------------------------

/**
 * The FIFO ledger of every position that has movements, by holding id, rebuilt from [movements] (all of them, so that
 * transfers between positions resolve). Movements are processed in the order they happened — by date, then
 * [MovementType] order, then entry sequence — except that the receiving leg of a transfer follows its sending leg
 * when both fall on the same day.
 *
 * @throws MovementValidationException when the history is impossible: selling or transferring more than is held
 *   ([InsufficientUnitsException]), a fee or dividend before the start ([MovementBeforeStartException]), or a
 *   transfer that is not a pair of legs on two positions, out first ([InvalidTransferException]).
 */
fun computeLedgers(movements: List<Movement>): Map<String, PositionLedger> {
    requireValidTransfers(movements)
    val ledgers = LinkedHashMap<String, PositionLedger>()
    val carried = HashMap<String, List<Lot>>()
    for (movement in movements.inLedgerOrder()) {
        val ledger = ledgers[movement.holdingId] ?: PositionLedger(movement.holdingId)
        ledgers[movement.holdingId] = when (movement.type) {
            MovementType.TRANSFER_OUT -> {
                val (next, lots) = ledger.sendTransfer(movement)
                carried[checkNotNull(movement.transferId)] = lots
                next
            }
            MovementType.TRANSFER_IN -> {
                val lots = carried.getValue(checkNotNull(movement.transferId))
                ledger.receiveTransfer(movement, lots)
            }
            else -> ledger.record(movement)
        }
    }
    return ledgers
}

/** The reason [movements] cannot be a real history, or `null` when [computeLedgers] accepts them. */
fun movementValidationError(movements: List<Movement>): MovementValidationException? {
    try {
        computeLedgers(movements)
    } catch (error: MovementValidationException) {
        return error
    }
    return null
}

private fun requireValidTransfers(movements: List<Movement>) {
    movements.filter { it.transferId != null }.groupBy { checkNotNull(it.transferId) }.forEach { (id, legs) ->
        val outs = legs.filter { it.type == MovementType.TRANSFER_OUT }
        val ins = legs.filter { it.type == MovementType.TRANSFER_IN }
        if (outs.size != 1 || ins.size != 1) throw InvalidTransferException(id, "debe tener una salida y una entrada")
        if (outs.single().holdingId == ins.single().holdingId) {
            throw InvalidTransferException(id, "el origen y el destino son el mismo producto")
        }
        if (ins.single().date < outs.single().date) {
            throw InvalidTransferException(id, "la entrada es anterior a la salida")
        }
    }
}

/**
 * The movements in ledger order: [inProcessingOrder][com.denebapps.patrimonio.domain.model.inProcessingOrder], but a
 * transfer in that falls on the day of its transfer out is placed right after it (it needs the lots the out gives up).
 */
private fun List<Movement>.inLedgerOrder(): List<Movement> {
    val outs = filter { it.type == MovementType.TRANSFER_OUT }.associateBy { it.transferId }
    fun anchor(movement: Movement): Movement? {
        if (movement.type != MovementType.TRANSFER_IN) return null
        return outs[movement.transferId]?.takeIf { it.date == movement.date }
    }
    return sortedWith(
        compareBy<Movement>(
            { it.date },
            { (anchor(it) ?: it).type.ordinal },
            { (anchor(it) ?: it).sequence },
            { if (anchor(it) != null) 1 else 0 },
            { it.id },
        ),
    )
}

// ---- FIFO arithmetic --------------------------------------------------------------------------------------

/**
 * Takes [needed] units from the front of the lots. Returns (lots left, lots consumed); a lot taken in part is split
 * with its cost pro rata (rounded half-even) and the rest of the cost stays with the remainder, so no cent is lost.
 */
private fun List<Lot>.consume(movement: Movement, needed: Units): Pair<List<Lot>, List<Lot>> {
    val available = UnitsBalance(sumOf { it.units.scaled })
    if (available.scaled < needed.scaled) {
        throw InsufficientUnitsException(movement.id, movement.date, available, needed)
    }
    var left = needed.scaled
    val remaining = mutableListOf<Lot>()
    val consumed = mutableListOf<Lot>()
    for (lot in this) {
        when {
            left == 0L -> remaining += lot
            lot.units.scaled <= left -> {
                consumed += lot
                left -= lot.units.scaled
            }
            else -> {
                val cost = lot.costEur
                val takenCost = cost?.let { Money(mulDivHalfEven(it.minorUnits, left, lot.units.scaled)) }
                consumed += Lot(lot.acquiredOn, Units(left), takenCost)
                remaining += Lot(lot.acquiredOn, Units(lot.units.scaled - left), takenCost?.let { cost - it })
                left = 0
            }
        }
    }
    return remaining to consumed
}

/**
 * The gain of selling [total] units for [proceeds] out of the [consumed] lots. The proceeds are shared among the
 * lots by units (the last lot takes the rounding remainder); the share of a lot with unknown cost has no gain.
 */
private fun realizedGain(consumed: List<Lot>, proceeds: Money, total: Units): GainBreakdown {
    var allocated = 0L
    var result = GainBreakdown.ZERO
    consumed.forEachIndexed { index, lot ->
        val share = if (index == consumed.lastIndex) {
            proceeds.minorUnits - allocated
        } else {
            mulDivHalfEven(proceeds.minorUnits, lot.units.scaled, total.scaled)
        }
        allocated += share
        val cost = lot.costEur
        result += if (cost != null) {
            GainBreakdown(Money(share) - cost, cost, Money.ZERO, UnitsBalance.ZERO)
        } else {
            GainBreakdown(Money.ZERO, Money.ZERO, Money(share), lot.units.toBalance())
        }
    }
    return result
}

/** The total cost of [this] lots, `null` as soon as one has an unknown cost. */
private fun List<Lot>.totalCostOrNull(): Money? {
    var total = Money.ZERO
    for (lot in this) total += lot.costEur ?: return null
    return total
}

/**
 * [lots] with their units rescaled so that they add up to exactly [unitsIn], keeping each lot's date and cost. The
 * boundaries are cumulative (`cumulative out units * unitsIn / total out units`, rounded half-even), so the rounding
 * never drifts. A lot that would end up with no units folds its cost into the next lot (the last one folds it into
 * the previous): the cost is never dropped, and becomes unknown if either side was.
 */
private fun rescaleLots(lots: List<Lot>, unitsIn: Units): List<Lot> {
    val totalOut = lots.sumOf { it.units.scaled }
    var cumulativeOut = 0L
    var previousBoundary = 0L
    var pendingCost: Money? = Money.ZERO
    val result = mutableListOf<Lot>()
    for (lot in lots) {
        cumulativeOut += lot.units.scaled
        val boundary = mulDivHalfEven(cumulativeOut, unitsIn.scaled, totalOut)
        val rescaled = boundary - previousBoundary
        previousBoundary = boundary
        pendingCost = pendingCost.plusOrUnknown(lot.costEur)
        if (rescaled > 0) {
            result += Lot(lot.acquiredOn, Units(rescaled), pendingCost)
            pendingCost = Money.ZERO
        }
    }
    // Trailing lots that rescaled to no units fold into the last lot; a zero pending cost has nothing to fold.
    if (pendingCost == Money.ZERO) return result
    val last = result.last()
    return result.dropLast(1) + last.copy(costEur = last.costEur.plusOrUnknown(pendingCost))
}

private fun Money?.plusOrUnknown(other: Money?): Money? = if (this == null || other == null) null else this + other
