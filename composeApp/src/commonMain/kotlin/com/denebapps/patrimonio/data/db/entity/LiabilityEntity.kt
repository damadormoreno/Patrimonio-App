package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "liabilities", indices = [Index("group")])
data class LiabilityEntity(
    @PrimaryKey val id: String,
    val group: String,
    val name: String,
    val subtitle: String?,
    val amountMinor: Long,
    val currency: String,
    /** Added in schema 6; null = the type's icon. */
    val emoji: String? = null,
)
