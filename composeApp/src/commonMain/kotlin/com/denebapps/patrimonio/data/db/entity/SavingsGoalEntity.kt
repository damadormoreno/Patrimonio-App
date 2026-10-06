package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "savings_goals",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedAssetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("linkedAssetId")],
)
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDateEpochDay: Long?,
    val linkedAssetId: String?,
    val lifecycle: String,
)
