package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.backup.BackupCodec
import com.denebapps.patrimonio.data.backup.BackupDocument
import com.denebapps.patrimonio.data.backup.toBackup
import com.denebapps.patrimonio.data.backup.toEntity
import com.denebapps.patrimonio.data.backup.toLinkedAssetEntities
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.deleteFinancialRows
import com.denebapps.patrimonio.data.db.ensureDefaultPortfolio
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.repository.BackupRepository
import kotlinx.datetime.Clock

/**
 * Export reads every table inside one transaction so the snapshot is consistent (a write
 * transaction rather than a reader one: the data set is tiny and it reuses the proven
 * [writeTransaction] path). Import decodes and validates the whole file first, then clears and
 * re-inserts parents before children in a single transaction, preserving ids (savings-goal ids
 * included, so ledgers and links keep pointing at the right goal; portfolios, with their allocation targets, before the
 * holdings in them, and those
 * before their movements, whose units replace the holdings' own; the holdings' memberships of the groups go last,
 * once both exist). Whatever the
 * file holds, the database ends with at least one portfolio. The current month's net-worth
 * snapshot is recomputed from the imported assets, like after any other asset write.
 */
class BackupRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val snapshotUpserter: NetWorthSnapshotUpserter,
    private val seedingGate: SeedingGate,
    private val clock: Clock,
) : BackupRepository {
    override suspend fun exportJson(): String {
        seedingGate.await()
        val document = appDatabase.writeTransaction {
            val goals = appDatabase.savingsGoalDao()
            val linkedAssetIds = goals.listAllLinkedAssets().groupBy({ it.goalId }, { it.assetId })
            BackupDocument(
                exportedAt = clock.now().toString(),
                assets = appDatabase.assetDao().list().map { it.toBackup() },
                liabilities = appDatabase.liabilityDao().list().map { it.toBackup() },
                accountGroups = appDatabase.accountGroupDao().listGroups().map { it.toBackup() },
                accountGroupMembers = appDatabase.accountGroupDao().listMembers().map { it.toBackup() },
                netWorthSnapshots = appDatabase.netWorthDao().list().map { it.toBackup() },
                savingsGoals = goals.listAllGoals().map { it.toBackup(linkedAssetIds[it.id].orEmpty()) },
                savingsGoalAllocationEvents = goals.listAllAllocationEvents().map { it.toBackup() },
                savingsGoalLinkEvents = goals.listAllLinkEvents().map { it.toBackup() },
                accountTypes = appDatabase.accountTypeDao().list().map { it.toBackup() },
                portfolios = appDatabase.portfolioDao().list().map { it.toBackup() },
                portfolioHoldings = appDatabase.portfolioHoldingDao().list().map { it.toBackup() },
                movements = appDatabase.movementDao().list().map { it.toBackup() },
                allocationTargets = appDatabase.allocationTargetDao().list().map { it.toBackup() },
                accountGroupHoldingMembers = appDatabase.accountGroupDao().listHoldingMembers().map { it.toBackup() },
            )
        }
        return BackupCodec.encode(document)
    }

    override suspend fun importJson(json: String) {
        val document = BackupCodec.decode(json)
        seedingGate.await()
        appDatabase.writeTransaction {
            appDatabase.deleteFinancialRows()
            document.accountTypes.forEach { appDatabase.accountTypeDao().insert(it.toEntity()) }
            document.assets.forEach { appDatabase.assetDao().insert(it.toEntity()) }
            document.liabilities.forEach { appDatabase.liabilityDao().insert(it.toEntity()) }
            document.accountGroups.forEach { appDatabase.accountGroupDao().insertGroup(it.toEntity()) }
            document.accountGroupMembers.forEach { appDatabase.accountGroupDao().insertMember(it.toEntity()) }
            document.netWorthSnapshots.forEach { appDatabase.netWorthDao().upsert(it.toEntity()) }
            val goals = appDatabase.savingsGoalDao()
            document.savingsGoals.forEach { goal ->
                goals.insertGoal(goal.toEntity())
                goals.insertLinkedAssets(goal.toLinkedAssetEntities())
            }
            document.savingsGoalAllocationEvents.forEach { goals.insertAllocationEvent(it.toEntity()) }
            document.savingsGoalLinkEvents.forEach { goals.insertLinkEvent(it.toEntity()) }
            document.portfolios.forEach { appDatabase.portfolioDao().insert(it.toEntity()) }
            appDatabase.allocationTargetDao().insertAll(document.allocationTargets.map { it.toEntity() })
            // A holding with movements gets its units from them (the file's value is redundant); others keep theirs.
            val derivedUnits = computeLedgers(document.movements.map { movementToDomain(it.toEntity()) })
                .mapValues { (_, ledger) -> ledger.unitsHeld.scaled }
            document.portfolioHoldings.forEach { holding ->
                val entity = holding.toEntity()
                val units = derivedUnits[holding.id]
                val stored = if (units == null) entity else entity.copy(unitsScaled = units)
                appDatabase.portfolioHoldingDao().insert(stored)
            }
            document.movements.forEach { appDatabase.movementDao().insert(it.toEntity()) }
            // The groups hold holdings too: their memberships can only go in once the holdings exist.
            document.accountGroupHoldingMembers.forEach {
                appDatabase.accountGroupDao().insertHoldingMember(it.toEntity())
            }
            // A file without portfolios (and so without holdings) still leaves the database with its "Mi cartera".
            appDatabase.ensureDefaultPortfolio()
            snapshotUpserter.refreshCurrentMonth()
        }
    }
}
