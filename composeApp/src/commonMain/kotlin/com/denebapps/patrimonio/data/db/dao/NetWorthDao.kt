package com.denebapps.patrimonio.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NetWorthDao {
    @Query("SELECT * FROM net_worth_snapshots ORDER BY yearMonth")
    fun observeAll(): Flow<List<NetWorthSnapshotEntity>>

    @Query("SELECT * FROM net_worth_snapshots ORDER BY yearMonth")
    suspend fun list(): List<NetWorthSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: NetWorthSnapshotEntity)

    @Query("SELECT * FROM net_worth_snapshots WHERE yearMonth = :yearMonth")
    suspend fun find(yearMonth: String): NetWorthSnapshotEntity?

    /** Most recent snapshot strictly before [yearMonth] — used for month-over-month delta when a
     *  month was skipped (not necessarily calendar `yearMonth - 1`). */
    @Query("SELECT * FROM net_worth_snapshots WHERE yearMonth < :yearMonth ORDER BY yearMonth DESC LIMIT 1")
    suspend fun findMostRecentBefore(yearMonth: String): NetWorthSnapshotEntity?

    /** Wipes the whole table — only called by `clearFinancialTables` inside its FK-ordered
     *  clear-all transaction. */
    @Query("DELETE FROM net_worth_snapshots")
    suspend fun deleteAll()
}
