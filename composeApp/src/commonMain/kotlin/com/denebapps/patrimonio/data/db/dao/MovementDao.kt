package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.denebapps.patrimonio.data.db.entity.MovementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovementDao {
    @Query("SELECT * FROM movements ORDER BY epochDay, sequence, id")
    fun observeAll(): Flow<List<MovementEntity>>

    @Query("SELECT * FROM movements WHERE holdingId = :holdingId ORDER BY epochDay, sequence, id")
    fun observeByHolding(holdingId: String): Flow<List<MovementEntity>>

    @Query("SELECT DISTINCT holdingId FROM movements")
    fun observeHoldingIdsWithMovements(): Flow<List<String>>

    @Query("SELECT * FROM movements ORDER BY epochDay, sequence, id")
    suspend fun list(): List<MovementEntity>

    @Query("SELECT * FROM movements WHERE holdingId = :holdingId ORDER BY epochDay, sequence, id")
    suspend fun listByHolding(holdingId: String): List<MovementEntity>

    @Query("SELECT * FROM movements WHERE id = :id")
    suspend fun find(id: String): MovementEntity?

    @Query("SELECT * FROM movements WHERE transferId = :transferId")
    suspend fun listByTransfer(transferId: String): List<MovementEntity>

    /** The highest entry sequence in use, `null` while there are no movements. */
    @Query("SELECT MAX(sequence) FROM movements")
    suspend fun maxSequence(): Long?

    @Query("SELECT COUNT(*) FROM movements WHERE holdingId = :holdingId")
    suspend fun countForHolding(holdingId: String): Int

    /** How many movements of [holdingId] are transfer legs, whatever the state of the other leg. */
    @Query(
        "SELECT COUNT(*) FROM movements WHERE holdingId = :holdingId AND " +
            "(type IN ('TRANSFER_OUT', 'TRANSFER_IN') OR transferId IS NOT NULL)",
    )
    suspend fun countTransferLegs(holdingId: String): Int

    /** How many movements of [holdingId] are legs of a transfer whose other leg is on another holding. */
    @Query(
        "SELECT COUNT(*) FROM movements m WHERE m.holdingId = :holdingId AND m.transferId IS NOT NULL AND EXISTS " +
            "(SELECT 1 FROM movements o WHERE o.transferId = m.transferId AND o.holdingId <> m.holdingId)",
    )
    suspend fun countTransferLegsWithOtherHoldings(holdingId: String): Int

    /** Fails when the id already exists: movements are never silently overwritten. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: MovementEntity)

    /** Replaces the whole row; @return how many rows matched (0 or 1). */
    @Update
    suspend fun update(movement: MovementEntity): Int

    @Query("DELETE FROM movements WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM movements WHERE transferId = :transferId")
    suspend fun deleteByTransfer(transferId: String): Int

    /** Only called by `deleteFinancialRows` and the backup import. */
    @Query("DELETE FROM movements")
    suspend fun deleteAll()
}
