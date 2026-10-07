package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.AccountGroupDao
import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.dao.SavingsGoalRelations
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalAdd
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalNegate
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalProgress
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalSubtract
import com.denebapps.patrimonio.domain.calc.savingsGoalCancellationDelta
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalAllocationEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalDeltaException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalNameException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalTargetException
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.TerminalSavingsGoalException
import com.denebapps.patrimonio.domain.repository.UpdateSavingsGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class SavingsGoalRepositoryImpl(
    private val database: AppDatabase,
    private val dataSource: SavingsGoalDataSource,
    private val assetDao: AssetDao,
    private val seedingGate: SeedingGate,
    private val clock: Clock,
    private val accountGroupDao: AccountGroupDao = database.accountGroupDao(),
    private val newGoalId: () -> String = ::randomGoalId,
) : SavingsGoalRepository {
    override fun observeAll(): Flow<List<SavingsGoal>> = flow {
        seedingGate.await()
        emitAll(dataSource.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override fun observeAllocationHistory(goalId: String): Flow<List<SavingsGoalAllocationEvent>> = flow {
        seedingGate.await()
        emitAll(
            dataSource.observeAllocationHistory(goalId).map { rows ->
                rows.map(::toDomain).also(::checkedSavingsGoalProgress)
            },
        )
    }

    override fun observeLinkHistory(goalId: String): Flow<List<SavingsGoalLinkEvent>> = flow {
        seedingGate.await()
        emitAll(dataSource.observeLinkHistory(goalId).map { rows -> rows.map(::toDomain) })
    }

    override suspend fun create(command: CreateSavingsGoal): String {
        seedingGate.await()
        validateDetails(command.name, command.target.amount)
        return database.writeTransaction {
            command.linkedAssetIds.forEach { assetId -> requireAsset(assetId) }
            command.linkedGroupId?.let { groupId -> requireLinkableGroup(groupId) }
            val goalId = newGoalId()
            val now = clock.now().toEpochMilliseconds()
            dataSource.insertGoal(
                SavingsGoalEntity(
                    id = goalId,
                    name = command.name,
                    targetMinor = command.target.amount.minorUnits,
                    currency = command.target.currency.code,
                    targetDateEpochDay = command.targetDate?.toEpochDays()?.toLong(),
                    lifecycle = SavingsGoalLifecycle.OPEN.name,
                    createdAtEpochMs = now,
                ),
            )
            // The links, group included, are written by changeLinks like on any later edit.
            changeLinks(goalId, LinkState.NONE, LinkState(command.linkedAssetIds, command.linkedGroupId), now)
            goalId
        }
    }

    override suspend fun allocate(goalId: String, amount: Money) {
        seedingGate.await()
        requirePositiveDelta(amount)
        database.writeTransaction {
            requireOpenGoal(goalId)
            val progress = currentProgress(goalId)
            checkedSavingsGoalAdd(progress, amount)
            dataSource.insertAllocationEvent(allocationEvent(goalId, amount))
        }
    }

    override suspend fun withdraw(goalId: String, amount: Money) {
        seedingGate.await()
        requirePositiveDelta(amount)
        database.writeTransaction {
            requireOpenGoal(goalId)
            val updated = checkedSavingsGoalSubtract(currentProgress(goalId), amount)
            if (updated < Money.ZERO) throw NegativeSavingsGoalProgressException(updated.minorUnits)
            dataSource.insertAllocationEvent(allocationEvent(goalId, checkedSavingsGoalNegate(amount)))
        }
    }

    override suspend fun close(goalId: String) {
        seedingGate.await()
        database.writeTransaction {
            requireOpenGoal(goalId)
            dataSource.updateLifecycle(goalId, SavingsGoalLifecycle.CLOSED.name)
        }
    }

    override suspend fun cancel(goalId: String) {
        seedingGate.await()
        database.writeTransaction {
            requireOpenGoal(goalId)
            savingsGoalCancellationDelta(currentProgress(goalId))?.let { delta ->
                dataSource.insertAllocationEvent(allocationEvent(goalId, delta))
            }
            dataSource.updateLifecycle(goalId, SavingsGoalLifecycle.CANCELLED.name)
        }
    }

    override suspend fun update(goalId: String, command: UpdateSavingsGoal) {
        seedingGate.await()
        validateDetails(command.name, command.targetAmount)
        database.writeTransaction {
            val goal = requireOpenGoal(goalId)
            dataSource.updateDetails(
                goalId = goalId,
                name = command.name,
                targetMinor = command.targetAmount.minorUnits,
                targetDateEpochDay = command.targetDate?.toEpochDays()?.toLong(),
            )
            val previous = LinkState(dataSource.listLinkedAssetIds(goalId).toSet(), goal.linkedGroupId)
            val next = LinkState(command.linkedAssetIds, command.linkedGroupId)
            (next.assetIds - previous.assetIds).forEach { assetId -> requireAsset(assetId) }
            if (next.groupId != null && next.groupId != previous.groupId) requireLinkableGroup(next.groupId)
            changeLinks(goalId, previous, next, clock.now().toEpochMilliseconds())
        }
    }

    /**
     * Rewrites the goal's links from [previous] to [next] (already validated) with one audit event per
     * change: LINK/UNLINK for every account added/removed and for a group set/cleared, RELINK when a
     * group is swapped for another. Removals come first so the history reads in that order.
     */
    private suspend fun changeLinks(goalId: String, previous: LinkState, next: LinkState, timestampEpochMs: Long) {
        val removedAssets = (previous.assetIds - next.assetIds).sorted()
        val addedAssets = (next.assetIds - previous.assetIds).sorted()
        if (removedAssets.isNotEmpty()) dataSource.deleteLinkedAssets(goalId, removedAssets)
        if (previous.groupId != next.groupId) dataSource.updateLinkedGroup(goalId, next.groupId)
        if (addedAssets.isNotEmpty()) {
            dataSource.insertLinkedAssets(addedAssets.map { assetId -> SavingsGoalAssetEntity(goalId, assetId) })
        }
        val groupChange = LinkChange(previous.groupTarget, next.groupTarget).takeIf { it.from != it.to }
        val changes = removedAssets.map { LinkChange(LinkTarget.Asset(it), null) } +
            listOfNotNull(groupChange) +
            addedAssets.map { LinkChange(null, LinkTarget.Asset(it)) }
        changes.forEach { change -> dataSource.insertLinkEvent(change.toEntity(goalId, timestampEpochMs)) }
    }

    override suspend fun delete(goalId: String) {
        seedingGate.await()
        if (dataSource.deleteGoal(goalId) == 0) throw SavingsGoalNotFoundException(goalId)
    }

    private suspend fun requireOpenGoal(goalId: String): SavingsGoalEntity {
        val goal = dataSource.findGoal(goalId) ?: throw SavingsGoalNotFoundException(goalId)
        val lifecycle = SavingsGoalLifecycle.valueOf(goal.lifecycle)
        if (lifecycle != SavingsGoalLifecycle.OPEN) throw TerminalSavingsGoalException(goalId, lifecycle)
        return goal
    }

    private suspend fun currentProgress(goalId: String): Money =
        checkedSavingsGoalProgress(dataSource.listAllocationHistory(goalId).map(::toDomain))

    /** Any currency is fine: the tracked balance converts each account to the goal's currency. */
    private suspend fun requireAsset(assetId: String) {
        assetDao.find(assetId) ?: throw SavingsGoalAssetNotFoundException(assetId)
    }

    /** Only persisted groups are linkable: the builtin "all accounts" group is synthesized, never a row. */
    private suspend fun requireLinkableGroup(groupId: String) {
        if (groupId == AccountGroup.ALL_ACCOUNTS_ID) throw SavingsGoalBuiltinGroupException()
        accountGroupDao.findGroup(groupId) ?: throw SavingsGoalGroupNotFoundException(groupId)
    }

    private fun allocationEvent(goalId: String, delta: Money) = SavingsGoalAllocationEventEntity(
        goalId = goalId,
        deltaMinor = delta.minorUnits,
        timestampEpochMs = clock.now().toEpochMilliseconds(),
    )
}

/** One side of a link event: an asset or a group. */
private sealed interface LinkTarget {
    data class Asset(val id: String) : LinkTarget

    data class Group(val id: String) : LinkTarget
}

/** Everything a goal follows: some accounts XOR one group XOR nothing. */
private data class LinkState(val assetIds: Set<String>, val groupId: String?) {
    val groupTarget: LinkTarget.Group? get() = groupId?.let { LinkTarget.Group(it) }

    companion object {
        val NONE = LinkState(emptySet(), null)
    }
}

/** One link event: [from] null means LINK, [to] null UNLINK, both set RELINK. */
private data class LinkChange(val from: LinkTarget?, val to: LinkTarget?) {
    fun toEntity(goalId: String, timestampEpochMs: Long) = SavingsGoalLinkEventEntity(
        goalId = goalId,
        fromAssetId = (from as? LinkTarget.Asset)?.id,
        toAssetId = (to as? LinkTarget.Asset)?.id,
        kind = when {
            from == null -> SavingsGoalLinkEventKind.LINK
            to == null -> SavingsGoalLinkEventKind.UNLINK
            else -> SavingsGoalLinkEventKind.RELINK
        }.name,
        timestampEpochMs = timestampEpochMs,
        fromGroupId = (from as? LinkTarget.Group)?.id,
        toGroupId = (to as? LinkTarget.Group)?.id,
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun randomGoalId(): String = Uuid.random().toString()

private fun validateDetails(name: String, target: Money) {
    if (name.isEmpty() || name != name.trim()) throw InvalidSavingsGoalNameException(name)
    if (target <= Money.ZERO) throw InvalidSavingsGoalTargetException(target.minorUnits)
}

private fun requirePositiveDelta(amount: Money) {
    if (amount <= Money.ZERO) throw InvalidSavingsGoalDeltaException(amount.minorUnits)
}

private fun toDomain(row: SavingsGoalRelations): SavingsGoal {
    val events = row.allocationEvents.map(::toDomain)
    return SavingsGoal(
        id = row.goal.id,
        name = row.goal.name,
        target = CurrencyAmount(Money(row.goal.targetMinor), Currency.valueOf(row.goal.currency)),
        targetDate = row.goal.targetDateEpochDay?.let { LocalDate.fromEpochDays(it.toInt()) },
        linkedAssetIds = row.linkedAssetIds,
        lifecycle = SavingsGoalLifecycle.valueOf(row.goal.lifecycle),
        progress = checkedSavingsGoalProgress(events),
        linkedGroupId = row.goal.linkedGroupId,
    )
}

private fun toDomain(entity: SavingsGoalAllocationEventEntity) =
    SavingsGoalAllocationEvent(entity.id, entity.goalId, Money(entity.deltaMinor), entity.timestampEpochMs)

private fun toDomain(entity: SavingsGoalLinkEventEntity) = SavingsGoalLinkEvent(
    id = entity.id,
    goalId = entity.goalId,
    fromAssetId = entity.fromAssetId,
    toAssetId = entity.toAssetId,
    kind = SavingsGoalLinkEventKind.valueOf(entity.kind),
    timestampEpochMs = entity.timestampEpochMs,
    fromGroupId = entity.fromGroupId,
    toGroupId = entity.toGroupId,
)
