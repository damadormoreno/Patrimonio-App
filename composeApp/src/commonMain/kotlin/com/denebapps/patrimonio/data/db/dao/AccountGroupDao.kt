package com.denebapps.patrimonio.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import kotlinx.coroutines.flow.Flow

data class AccountGroupWithMembers(
    @Embedded val group: AccountGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val members: List<AccountGroupMemberEntity>,
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: AccountGroupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: AccountGroupMemberEntity)

    /** Clears/replaces a group's members while keeping the group (edit-membership flows). NOT
     *  used for delete-group cleanup — that is handled by the `account_group_members` CASCADE FK
     *  automatically when [deleteGroup] runs. */
    @Query("DELETE FROM account_group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: String)

    @Query("DELETE FROM account_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)

    /** Wipes every membership row — only called by `clearFinancialTables` inside its
     *  FK-ordered clear-all transaction, BEFORE the groups themselves. */
    @Query("DELETE FROM account_group_members")
    suspend fun deleteAllMembers()

    /** Wipes every group — only called by `clearFinancialTables` inside its FK-ordered
     *  clear-all transaction, AFTER [deleteAllMembers]. */
    @Query("DELETE FROM account_groups")
    suspend fun deleteAllGroups()
}
