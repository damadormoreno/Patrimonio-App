package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sync state of one currency of [FxHistoryEntity]: the oldest day [coveredFromEpochDay] a download was asked to reach
 * (Frankfurter publishes working days only, so the oldest stored row can be later), and when it last ran.
 */
@Entity(tableName = "fx_history_sync")
data class FxHistorySyncEntity(
    @PrimaryKey val currency: String,
    val coveredFromEpochDay: Long,
    val lastSyncedAtEpochMs: Long,
)
