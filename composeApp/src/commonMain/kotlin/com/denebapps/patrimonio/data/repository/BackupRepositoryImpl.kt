package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.backup.BackupCodec
import com.denebapps.patrimonio.data.backup.BackupDocument
import com.denebapps.patrimonio.data.backup.toBackup
import com.denebapps.patrimonio.data.backup.toEntity
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.clearFinancialTables
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.repository.BackupRepository
import kotlinx.datetime.Clock

/**
 * Export reads every table inside one transaction so the snapshot is consistent (a write
 * transaction rather than a reader one: the data set is tiny and it reuses the proven
 * [writeTransaction] path). Import decodes and validates the whole file first, then clears and
 * re-inserts parents before children in a single transaction, preserving ids (savings-goal ids
 * included, so ledgers and links keep pointing at the right goal). The current month's net-worth
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
            BackupDocument(
                exportedAt = clock.now().toString(),
                assets = appDatabase.assetDao().list().map { it.toBackup() },
                liabilities = appDatabase.liabilityDao().list().map { it.toBackup() },
                accountGroups = appDatabase.accountGroupDao().listGroups().map { it.toBackup() },
                accountGroupMembers = appDatabase.accountGroupDao().listMembers().map { it.toBackup() },
                netWorthSnapshots = appDatabase.netWorthDao().list().map { it.toBackup() },
                savingsGoals = goals.listAllGoals().map { it.toBackup() },
                savingsGoalAllocationEvents = goals.listAllAllocationEvents().map { it.toBackup() },
                savingsGoalLinkEvents = goals.listAllLinkEvents().map { it.toBackup() },
            )
        }
        return BackupCodec.encode(document)
    }

    override suspend fun importJson(json: String) {
        val document = BackupCodec.decode(json)
        seedingGate.await()
        appDatabase.writeTransaction {
            appDatabase.clearFinancialTables()
            document.assets.forEach { appDatabase.assetDao().insert(it.toEntity()) }
            document.liabilities.forEach { appDatabase.liabilityDao().insert(it.toEntity()) }
            document.accountGroups.forEach { appDatabase.accountGroupDao().insertGroup(it.toEntity()) }
            document.accountGroupMembers.forEach { appDatabase.accountGroupDao().insertMember(it.toEntity()) }
            document.netWorthSnapshots.forEach { appDatabase.netWorthDao().upsert(it.toEntity()) }
            val goals = appDatabase.savingsGoalDao()
            document.savingsGoals.forEach { goals.insertGoal(it.toEntity()) }
            document.savingsGoalAllocationEvents.forEach { goals.insertAllocationEvent(it.toEntity()) }
            document.savingsGoalLinkEvents.forEach { goals.insertLinkEvent(it.toEntity()) }
            snapshotUpserter.refreshCurrentMonth()
        }
    }
}
