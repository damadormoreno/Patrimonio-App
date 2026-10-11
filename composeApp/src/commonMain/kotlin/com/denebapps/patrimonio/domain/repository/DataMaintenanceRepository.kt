package com.denebapps.patrimonio.domain.repository

/**
 * Owns the destructive clear-all-financial-data command. Room-only by construction:
 * the implementation never touches DataStore, so profile/theme preference preservation is
 * structural rather than by care.
 */
interface DataMaintenanceRepository {
    /**
     * Atomically wipes every Room financial table (assets, liabilities, account groups and members,
     * savings goals and their ledgers/links, net-worth snapshots). `fx_rates` and all DataStore
     * preferences are preserved. Room invalidation re-emits every active collector with the
     * cleared state.
     */
    suspend fun clearAllFinancialData()
}
