package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [id] is a UUID string, like every other entity, so goals created on different devices never collide.
 * [createdAtEpochMs] keeps the creation order the old autoincrement id used to give; goals migrated from
 * schema 3 carry their old numeric id there instead of a real instant (see `MIGRATION_3_4`).
 * The linked assets live in [SavingsGoalAssetEntity] (one goal, many accounts).
 */
@Entity(
    tableName = "savings_goals",
    foreignKeys = [
        ForeignKey(
            entity = AccountGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedGroupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("linkedGroupId")],
)
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDateEpochDay: Long?,
    val lifecycle: String,
    /** Exclusive with the goal's `savings_goal_assets` rows: a goal links to assets XOR a persisted group
     *  XOR nothing. */
    val linkedGroupId: String? = null,
    val createdAtEpochMs: Long,
)
