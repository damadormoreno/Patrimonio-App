package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.AccountGroupDao
import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.dao.SavingsGoalRelations
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
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
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalTransitionException
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalCurrencyMismatchException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.TerminalSavingsGoalException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate

class SavingsGoalRepositoryImpl(
    private val database: AppDatabase,
    private val dataSource: SavingsGoalDataSource,
    private val assetDao: AssetDao,
    private val seedingGate: SeedingGate,
    private val clock: Clock,
    private val accountGroupDao: AccountGroupDao = database.accountGroupDao(),
) : SavingsGoalRepository {
    override fun observeAll(): Flow<List<SavingsGoal>> = flow {
        seedingGate.await()
        emitAll(dataSource.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override fun observeAllocationHistory(goalId: Long): Flow<List<SavingsGoalAllocationEvent>> = flow {
        seedingGate.await()
        emitAll(
            dataSource.observeAllocationHistory(goalId).map { rows ->
                rows.map(::toDomain).also(::checkedSavingsGoalProgress)
            },
        )
    }

    override fun observeLinkHistory(goalId: Long): Flow<List<SavingsGoalLinkEvent>> = flow {
        seedingGate.await()
        emitAll(dataSource.observeLinkHistory(goalId).map { rows -> rows.map(::toDomain) })
    }

    override suspend fun create(command: CreateSavingsGoal): Long {
        seedingGate.await()
        validateCreate(command)
        return database.writeTransaction {
            command.linkedAssetId?.let { assetId -> requireCompatibleAsset(command.target.currency, assetId) }
            command.linkedGroupId?.let { groupId -> requireLinkableGroup(groupId) }
            val goalId =
                dataSource.insertGoal(
                    SavingsGoalEntity(
                        name = command.name,
                        targetMinor = command.target.amount.minorUnits,
                        currency = command.target.currency.code,
                        targetDateEpochDay = command.targetDate?.toEpochDays()?.toLong(),
                        linkedAssetId = command.linkedAssetId,
                        lifecycle = SavingsGoalLifecycle.OPEN.name,
                        linkedGroupId = command.linkedGroupId,
                    ),
                )
            val target =
                command.linkedAssetId?.let { LinkTarget.Asset(it) }
                    ?: command.linkedGroupId?.let { LinkTarget.Group(it) }
            target?.let { dataSource.insertLinkEvent(linkEvent(goalId, null, it, SavingsGoalLinkEventKind.LINK)) }
            goalId
        }
    }

    override suspend fun allocate(goalId: Long, amount: Money) {
        seedingGate.await()
        requirePositiveDelta(amount)
        database.writeTransaction {
            requireOpenGoal(goalId)
            val progress = currentProgress(goalId)
            checkedSavingsGoalAdd(progress, amount)
            dataSource.insertAllocationEvent(allocationEvent(goalId, amount))
        }
    }

    override suspend fun withdraw(goalId: Long, amount: Money) {
        seedingGate.await()
        requirePositiveDelta(amount)
        database.writeTransaction {
            requireOpenGoal(goalId)
            val updated = checkedSavingsGoalSubtract(currentProgress(goalId), amount)
            if (updated < Money.ZERO) throw NegativeSavingsGoalProgressException(updated.minorUnits)
            dataSource.insertAllocationEvent(allocationEvent(goalId, checkedSavingsGoalNegate(amount)))
        }
    }

    override suspend fun link(goalId: Long, assetId: String) {
        seedingGate.await()
        database.writeTransaction {
            val goal = requireOpenGoal(goalId)
            if (goal.currentLink() != null) throw InvalidSavingsGoalTransitionException(goalId, "link")
            requireCompatibleAsset(Currency.valueOf(goal.currency), assetId)
            dataSource.updateLinkedAsset(goalId, assetId)
            val target = LinkTarget.Asset(assetId)
            dataSource.insertLinkEvent(linkEvent(goalId, null, target, SavingsGoalLinkEventKind.LINK))
        }
    }

    override suspend fun linkToGroup(goalId: Long, groupId: String) {
        seedingGate.await()
        database.writeTransaction {
            val goal = requireOpenGoal(goalId)
            if (goal.currentLink() != null) throw InvalidSavingsGoalTransitionException(goalId, "link")
            requireLinkableGroup(groupId)
            dataSource.updateLinkedGroup(goalId, groupId)
            val target = LinkTarget.Group(groupId)
            dataSource.insertLinkEvent(linkEvent(goalId, null, target, SavingsGoalLinkEventKind.LINK))
        }
    }

    override suspend fun relink(goalId: Long, assetId: String) {
        seedingGate.await()
        database.writeTransaction {
            val goal = requireOpenGoal(goalId)
            val previous = goal.currentLink() ?: throw InvalidSavingsGoalTransitionException(goalId, "relink")
            val target = LinkTarget.Asset(assetId)
            if (previous == target) throw InvalidSavingsGoalTransitionException(goalId, "relink")
            requireCompatibleAsset(Currency.valueOf(goal.currency), assetId)
            dataSource.updateLinkedAsset(goalId, assetId)
            dataSource.insertLinkEvent(linkEvent(goalId, previous, target, SavingsGoalLinkEventKind.RELINK))
        }
    }

    override suspend fun relinkToGroup(goalId: Long, groupId: String) {
        seedingGate.await()
        database.writeTransaction {
            val goal = requireOpenGoal(goalId)
            val previous = goal.currentLink() ?: throw InvalidSavingsGoalTransitionException(goalId, "relink")
            val target = LinkTarget.Group(groupId)
            if (previous == target) throw InvalidSavingsGoalTransitionException(goalId, "relink")
            requireLinkableGroup(groupId)
            dataSource.updateLinkedGroup(goalId, groupId)
            dataSource.insertLinkEvent(linkEvent(goalId, previous, target, SavingsGoalLinkEventKind.RELINK))
        }
    }

    override suspend fun unlink(goalId: Long) {
        seedingGate.await()
        database.writeTransaction {
            val goal = requireOpenGoal(goalId)
            val previous = goal.currentLink() ?: throw InvalidSavingsGoalTransitionException(goalId, "unlink")
            dataSource.updateLinkedAsset(goalId, null)
            dataSource.insertLinkEvent(linkEvent(goalId, previous, null, SavingsGoalLinkEventKind.UNLINK))
        }
    }

    override suspend fun close(goalId: Long) {
        seedingGate.await()
        database.writeTransaction {
            requireOpenGoal(goalId)
            dataSource.updateLifecycle(goalId, SavingsGoalLifecycle.CLOSED.name)
        }
    }

    override suspend fun cancel(goalId: Long) {
        seedingGate.await()
        database.writeTransaction {
            requireOpenGoal(goalId)
            savingsGoalCancellationDelta(currentProgress(goalId))?.let { delta ->
                dataSource.insertAllocationEvent(allocationEvent(goalId, delta))
            }
            dataSource.updateLifecycle(goalId, SavingsGoalLifecycle.CANCELLED.name)
        }
    }

    private suspend fun requireOpenGoal(goalId: Long): SavingsGoalEntity {
        val goal = dataSource.findGoal(goalId) ?: throw SavingsGoalNotFoundException(goalId)
        val lifecycle = SavingsGoalLifecycle.valueOf(goal.lifecycle)
        if (lifecycle != SavingsGoalLifecycle.OPEN) throw TerminalSavingsGoalException(goalId, lifecycle)
        return goal
    }

    private suspend fun currentProgress(goalId: Long): Money =
        checkedSavingsGoalProgress(dataSource.listAllocationHistory(goalId).map(::toDomain))

    private suspend fun requireCompatibleAsset(goalCurrency: Currency, assetId: String) {
        val asset = assetDao.find(assetId) ?: throw SavingsGoalAssetNotFoundException(assetId)
        val assetCurrency = Currency.valueOf(asset.currency)
        if (assetCurrency != goalCurrency) throw SavingsGoalCurrencyMismatchException(goalCurrency, assetCurrency)
    }

    /** Only persisted groups are linkable: the builtin "all accounts" group is synthesized, never a row. */
    private suspend fun requireLinkableGroup(groupId: String) {
        if (groupId == AccountGroup.ALL_ACCOUNTS_ID) throw SavingsGoalBuiltinGroupException()
        accountGroupDao.findGroup(groupId) ?: throw SavingsGoalGroupNotFoundException(groupId)
    }

    private fun allocationEvent(goalId: Long, delta: Money) = SavingsGoalAllocationEventEntity(
        goalId = goalId,
        deltaMinor = delta.minorUnits,
        timestampEpochMs = clock.now().toEpochMilliseconds(),
    )

    private fun linkEvent(goalId: Long, from: LinkTarget?, to: LinkTarget?, kind: SavingsGoalLinkEventKind) =
        SavingsGoalLinkEventEntity(
            goalId = goalId,
            fromAssetId = (from as? LinkTarget.Asset)?.id,
            toAssetId = (to as? LinkTarget.Asset)?.id,
            kind = kind.name,
            timestampEpochMs = clock.now().toEpochMilliseconds(),
            fromGroupId = (from as? LinkTarget.Group)?.id,
            toGroupId = (to as? LinkTarget.Group)?.id,
        )
}

/** What a goal is linked to: an asset XOR a group. */
private sealed interface LinkTarget {
    data class Asset(val id: String) : LinkTarget

    data class Group(val id: String) : LinkTarget
}

private fun SavingsGoalEntity.currentLink(): LinkTarget? =
    linkedAssetId?.let { LinkTarget.Asset(it) } ?: linkedGroupId?.let { LinkTarget.Group(it) }

private fun validateCreate(command: CreateSavingsGoal) {
    if (command.name.isEmpty() || command.name != command.name.trim()) {
        throw InvalidSavingsGoalNameException(command.name)
    }
    if (command.target.amount <= Money.ZERO) {
        throw InvalidSavingsGoalTargetException(command.target.amount.minorUnits)
    }
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
        linkedAssetId = row.goal.linkedAssetId,
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
