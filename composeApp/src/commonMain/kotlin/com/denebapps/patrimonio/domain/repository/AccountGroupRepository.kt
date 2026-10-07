package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.AccountGroup
import kotlinx.coroutines.flow.Flow

interface AccountGroupRepository {
    /** Includes the synthesized builtin "all accounts" group first; that group is never a row in
     *  the underlying table. */
    fun observeAll(): Flow<List<AccountGroup>>

    suspend fun insertGroup(group: AccountGroup)

    suspend fun setMembers(groupId: String, assetIds: Set<String>)

    /** Renames [group], updates its balance visibility and replaces its members, keeping its id,
     *  position and linked savings goals. The builtin "all accounts" group cannot be edited.
     *  @throws AccountGroupNotFoundException when no persisted group has that id. */
    suspend fun updateGroup(group: AccountGroup)

    /** Persists the user's order: each id in [groupIds] gets its index as `sortOrder`. Unknown ids
     *  (and the builtin group, which always stays first) are ignored. */
    suspend fun reorderGroups(groupIds: List<String>)

    suspend fun deleteGroup(id: String)
}

class AccountGroupNotFoundException(groupId: String) : NoSuchElementException("Account group '$groupId' was not found")
