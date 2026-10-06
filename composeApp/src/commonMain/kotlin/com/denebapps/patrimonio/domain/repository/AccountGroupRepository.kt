package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.AccountGroup
import kotlinx.coroutines.flow.Flow

interface AccountGroupRepository {
    /** Includes the synthesized builtin "all accounts" group first; that group is never a row in
     *  the underlying table. */
    fun observeAll(): Flow<List<AccountGroup>>

    suspend fun insertGroup(group: AccountGroup)

    suspend fun setMembers(groupId: String, assetIds: Set<String>)

    suspend fun deleteGroup(id: String)
}
