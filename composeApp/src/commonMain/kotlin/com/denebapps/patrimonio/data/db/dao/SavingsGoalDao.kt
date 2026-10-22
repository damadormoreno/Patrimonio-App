package com.denebapps.patrimonio.data.db.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalGroupEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SavingsGoalRelationRow(
    @Embedded val goal: SavingsGoalEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["goalId"])
    val allocationEvents: List<SavingsGoalAllocationEventEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["goalId"])
    val linkEvents: List<SavingsGoalLinkEventEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["goalId"])
    val linkedAssets: List<SavingsGoalAssetEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["goalId"])
    val linkedHoldings: List<SavingsGoalHoldingEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["goalId"])
    val linkedGroups: List<SavingsGoalGroupEntity>,
)

data class SavingsGoalRelations(
    val goal: SavingsGoalEntity,
    val allocationEvents: List<SavingsGoalAllocationEventEntity>,
    val linkEvents: List<SavingsGoalLinkEventEntity>,
    val linkedAssetIds: Set<String>,
    val linkedHoldingIds: Set<String>,
    val linkedGroupIds: Set<String>,
)

interface SavingsGoalDataSource {
    fun observeAll(): Flow<List<SavingsGoalRelations>>

    fun observeAllocationHistory(goalId: String): Flow<List<SavingsGoalAllocationEventEntity>>

    fun observeLinkHistory(goalId: String): Flow<List<SavingsGoalLinkEventEntity>>

    suspend fun listAllocationHistory(goalId: String): List<SavingsGoalAllocationEventEntity>

    suspend fun findGoal(goalId: String): SavingsGoalEntity?

    suspend fun listLinkedAssetIds(goalId: String): List<String>

    suspend fun listGoalsLinkedToAsset(assetId: String): List<SavingsGoalEntity>

    suspend fun listLinkedHoldingIds(goalId: String): List<String>

    suspend fun listLinkedGroupIds(goalId: String): List<String>

    suspend fun listGoalsLinkedToHolding(holdingId: String): List<SavingsGoalEntity>

    suspend fun listGoalsLinkedToGroup(groupId: String): List<SavingsGoalEntity>

    suspend fun insertGoal(goal: SavingsGoalEntity)

    suspend fun insertAllocationEvent(event: SavingsGoalAllocationEventEntity): Long

    suspend fun insertLinkEvent(event: SavingsGoalLinkEventEntity): Long

    suspend fun insertLinkedAssets(rows: List<SavingsGoalAssetEntity>)

    suspend fun deleteLinkedAssets(goalId: String, assetIds: List<String>): Int

    suspend fun insertLinkedHoldings(rows: List<SavingsGoalHoldingEntity>)

    suspend fun deleteLinkedHoldings(goalId: String, holdingIds: List<String>): Int

    suspend fun insertLinkedGroups(rows: List<SavingsGoalGroupEntity>)

    suspend fun deleteLinkedGroups(goalId: String, groupIds: List<String>): Int

    /** Removes [assetId] from every goal following it. */
    suspend fun clearLinkedAsset(assetId: String): Int

    /** Removes [holdingId] from every goal following it directly. */
    suspend fun clearLinkedHolding(holdingId: String): Int

    /** Removes [groupId] from every goal following it. */
    suspend fun clearLinkedGroup(groupId: String): Int

    suspend fun updateLifecycle(goalId: String, lifecycle: String): Int

    suspend fun updateDetails(goalId: String, name: String, targetMinor: Long, targetDateEpochDay: Long?): Int

    /** Deletes the goal; its events and linked account, product and group rows go with it (ON DELETE CASCADE). */
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

    @Query("SELECT holdingId FROM savings_goal_holdings WHERE goalId = :goalId ORDER BY holdingId")
    abstract override suspend fun listLinkedHoldingIds(goalId: String): List<String>

    @Query("SELECT groupId FROM savings_goal_groups WHERE goalId = :goalId ORDER BY groupId")
    abstract override suspend fun listLinkedGroupIds(goalId: String): List<String>

    @Query(
        "SELECT g.* FROM savings_goals g JOIN savings_goal_holdings l ON l.goalId = g.id " +
            "WHERE l.holdingId = :holdingId ORDER BY g.createdAtEpochMs, g.id",
    )
    abstract override suspend fun listGoalsLinkedToHolding(holdingId: String): List<SavingsGoalEntity>

    @Query(
        "SELECT g.* FROM savings_goals g JOIN savings_goal_groups l ON l.goalId = g.id " +
            "WHERE l.groupId = :groupId ORDER BY g.createdAtEpochMs, g.id",
    )
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override suspend fun insertLinkedHoldings(rows: List<SavingsGoalHoldingEntity>)

    @Query("DELETE FROM savings_goal_holdings WHERE goalId = :goalId AND holdingId IN (:holdingIds)")
    abstract override suspend fun deleteLinkedHoldings(goalId: String, holdingIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract override suspend fun insertLinkedGroups(rows: List<SavingsGoalGroupEntity>)

    @Query("DELETE FROM savings_goal_groups WHERE goalId = :goalId AND groupId IN (:groupIds)")
    abstract override suspend fun deleteLinkedGroups(goalId: String, groupIds: List<String>): Int

    @Query("DELETE FROM savings_goal_assets WHERE assetId = :assetId")
    abstract override suspend fun clearLinkedAsset(assetId: String): Int

    @Query("DELETE FROM savings_goal_holdings WHERE holdingId = :holdingId")
    abstract override suspend fun clearLinkedHolding(holdingId: String): Int

    @Query("DELETE FROM savings_goal_groups WHERE groupId = :groupId")
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

    @Query("SELECT * FROM savings_goal_holdings ORDER BY goalId, holdingId")
    abstract suspend fun listAllLinkedHoldings(): List<SavingsGoalHoldingEntity>

    @Query("SELECT * FROM savings_goal_groups ORDER BY goalId, groupId")
    abstract suspend fun listAllLinkedGroups(): List<SavingsGoalGroupEntity>

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

    /** Wipes every goal-product row — only called by `clearFinancialTables`, BEFORE the goals and the holdings they
     *  reference. */
    @Query("DELETE FROM savings_goal_holdings")
    abstract suspend fun deleteAllLinkedHoldings()

    /** Wipes every goal-group row — only called by `clearFinancialTables`, BEFORE the goals and the groups they
     *  reference. */
    @Query("DELETE FROM savings_goal_groups")
    abstract suspend fun deleteAllLinkedGroups()

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
    linkedHoldingIds = linkedHoldings.mapTo(mutableSetOf()) { it.holdingId },
    linkedGroupIds = linkedGroups.mapTo(mutableSetOf()) { it.groupId },
)
