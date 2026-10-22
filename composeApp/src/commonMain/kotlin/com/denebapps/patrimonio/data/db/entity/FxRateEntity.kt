package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** [fetchedAtEpochMs] is pinned to `0` for seeded rows so the first staleness check always
 *  attempts a live fetch. The EUR row's timestamp is never used for staleness (see FxRepository). */
@Entity(tableName = "fx_rates")
data class FxRateEntity(
    @PrimaryKey val currency: String,
    val rateToEurScaled: Long,
    val fetchedAtEpochMs: Long,
)
