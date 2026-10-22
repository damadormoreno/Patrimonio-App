package com.denebapps.patrimonio.data.db.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupHoldingMemberEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import kotlinx.coroutines.flow.Flow

data class AccountGroupWithMembers(
    @Embedded val group: AccountGroupEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["groupId"])
    val members: List<AccountGroupMemberEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["groupId"])
    val holdingMembers: List<AccountGroupHoldingMemberEntity>,
)

@Dao
interface AccountGroupDao {
    @Transaction
    @Query("SELECT * FROM account_groups ORDER BY sortOrder")
    fun observeAll(): Flow<List<AccountGroupWithMembers>>

    @Query("SELECT * FROM account_groups ORDER BY sortOrder, id")
    suspend fun listGroups(): List<AccountGroupEntity>

    @Query("SELECT * FROM account_groups WHERE id = :id")
    suspend fun findGroup(id: String): AccountGroupEntity?

    @Query("SELECT * FROM account_group_members ORDER BY groupId, assetId")
    suspend fun listMembers(): List<AccountGroupMemberEntity>

    @Query("SELECT * FROM account_group_holding_members ORDER BY groupId, holdingId")
    suspend fun listHoldingMembers(): List<AccountGroupHoldingMemberEntity>

    /** The groups [holdingId] is a member of, in the user's order (the builtin group is never a row). */
    @Query(
        "SELECT g.* FROM account_groups AS g " +
            "INNER JOIN account_group_holding_members AS m ON m.groupId = g.id " +
            "WHERE m.holdingId = :holdingId ORDER BY g.sortOrder, g.id",
    )
    suspend fun listGroupsContainingHolding(holdingId: String): List<AccountGroupEntity>

    /** Adds a new group; an id that already exists fails (ABORT) instead of replacing the row. REPLACE would delete
     *  the old row first, and that fires `ON DELETE SET NULL` on the goals linked to it and the members' CASCADE. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: AccountGroupEntity)

    /** Edits a group in place, keeping its members and the goals linked to it. Never delete and re-insert a group to
     *  edit it: that cascades to the members and unlinks every savings goal pointing at the group. */
    @Query("UPDATE account_groups SET name = :name, showBalance = :showBalance WHERE id = :id")
    suspend fun updateGroup(id: String, name: String, showBalance: Boolean): Int

    @Query("UPDATE account_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: AccountGroupMemberEntity)

    /** Clears/replaces a group's members while keeping the group (edit-membership flows). NOT
     *  used for delete-group cleanup — that is handled by the `account_group_members` CASCADE FK
     *  automatically when [deleteGroup] runs. */
    @Query("DELETE FROM account_group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: String)

    /** Fails when the group or the holding does not exist (foreign keys); a repeated pair is ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHoldingMember(member: AccountGroupHoldingMemberEntity)

    /** Same as [deleteMembers], for the portfolio holdings of the group. */
    @Query("DELETE FROM account_group_holding_members WHERE groupId = :groupId")
    suspend fun deleteHoldingMembers(groupId: String)

    @Query("DELETE FROM account_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    /** Wipes every membership row — only called by `clearFinancialTables` inside its
     *  FK-ordered clear-all transaction, BEFORE the groups themselves. */
    @Query("DELETE FROM account_group_members")
    suspend fun deleteAllMembers()

    /** Same for the holdings' memberships: before the groups AND before the holdings they point at. */
    @Query("DELETE FROM account_group_holding_members")
    suspend fun deleteAllHoldingMembers()

    /** Wipes every group — only called by `clearFinancialTables` inside its FK-ordered
     *  clear-all transaction, AFTER [deleteAllMembers]. */
    @Query("DELETE FROM account_groups")
    suspend fun deleteAllGroups()
}
