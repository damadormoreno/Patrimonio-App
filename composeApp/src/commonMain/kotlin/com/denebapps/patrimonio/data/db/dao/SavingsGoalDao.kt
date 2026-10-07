package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SavingsGoalRelationRow(
    @Embedded val goal: SavingsGoalEntity,
    @Relation(parentColumn = "id", entityColumn = "goalId")
    val allocationEvents: List<SavingsGoalAllocationEventEntity>,
    @Relation(parentColumn = "id", entityColumn = "goalId")
    val linkEvents: List<SavingsGoalLinkEventEntity>,
    @Relation(parentColumn = "id", entityColumn = "goalId")
    val linkedAssets: List<SavingsGoalAssetEntity>,
)

data class SavingsGoalRelations(
    val goal: SavingsGoalEntity,
    val allocationEvents: List<SavingsGoalAllocationEventEntity>,
    val linkEvents: List<SavingsGoalLinkEventEntity>,
    val linkedAssetIds: Set<String>,
)

interface SavingsGoalDataSource {
    fun observeAll(): Flow<List<SavingsGoalRelations>>

    fun observeAllocationHistory(goalId: String): Flow<List<SavingsGoalAllocationEventEntity>>

    fun observeLinkHistory(goalId: String): Flow<List<SavingsGoalLinkEventEntity>>

    suspend fun listAllocationHistory(goalId: String): List<SavingsGoalAllocationEventEntity>

    suspend fun findGoal(goalId: String): SavingsGoalEntity?

    suspend fun listLinkedAssetIds(goalId: String): List<String>

    suspend fun listGoalsLinkedToAsset(assetId: String): List<SavingsGoalEntity>

    suspend fun listGoalsLinkedToGroup(groupId: String): List<SavingsGoalEntity>

    suspend fun insertGoal(goal: SavingsGoalEntity)

    suspend fun insertAllocationEvent(event: SavingsGoalAllocationEventEntity): Long

    suspend fun insertLinkEvent(event: SavingsGoalLinkEventEntity): Long

    suspend fun insertLinkedAssets(rows: List<SavingsGoalAssetEntity>)

    suspend fun deleteLinkedAssets(goalId: String, assetIds: List<String>): Int

    /** Sets (or, with null, clears) the group link. The caller keeps it exclusive with the asset rows. */
    suspend fun updateLinkedGroup(goalId: String, linkedGroupId: String?): Int

    /** Removes [assetId] from every goal following it. */
    suspend fun clearLinkedAsset(assetId: String): Int

    suspend fun clearLinkedGroup(groupId: String): Int

    suspend fun updateLifecycle(goalId: String, lifecycle: String): Int

    suspend fun updateDetails(goalId: String, name: String, targetMinor: Long, targetDateEpochDay: Long?): Int

    /** Deletes the goal; its events and linked-asset rows go with it (ON DELETE CASCADE). */
    suspend fun deleteGoal(goalId: String): Int
}

@Dao
abstract class SavingsGoalDao : SavingsGoalDataSource {
    @Transaction
    @Query("SELECT * FROM savings_goals ORDER BY createdAtEpochMs, id")
    protected abstract fun observeRelationRows(): Flow<List<SavingsGoalRelationRow>>

    override fun observeAll(): Flow<List<SavingsGoalRelations>> =
        observeRelationRows().map { rows -> rows.map(SavingsGoalRelationRow::toOrderedRelations) }

    @Query(
        "SELECT * FROM savings_goal_allocation_events " +
            "WHERE goalId = :goalId ORDER BY timestampEpochMs, id",
    )
    abstract override fun observeAllocationHistory(goalId: String): Flow<List<SavingsGoalAllocationEventEntity>>

    @Query(
        "SELECT * FROM savings_goal_allocation_events " +
            "WHERE goalId = :goalId ORDER BY timestampEpochMs, id",
    )
    abstract override suspend fun listAllocationHistory(goalId: String): List<SavingsGoalAllocationEventEntity>

    @Query(
        "SELECT * FROM savings_goal_link_events " +
            "WHERE goalId = :goalId ORDER BY timestampEpochMs, id",
    )
    abstract override fun observeLinkHistory(goalId: String): Flow<List<SavingsGoalLinkEventEntity>>

    @Query("SELECT * FROM savings_goals WHERE id = :goalId")
    abstract override suspend fun findGoal(goalId: String): SavingsGoalEntity?

    @Query("SELECT assetId FROM savings_goal_assets WHERE goalId = :goalId ORDER BY assetId")
    abstract override suspend fun listLinkedAssetIds(goalId: String): List<String>

