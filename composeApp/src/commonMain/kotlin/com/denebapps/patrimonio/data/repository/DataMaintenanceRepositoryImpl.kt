package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.clearFinancialTables
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository

/**
 * Runs the clear-all command as ONE Room write transaction (see [clearFinancialTables] for the
 * FK-safe order). `fx_rates` and every DataStore preference are deliberately untouched — this
 * class never sees a DataStore handle. Room's transaction-scoped invalidation re-emits every
 * active collector exactly once with the cleared state.
 */
class DataMaintenanceRepositoryImpl(
    private val appDatabase: AppDatabase,
) : DataMaintenanceRepository {
    override suspend fun clearAllFinancialData() {
        appDatabase.writeTransaction { appDatabase.clearFinancialTables() }
    }
}
