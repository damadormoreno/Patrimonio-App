package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

// Builders for movement tests. Amounts are EUR cents (the holding is in EUR, so the EUR amount equals the amount).

internal const val FUND_A = "fund-a"
internal const val FUND_B = "fund-b"
internal const val FUND_C = "fund-c"

internal fun date(year: Int, month: Int, day: Int) = LocalDate(year, month, day)

internal fun units(whole: Long, millionths: Long = 0) = Units(whole * UNITS_SCALE + millionths)

internal fun eurAmount(cents: Long) = CurrencyAmount(Money(cents), Currency.EUR)

internal fun buy(
    id: String,
    date: LocalDate,
    units: Units,
    cents: Long,
    feeCents: Long = 0,
    holding: String = FUND_A,
    sequence: Long = 0,
) = Movement(
    id = id,
    holdingId = holding,
    type = MovementType.BUY,
    date = date,
    units = units,
    amount = eurAmount(cents),
    fee = Money(feeCents),
    amountEur = Money(cents),
    feeEur = Money(feeCents),
    sequence = sequence,
)

internal fun sell(
    id: String,
    date: LocalDate,
    units: Units,
    cents: Long,
    feeCents: Long = 0,
    holding: String = FUND_A,
    sequence: Long = 0,
) = Movement(
    id = id,
    holdingId = holding,
    type = MovementType.SELL,
    date = date,
    units = units,
    amount = eurAmount(cents),
    fee = Money(feeCents),
    amountEur = Money(cents),
    feeEur = Money(feeCents),
    sequence = sequence,
)

/** An opening balance; [costCents] `null` is an unknown cost. */
internal fun opening(
    id: String,
    date: LocalDate,
    units: Units,
    costCents: Long?,
    holding: String = FUND_A,
    sequence: Long = 0,
) = Movement(
    id = id,
    holdingId = holding,
    type = MovementType.OPENING_BALANCE,
    date = date,
    units = units,
    amount = costCents?.let { eurAmount(it) },
    amountEur = costCents?.let { Money(it) },
    sequence = sequence,
)

internal fun dividend(id: String, date: LocalDate, cents: Long, holding: String = FUND_A, sequence: Long = 0) =
    Movement(
        id = id,
        holdingId = holding,
        type = MovementType.DIVIDEND,
        date = date,
        amount = eurAmount(cents),
        amountEur = Money(cents),
        sequence = sequence,
    )

internal fun feeMovement(id: String, date: LocalDate, cents: Long, holding: String = FUND_A, sequence: Long = 0) =
    Movement(
        id = id,
        holdingId = holding,
        type = MovementType.FEE,
        date = date,
        amount = eurAmount(cents),
        amountEur = Money(cents),
        sequence = sequence,
    )

internal fun transferOut(
    id: String,
    date: LocalDate,
    units: Units,
    transferId: String,
    holding: String = FUND_A,
    sequence: Long = 0,
) = Movement(
    id = id,
    holdingId = holding,
    type = MovementType.TRANSFER_OUT,
    date = date,
    units = units,
    transferId = transferId,
    sequence = sequence,
)

internal fun transferIn(
    id: String,
    date: LocalDate,
    units: Units,
    transferId: String,
    holding: String = FUND_B,
    sequence: Long = 0,
) = Movement(
    id = id,
    holdingId = holding,
    type = MovementType.TRANSFER_IN,
    date = date,
    units = units,
    transferId = transferId,
    sequence = sequence,
)
