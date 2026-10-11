package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios ORDER BY sortOrder, name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<PortfolioEntity>>

    @Query("SELECT * FROM portfolios ORDER BY sortOrder, name COLLATE NOCASE, id")
    suspend fun list(): List<PortfolioEntity>

    @Query("SELECT * FROM portfolios WHERE id = :id")
    suspend fun find(id: String): PortfolioEntity?

    @Query("SELECT COUNT(*) FROM portfolios")
    suspend fun count(): Int

    /** The highest sort order, or -1 when there are no portfolios. */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM portfolios")
    suspend fun maxSortOrder(): Int

    /** Fails when the id already exists. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(portfolio: PortfolioEntity)

    /** @return rows touched (0 or 1). */
    @Query("UPDATE portfolios SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String): Int

    /** Fails while holdings still point at it: they are moved first, never cascaded away. @return rows deleted. */
    @Query("DELETE FROM portfolios WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Only called by `clearFinancialTables` and the backup import, after the holdings are gone. */
    @Query("DELETE FROM portfolios")
    suspend fun deleteAll()
}
