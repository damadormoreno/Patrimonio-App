package com.denebapps.patrimonio.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named group of portfolio holdings. [id] is a random UUID string. Name uniqueness (ignoring case) is not a database
 * constraint: SQLite folds only ASCII, so the repository keeps it. There is always at least one row.
 */
@Entity(tableName = "portfolios")
data class PortfolioEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)
