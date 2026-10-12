package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The share of one asset class a portfolio aims for: [assetClass] is an `AssetClass` name, [targetBp] the share in
 * basis points (hundredths of a percentage point, 1 to 10 000). A portfolio's rows add up to 10 000 or it has none;
 * classes with no target have no row. Deleting a portfolio deletes its targets.
 */
@Entity(
    tableName = "allocation_targets",
    primaryKeys = ["portfolioId", "assetClass"],
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("portfolioId")],
)
data class AllocationTargetEntity(
    val portfolioId: String,
    val assetClass: String,
    val targetBp: Int,
)
