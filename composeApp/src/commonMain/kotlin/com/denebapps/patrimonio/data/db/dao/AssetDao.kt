package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets ORDER BY name")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets ORDER BY name")
    suspend fun list(): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun find(id: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(asset: AssetEntity)

    @Query(
        """
        UPDATE assets
        SET `group` = :group,
            name = :name,
            subtitle = :subtitle,
            amountMinor = :amountMinor,
            currency = :currency
        WHERE id = :id
          AND (
              currency = :currency
              OR NOT EXISTS (
                  SELECT 1 FROM savings_goals WHERE linkedAssetId = :id
              )
          )
        """,
    )
    suspend fun updateGuarded(
        id: String,
        group: String,
        name: String,
        subtitle: String?,
        amountMinor: Long,
        currency: String,
    ): Int

    @Query(
        """
        DELETE FROM assets
        WHERE id = :id
          AND NOT EXISTS (
              SELECT 1 FROM savings_goals WHERE linkedAssetId = :id
          )
        """,
    )
    suspend fun deleteIfUnlinked(id: String): Int

    /** Wipes the whole table — only called by `DataMaintenanceRepositoryImpl` inside its FK-ordered
     *  clear-all transaction, AFTER savings goals and group members are already gone. */
    @Query("DELETE FROM assets")
    suspend fun deleteAll()
}
