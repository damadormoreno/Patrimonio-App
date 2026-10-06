package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.AccountGroupDao
import com.denebapps.patrimonio.data.db.dao.AccountGroupWithMembers
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The builtin "all accounts" group is synthesized here (never a persisted row) and always
 *  prepended first (spec: `builtin-group-synthesized`). */
class AccountGroupRepositoryImpl(
    private val accountGroupDao: AccountGroupDao,
    private val seedingGate: SeedingGate,
) : AccountGroupRepository {
    override fun observeAll(): Flow<List<AccountGroup>> = flow {
        seedingGate.await()
        emitAll(
            accountGroupDao.observeAll().map { rows -> listOf(AccountGroup.allAccounts()) + rows.map(::toDomain) },
        )
    }

    override suspend fun insertGroup(group: AccountGroup) {
        seedingGate.await()
        accountGroupDao.insertGroup(
            AccountGroupEntity(
                id = group.id,
                name = group.name,
                showBalance = group.showBalance,
                sortOrder = group.sortOrder,
            ),
        )
        group.memberAssetIds?.forEach { assetId ->
            accountGroupDao.insertMember(AccountGroupMemberEntity(group.id, assetId))
        }
    }

    override suspend fun setMembers(groupId: String, assetIds: Set<String>) {
        seedingGate.await()
        accountGroupDao.deleteMembers(groupId)
        assetIds.forEach { assetId -> accountGroupDao.insertMember(AccountGroupMemberEntity(groupId, assetId)) }
    }

    /** `account_group_members` rows are removed via the CASCADE FK automatically — no explicit
     *  [AccountGroupDao.deleteMembers] call needed here (spec: `account-groups-crud`). */
    override suspend fun deleteGroup(id: String) {
        seedingGate.await()
        accountGroupDao.deleteGroup(id)
    }
}

private fun toDomain(row: AccountGroupWithMembers): AccountGroup = AccountGroup(
    id = row.group.id,
    name = row.group.name,
    showBalance = row.group.showBalance,
    sortOrder = row.group.sortOrder,
    memberAssetIds = row.members.map { it.assetId }.toSet(),
)
