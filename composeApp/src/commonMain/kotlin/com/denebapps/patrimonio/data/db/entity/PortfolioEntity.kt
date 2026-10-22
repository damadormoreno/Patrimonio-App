package com.denebapps.patrimonio.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A named group of portfolio holdings. [id] is a random UUID string. Name uniqueness (ignoring case) is not a database
 * constraint: SQLite folds only ASCII, so the repository keeps it. There is always at least one row.
 * [rebalanceToleranceBp] is the width of the band, in basis points either side of each allocation target, within which
 * the portfolio is not asked to rebalance. Its database default is `DEFAULT_REBALANCE_TOLERANCE_BP` (500): the literal
 * lives in [ColumnInfo.defaultValue], which needs a constant string, and a test keeps the two equal.
 */
@Entity(tableName = "portfolios")
data class PortfolioEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "500") val rebalanceToleranceBp: Int = 500,
)
