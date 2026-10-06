package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LiabilityDao {
    @Query("SELECT * FROM liabilities ORDER BY name")
    fun observeAll(): Flow<List<LiabilityEntity>>

    @Query("SELECT * FROM liabilities ORDER BY name")
    suspend fun list(): List<LiabilityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(liability: LiabilityEntity)

    @Update
    suspend fun update(liability: LiabilityEntity)

    @Query("DELETE FROM liabilities WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Wipes the whole table — only called by `clearFinancialTables` inside its FK-ordered
     *  clear-all transaction. */
    @Query("DELETE FROM liabilities")
    suspend fun deleteAll()
}
