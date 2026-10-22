package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

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
    val goalId: String,
    val fromAssetId: String?,
    val toAssetId: String?,
    val kind: String,
    val timestampEpochMs: Long,
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
    val fromHoldingId: String? = null,
    val toHoldingId: String? = null,
)
