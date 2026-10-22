package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * [id] is a UUID string, like every other entity, so goals created on different devices never collide.
 * [createdAtEpochMs] keeps the creation order the old autoincrement id used to give; goals migrated from
 * schema 3 carry their old numeric id there instead of a real instant (see `MIGRATION_3_4`).
 * What a goal follows lives in three junction tables, any combination of them (see `MIGRATION_15_16`): accounts in
 * [SavingsGoalAssetEntity], portfolio products in [SavingsGoalHoldingEntity] and account groups in
 * [SavingsGoalGroupEntity]. No rows in any of them means an unlinked goal with its own ledger.
 */
@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDateEpochDay: Long?,
    val lifecycle: String,
    val createdAtEpochMs: Long,
)
