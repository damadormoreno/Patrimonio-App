package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.denebapps.patrimonio.data.db.entity.AccountTypeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountTypeDao {
    @Query("SELECT * FROM account_types ORDER BY position, name")
    fun observeAll(): Flow<List<AccountTypeEntity>>

    @Query("SELECT * FROM account_types ORDER BY position, name")
    suspend fun list(): List<AccountTypeEntity>

    @Query("SELECT * FROM account_types WHERE id = :id")
    suspend fun find(id: String): AccountTypeEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM account_types WHERE kind = :kind")
    suspend fun maxPosition(kind: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(type: AccountTypeEntity)

    @Query("UPDATE account_types SET name = :name, emoji = :emoji, color = :color WHERE id = :id")
    suspend fun update(id: String, name: String, emoji: String, color: String): Int

    @Query("DELETE FROM account_types WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Only called by `clearFinancialTables`. */
    @Query("DELETE FROM account_types")
    suspend fun deleteAll()
}
