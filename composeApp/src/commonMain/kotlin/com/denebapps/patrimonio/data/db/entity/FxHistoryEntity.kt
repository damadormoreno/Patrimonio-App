package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity

/**
 * The rate of [currency] (ISO code) to EUR on [epochDay], scaled by `RATE_SCALE` like [FxRateEntity.rateToEurScaled].
 * A reference cache of Frankfurter's time series: re-downloadable, never in a backup, and kept when the financial data
 * is cleared since it belongs to no holding. The key leads with the currency so a series is one range scan.
 */
@Entity(tableName = "fx_history", primaryKeys = ["currency", "epochDay"])
data class FxHistoryEntity(
    val currency: String,
    val epochDay: Long,
    val rateToEurScaled: Long,
)
