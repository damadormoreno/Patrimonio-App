package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One movement of a position: a buy, a sell, an opening balance, a fee, a dividend or one leg of a fund transfer.
 * [type]: a `MovementType` name; [epochDay]: the day it happened; [currency]: the ISO code of [amountMinor] and
 * [feeMinor] (the holding's currency), `null` with no amount. [amountEurMinor] and [feeEurMinor] are the same amounts
 * in EUR at that day's FX. [amountMinor] and [amountEurMinor] are `null` together (a transfer leg, an opening balance
 * of unknown cost). [unitsScaled] is units x 1e6, `null` for fees and dividends. [transferId] is shared by the two
 * legs of a transfer, `null` otherwise. [sequence] is the entry order across the whole app.
 *
 * Deleting a holding deletes its movements.
 */
@Entity(
    tableName = "movements",
    foreignKeys = [
        ForeignKey(
            entity = PortfolioHoldingEntity::class,
            parentColumns = ["id"],
            childColumns = ["holdingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("holdingId"), Index("transferId")],
)
data class MovementEntity(
    @PrimaryKey val id: String,
    val holdingId: String,
    val type: String,
    val epochDay: Long,
    val unitsScaled: Long?,
    val amountMinor: Long?,
    val currency: String?,
    val feeMinor: Long,
    val amountEurMinor: Long?,
    val feeEurMinor: Long,
    val transferId: String?,
    val note: String?,
    val sequence: Long,
)
