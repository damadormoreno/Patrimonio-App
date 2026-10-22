package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "savings_goal_allocation_events",
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
data class SavingsGoalAllocationEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: String,
    val deltaMinor: Long,
    val timestampEpochMs: Long,
)
