package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.denebapps.patrimonio.data.db.entity.FxHistoryEntity
import com.denebapps.patrimonio.data.db.entity.FxHistorySyncEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FxHistoryDao {
    @Query("SELECT * FROM fx_history ORDER BY currency, epochDay")
    fun observeAll(): Flow<List<FxHistoryEntity>>

    @Query("SELECT * FROM fx_history ORDER BY currency, epochDay")
    suspend fun list(): List<FxHistoryEntity>

    @Query("SELECT MAX(epochDay) FROM fx_history WHERE currency = :currency")
    suspend fun lastEpochDay(currency: String): Long?

    /** Replaces a day that is already stored, which is how an incremental sync revises its overlap. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FxHistoryEntity>)

    @Query("SELECT * FROM fx_history_sync WHERE currency = :currency")
    suspend fun findSync(currency: String): FxHistorySyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(state: FxHistorySyncEntity)
}
