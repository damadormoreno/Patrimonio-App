package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * What a [Movement] records. The declaration order is the order in which movements of the same day are processed:
 * what brings units in first, then costs and income, then what takes units out (so a same-day buy can be sold, and a
 * same-day transfer in can be transferred on).
 */
enum class MovementType {
    /** Units bought for [Movement.amount] plus [Movement.fee]. */
    BUY,

    /** Units the position already had when the history starts; the total cost is optional (unknown cost). */
    OPENING_BALANCE,

    /** Units received from a fund transfer; carries the lots (dates and cost) of the matching [TRANSFER_OUT]. */
    TRANSFER_IN,

    /** A cost charged to the position that is not part of a trade (custody, management). */
    FEE,

    /** Income paid by the product. Does not change the units or the cost. */
    DIVIDEND,

    /** Units given up in a fund transfer; realizes nothing. */
    TRANSFER_OUT,

    /** Units sold for [Movement.amount] minus [Movement.fee]. */
    SELL,
}

/**
 * One event of a position's life. Amounts are in the holding currency ([amount], [fee]) and in EUR at that day's FX
 * ([amountEur], [feeEur]); cost basis and gains are computed in EUR only.
 *
 * Which fields a type carries:
 * - [MovementType.BUY], [MovementType.SELL]: [units], [amount] (gross, units x price) and [amountEur] are required;
 *   [fee] and [feeEur] are the broker fee (added to the cost of a buy, deducted from the proceeds of a sell).
 * - [MovementType.OPENING_BALANCE]: [units]; [amount] and [amountEur] are the total cost, both `null` when the cost
 *   is unknown (never zero). No fee.
 * - [MovementType.TRANSFER_OUT], [MovementType.TRANSFER_IN]: [units] and the [transferId] shared by both legs; no
 *   amount (the cost travels with the lots), no fee.
 * - [MovementType.FEE], [MovementType.DIVIDEND]: no [units]; [amount] and [amountEur] are the money paid or received.
 *
 * [sequence] is the entry order, increasing across the whole app: it breaks ties between movements of the same day
 * and type.
 */
data class Movement(
    val id: String,
    val holdingId: String,
    val type: MovementType,
    val date: LocalDate,
    val units: Units? = null,
    val amount: CurrencyAmount? = null,
    val fee: Money = Money.ZERO,
    val amountEur: Money? = null,
    val feeEur: Money = Money.ZERO,
    val transferId: String? = null,
    val note: String? = null,
    val sequence: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "movement id must not be blank" }
        require(holdingId.isNotBlank()) { "movement holding id must not be blank" }
        require(fee.minorUnits >= 0 && feeEur.minorUnits >= 0) { "movement fees must not be negative" }
        require((amount?.amount?.minorUnits ?: 0L) >= 0 && (amountEur?.minorUnits ?: 0L) >= 0) {
            "movement amounts must not be negative"
        }
        when (type) {
            MovementType.BUY, MovementType.SELL -> {
                requireUnits()
                require(amount != null && amountEur != null) { "$type needs its amount and its EUR amount" }
                requireNoTransfer()
            }
            MovementType.OPENING_BALANCE -> {
                requireUnits()
                require((amount == null) == (amountEur == null)) {
                    "$type cost and EUR cost are both known or both unknown"
                }
                requireNoFee()
                requireNoTransfer()
            }
            MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN -> {
                requireUnits()
                require(amount == null && amountEur == null) { "$type carries no amount, its lots carry the cost" }
                requireNoFee()
                require(!transferId.isNullOrBlank()) { "$type needs a transfer id" }
            }
            MovementType.FEE, MovementType.DIVIDEND -> {
                require(units == null) { "$type has no units" }
                require(amount != null && amountEur != null) { "$type needs its amount and its EUR amount" }
                requireNoFee()
                requireNoTransfer()
            }
        }
    }

    private fun requireUnits() = require(units != null) { "$type needs units" }

    private fun requireNoFee() = require(fee == Money.ZERO && feeEur == Money.ZERO) { "$type has no separate fee" }

    private fun requireNoTransfer() = require(transferId == null) { "$type is not part of a transfer" }
}

/**
 * The movements in the order they happened: by date, then by [MovementType] (declaration order), then by entry
 * [Movement.sequence] (and id, so the order is total and deterministic).
 */
fun List<Movement>.inProcessingOrder(): List<Movement> =
    sortedWith(compareBy<Movement>({ it.date }, { it.type.ordinal }, { it.sequence }, { it.id }))
