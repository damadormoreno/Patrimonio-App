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
            currency = :currency,
            emoji = :emoji
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: String,
        group: String,
        name: String,
        subtitle: String?,
        amountMinor: Long,
        currency: String,
        emoji: String?,
    ): Int

    /** Moves every account of type [from] to type [to] (when the custom type [from] is deleted). */
    @Query("UPDATE assets SET `group` = :to WHERE `group` = :from")
    suspend fun changeType(from: String, to: String): Int

    @Query(
        """
        DELETE FROM assets
        WHERE id = :id
          AND NOT EXISTS (
              SELECT 1 FROM savings_goal_assets WHERE assetId = :id
          )
        """,
    )
    suspend fun deleteIfUnlinked(id: String): Int

    /** Wipes the whole table — only called by `clearFinancialTables` inside its FK-ordered
     *  clear-all transaction, AFTER savings goals and group members are already gone. */
    @Query("DELETE FROM assets")
    suspend fun deleteAll()
}
