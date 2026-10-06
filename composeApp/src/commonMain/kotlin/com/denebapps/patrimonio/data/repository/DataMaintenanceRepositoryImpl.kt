package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository

/**
 * Runs the clear-all command as ONE Room write transaction in FK-safe child→parent order:
 *
 * 1. Savings-goal ledgers/links, then goals (children of goals: CASCADE; goals reference assets).
 * 2. Group members, then groups (members reference both groups and assets).
 * 3. Net-worth snapshots, assets, liabilities (no outbound FKs once goals/members are gone).
 *
 * `fx_rates` (reference cache, re-fetchable) and every DataStore preference are deliberately
 * untouched — this class never sees a DataStore handle. Room's transaction-scoped invalidation
 * re-emits every active collector exactly once with the cleared state.
 */
class DataMaintenanceRepositoryImpl(
    private val appDatabase: AppDatabase,
) : DataMaintenanceRepository {
    override suspend fun clearAllFinancialData() {
        appDatabase.writeTransaction {
            appDatabase.savingsGoalDao().deleteAllAllocationEvents()
            appDatabase.savingsGoalDao().deleteAllLinkEvents()
            appDatabase.savingsGoalDao().deleteAllGoals()
            appDatabase.accountGroupDao().deleteAllMembers()
            appDatabase.accountGroupDao().deleteAllGroups()
            appDatabase.netWorthDao().deleteAll()
            appDatabase.assetDao().deleteAll()
            appDatabase.liabilityDao().deleteAll()
        }
    }
}
