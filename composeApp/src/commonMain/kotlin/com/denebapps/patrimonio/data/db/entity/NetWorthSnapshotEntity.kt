package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Past months are immutable — only the row for the current `YearMonth` is ever re-upserted. */
@Entity(tableName = "net_worth_snapshots")
data class NetWorthSnapshotEntity(
    @PrimaryKey val yearMonth: String,
    val assetsMinor: Long,
    val liabsMinor: Long,
)
