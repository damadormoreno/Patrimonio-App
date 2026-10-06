package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "assets", indices = [Index("group")])
data class AssetEntity(
    @PrimaryKey val id: String,
    val group: String,
    val name: String,
    val subtitle: String?,
    val amountMinor: Long,
    val currency: String,
)
