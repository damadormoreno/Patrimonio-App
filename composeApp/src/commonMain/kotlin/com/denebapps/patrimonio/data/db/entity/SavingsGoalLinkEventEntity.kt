package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "savings_goal_link_events",
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["goalId", "timestampEpochMs", "id"])],
)
data class SavingsGoalLinkEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val fromAssetId: String?,
    val toAssetId: String?,
    val kind: String,
    val timestampEpochMs: Long,
)