    @Query(
        "SELECT g.* FROM savings_goals g JOIN savings_goal_assets l ON l.goalId = g.id " +
            "WHERE l.assetId = :assetId ORDER BY g.createdAtEpochMs, g.id",
    )
    abstract override suspend fun listGoalsLinkedToAsset(assetId: String): List<SavingsGoalEntity>

    @Query("SELECT * FROM savings_goals WHERE linkedGroupId = :groupId ORDER BY createdAtEpochMs, id")
    abstract override suspend fun listGoalsLinkedToGroup(groupId: String): List<SavingsGoalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override suspend fun insertGoal(goal: SavingsGoalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override suspend fun insertAllocationEvent(event: SavingsGoalAllocationEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override suspend fun insertLinkEvent(event: SavingsGoalLinkEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override suspend fun insertLinkedAssets(rows: List<SavingsGoalAssetEntity>)

    @Query("DELETE FROM savings_goal_assets WHERE goalId = :goalId AND assetId IN (:assetIds)")
    abstract override suspend fun deleteLinkedAssets(goalId: String, assetIds: List<String>): Int

    @Query("UPDATE savings_goals SET linkedGroupId = :linkedGroupId WHERE id = :goalId")
    abstract override suspend fun updateLinkedGroup(goalId: String, linkedGroupId: String?): Int

    @Query("DELETE FROM savings_goal_assets WHERE assetId = :assetId")
    abstract override suspend fun clearLinkedAsset(assetId: String): Int

    @Query("UPDATE savings_goals SET linkedGroupId = NULL WHERE linkedGroupId = :groupId")
    abstract override suspend fun clearLinkedGroup(groupId: String): Int

    @Query("UPDATE savings_goals SET lifecycle = :lifecycle WHERE id = :goalId")
    abstract override suspend fun updateLifecycle(goalId: String, lifecycle: String): Int

    @Query(
        "UPDATE savings_goals SET name = :name, targetMinor = :targetMinor, " +
            "targetDateEpochDay = :targetDateEpochDay WHERE id = :goalId",
    )
    abstract override suspend fun updateDetails(
        goalId: String,
        name: String,
        targetMinor: Long,
        targetDateEpochDay: Long?,
    ): Int

    @Query("DELETE FROM savings_goals WHERE id = :goalId")
    abstract override suspend fun deleteGoal(goalId: String): Int

    /** Whole-table reads for backup export. NOT part of [SavingsGoalDataSource]. */
    @Query("SELECT * FROM savings_goals ORDER BY createdAtEpochMs, id")
    abstract suspend fun listAllGoals(): List<SavingsGoalEntity>

    @Query("SELECT * FROM savings_goal_allocation_events ORDER BY goalId, timestampEpochMs, id")
    abstract suspend fun listAllAllocationEvents(): List<SavingsGoalAllocationEventEntity>

    @Query("SELECT * FROM savings_goal_link_events ORDER BY goalId, timestampEpochMs, id")
    abstract suspend fun listAllLinkEvents(): List<SavingsGoalLinkEventEntity>

    @Query("SELECT * FROM savings_goal_assets ORDER BY goalId, assetId")
    abstract suspend fun listAllLinkedAssets(): List<SavingsGoalAssetEntity>

    /** Wipes every allocation event — only called by `clearFinancialTables` inside its
     *  FK-ordered clear-all transaction, BEFORE the goals themselves. NOT part of
     *  [SavingsGoalDataSource]: the destructive command reaches the concrete DAO directly. */
    @Query("DELETE FROM savings_goal_allocation_events")
    abstract suspend fun deleteAllAllocationEvents()

    /** Wipes every link event — only called by `clearFinancialTables` inside its
     *  FK-ordered clear-all transaction, BEFORE the goals themselves. */
    @Query("DELETE FROM savings_goal_link_events")
    abstract suspend fun deleteAllLinkEvents()

    /** Wipes every goal-account row — only called by `clearFinancialTables`, BEFORE the goals and the
     *  assets they reference. */
    @Query("DELETE FROM savings_goal_assets")
    abstract suspend fun deleteAllLinkedAssets()

    /** Wipes every goal — only called by `clearFinancialTables` inside its FK-ordered
     *  clear-all transaction, AFTER its child tables and BEFORE the linked assets. */
    @Query("DELETE FROM savings_goals")
    abstract suspend fun deleteAllGoals()
}

private fun SavingsGoalRelationRow.toOrderedRelations() = SavingsGoalRelations(
    goal = goal,
    allocationEvents = allocationEvents.sortedWith(compareBy({ it.timestampEpochMs }, { it.id })),
    linkEvents = linkEvents.sortedWith(compareBy({ it.timestampEpochMs }, { it.id })),
    linkedAssetIds = linkedAssets.mapTo(mutableSetOf()) { it.assetId },
)
