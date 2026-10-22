package com.denebapps.patrimonio.data.db.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * Junction row: [holdingId] is one of the portfolio products (fund, ETF, stock, crypto...) whose value the goal
 * follows directly. Both foreign keys cascade so the row never dangles, but deleting a holding goes through
 * `PortfolioRepositoryImpl.delete`, which records a `HOLDING_DELETED` link event first.
 */
@Entity(
    tableName = "savings_goal_holdings",
    primaryKeys = ["goalId", "holdingId"],
    foreignKeys = [
        ForeignKey(
            entity = SavingsGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PortfolioHoldingEntity::class,
            parentColumns = ["id"],
            childColumns = ["holdingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("holdingId")],
)
data class SavingsGoalHoldingEntity(
    val goalId: String,
    val holdingId: String,
)
