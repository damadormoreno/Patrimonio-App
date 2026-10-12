package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.inProcessingOrder
import kotlinx.datetime.LocalDate

/**
 * The cash flows of one position, for [xirr]: every movement as the user lived it, closed by the position's current
 * value as if it were cashed on [today]. A buy is `-(amount + fee)`, an opening balance `-cost`, a sell
 * `+(amount - fee)`, a dividend `+amount`, a fee `-amount`; a transfer out is `+` and a transfer in `-` the market
 * value of the units on its date.
 *
 * [movements] are those of the position [ledger] was built from. [transferValueEur] gives the market value, in EUR,
 * of the units of a transfer leg on its date (from the price history); when it has none, the cost carried by the lots
 * is used instead. Returns `null` when the position has no usable flows because some cost is unknown — an opening
 * balance without a cost, or a transfer with neither a market value nor a known carried cost.
 */
fun positionCashFlows(
    movements: List<Movement>,
    ledger: PositionLedger,
    today: LocalDate,
    currentValueEur: Money,
    transferValueEur: (Movement) -> Money? = { null },
): List<CashFlow>? {
    val flows = mutableListOf<CashFlow>()
    for (movement in movements.inProcessingOrder()) {
        val date = movement.date
        val amount = movement.amountEur
        flows += when (movement.type) {
            MovementType.BUY -> CashFlow(date, -(checkNotNull(amount) + movement.feeEur))
            MovementType.OPENING_BALANCE -> CashFlow(date, -(amount ?: return null))
            MovementType.SELL -> CashFlow(date, checkNotNull(amount) - movement.feeEur)
            MovementType.DIVIDEND -> CashFlow(date, checkNotNull(amount))
            MovementType.FEE -> CashFlow(date, -checkNotNull(amount))
            MovementType.TRANSFER_OUT ->
                CashFlow(date, transferValue(movement, ledger, transferValueEur) ?: return null, movement.transferId)
            MovementType.TRANSFER_IN ->
                CashFlow(date, -(transferValue(movement, ledger, transferValueEur) ?: return null), movement.transferId)
        }
    }
    if (currentValueEur.minorUnits != 0L) flows += CashFlow(today, currentValueEur)
    return flows
}

private fun transferValue(movement: Movement, ledger: PositionLedger, marketValue: (Movement) -> Money?): Money? =
    marketValue(movement) ?: ledger.transferLegs[movement.id]?.costEur

/**
 * The flows of several positions as one: the union of [positions], except that a transfer between two positions that
 * are both included cancels out (both legs are dropped, as the money never left the portfolio). A leg whose other
 * side is not among [positions] stays, at its market value.
 */
fun combineCashFlows(positions: List<List<CashFlow>>): List<CashFlow> {
    val all = positions.flatten()
    val legsByTransfer = all.mapNotNull { it.transferId }.groupingBy { it }.eachCount()
    return all.filter { flow ->
        val transferId = flow.transferId
        transferId == null || legsByTransfer.getValue(transferId) < 2
    }
}
