package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.AccountGroupRef
import kotlinx.coroutines.flow.Flow

interface AccountGroupRepository {
    /** Includes the synthesized builtin "all accounts" group first; that group is never a row in
     *  the underlying table. Each group carries its accounts and its portfolio holdings. */
    fun observeAll(): Flow<List<AccountGroup>>

    /** Adds [group] with its accounts and holdings in one transaction.
     *  @throws PortfolioHoldingNotFoundException when a member holding does not exist; nothing is written. */
    suspend fun insertGroup(group: AccountGroup)

    /** Replaces the accounts and the holdings of the group in one transaction.
     *  @throws PortfolioHoldingNotFoundException when a member holding does not exist; nothing changes. */
    suspend fun setMembers(groupId: String, assetIds: Set<String>, holdingIds: Set<String>)

    /** Renames [group], updates its balance visibility and replaces its accounts and holdings, keeping its id,
     *  position and linked savings goals. The builtin "all accounts" group cannot be edited.
     *  @throws AccountGroupNotFoundException when no persisted group has that id.
     *  @throws PortfolioHoldingNotFoundException when a member holding does not exist; nothing changes. */
    suspend fun updateGroup(group: AccountGroup)

    /** Persists the user's order: each id in [groupIds] gets its index as `sortOrder`. Unknown ids
     *  (and the builtin group, which always stays first) are ignored. */
    suspend fun reorderGroups(groupIds: List<String>)

    suspend fun deleteGroup(id: String)

    /** The groups the holding [holdingId] is a member of, in the user's order; empty when it is in none (or does not
     *  exist). A one-shot read for the warning before deleting a holding: the memberships themselves go with it. */
    suspend fun groupsContainingHolding(holdingId: String): List<AccountGroupRef>
}

class AccountGroupNotFoundException(groupId: String) : NoSuchElementException("Account group '$groupId' was not found")
