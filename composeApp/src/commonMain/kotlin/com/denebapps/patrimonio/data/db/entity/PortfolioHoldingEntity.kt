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
 *
 * [broker] is the optional bank or broker, for either kind; `null` when none was given.
 *
 * The fundamentals a data provider published about the product, for either kind, all `null` when unknown:
 * [ongoingChargePercentScaled] (percent x 1e4), [riskLevel] (SRRI 1 to 7), [category] and [managementCompany].
 * [fundamentalsFetchedAtEpochMs] is local sync state, not part of the backup: when the provider was last asked, so the
 * lookup runs at most once a day per holding; `null` until then.
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
    val broker: String? = null,
    val ongoingChargePercentScaled: Long? = null,
    val riskLevel: Int? = null,
    val category: String? = null,
    val managementCompany: String? = null,
    val fundamentalsFetchedAtEpochMs: Long? = null,
)
