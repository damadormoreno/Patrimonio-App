package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which variant of a portfolio holding a row stores; the columns that only apply to the other one stay null. */
enum class HoldingKind { QUOTED, MANUAL }

/**
 * One portfolio holding. [type], [kind], [provider]: enum names; [currency]: ISO code. [currency] is the currency the
 * instrument is quoted in for a `QUOTED` row and the currency of [valueMinor] for a `MANUAL` one.
 *
 * - `QUOTED`: [provider]/[providerCode], [unitsScaled] (x 1e6) and, once fetched, [priceScaled] (x 1e6, major units),
 *   [priceDateEpochDay] and [priceFetchedAtEpochMs] — all three set together or all null.
 * - `MANUAL`: [valueMinor] and [valuedAtEpochMs].
 */
@Entity(tableName = "portfolio_holdings")
data class PortfolioHoldingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val isin: String?,
    val ticker: String?,
    val kind: String,
    val provider: String?,
    val providerCode: String?,
    val currency: String,
    val unitsScaled: Long?,
    val priceScaled: Long?,
    val priceDateEpochDay: Long?,
    val priceFetchedAtEpochMs: Long?,
    val valueMinor: Long?,
    val valuedAtEpochMs: Long?,
)
