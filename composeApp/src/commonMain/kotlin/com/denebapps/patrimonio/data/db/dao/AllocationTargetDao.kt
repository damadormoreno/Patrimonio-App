package com.denebapps.patrimonio.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.denebapps.patrimonio.data.db.entity.AllocationTargetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AllocationTargetDao {
    /** Every target of every portfolio; re-emits when any changes. */
    @Query("SELECT * FROM allocation_targets ORDER BY portfolioId, assetClass")
    fun observeAll(): Flow<List<AllocationTargetEntity>>

    @Query("SELECT * FROM allocation_targets ORDER BY portfolioId, assetClass")
    suspend fun list(): List<AllocationTargetEntity>

    /** Fails when a portfolio already has a target for that class, or the portfolio does not exist. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(targets: List<AllocationTargetEntity>)

    /** @return rows deleted. */
    @Query("DELETE FROM allocation_targets WHERE portfolioId = :portfolioId")
    suspend fun deleteFor(portfolioId: String): Int

    /** Only called by `deleteFinancialRows`, before the portfolios go (the foreign key would cascade anyway). */
    @Query("DELETE FROM allocation_targets")
    suspend fun deleteAll()
}
