package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FxRateDao {
    @Query("SELECT * FROM fx_rates")
    fun observeAll(): Flow<List<FxRateEntity>>

    @Query("SELECT * FROM fx_rates")
    suspend fun list(): List<FxRateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rates: List<FxRateEntity>)

    @Query("SELECT COUNT(*) FROM fx_rates")
    suspend fun count(): Int
}
