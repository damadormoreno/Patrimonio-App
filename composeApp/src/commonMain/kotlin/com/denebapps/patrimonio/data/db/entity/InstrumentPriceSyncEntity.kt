package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity

/**
 * When the price history of an instrument was last downloaded, to skip a sync done less than six hours ago and to tell
 * "never synced" (no row: download everything) from "synced, but the provider had nothing". Not user data.
 */
@Entity(tableName = "instrument_price_sync", primaryKeys = ["provider", "code"])
data class InstrumentPriceSyncEntity(
    val provider: String,
    val code: String,
    val lastSyncedAtEpochMs: Long,
)
