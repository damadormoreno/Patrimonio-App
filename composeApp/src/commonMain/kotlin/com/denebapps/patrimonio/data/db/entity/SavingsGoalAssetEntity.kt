package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Junction row: [assetId] is one of the accounts whose combined balance the goal follows. A goal links
 * to any number of assets, products ([SavingsGoalHoldingEntity]) and groups ([SavingsGoalGroupEntity]), in any
 * combination.
 *
 * Both foreign keys cascade so the row never dangles, but deleting an asset goes through
 * `AssetRepositoryImpl.deleteById`, which records an `ASSET_DELETED` link event first.
 */
@Entity(
    tableName = "savings_goal_assets",
    primaryKeys = ["goalId", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("assetId")],
)
data class SavingsGoalAssetEntity(
    val goalId: String,
    val assetId: String,
)
