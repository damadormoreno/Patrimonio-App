package com.denebapps.patrimonio.data.db

/**
 * Deletes every financial row in FK-safe child→parent order:
 *
 * 1. Savings-goal ledgers/links, then goals (children of goals: CASCADE; goals reference assets).
 * 2. Group members, then groups (members reference both groups and assets).
 * 3. Subscriptions (they reference the asset that pays them).
 * 4. Net-worth snapshots, assets, liabilities (no outbound FKs once the above are gone).
 *
 * `fx_rates` (reference cache, re-fetchable) is deliberately untouched. Callers MUST run this
 * inside [writeTransaction] so a failure leaves the previous data intact.
 */
internal suspend fun AppDatabase.clearFinancialTables() {
    savingsGoalDao().deleteAllAllocationEvents()
    savingsGoalDao().deleteAllLinkEvents()
    savingsGoalDao().deleteAllGoals()
    accountGroupDao().deleteAllMembers()
    accountGroupDao().deleteAllGroups()
    subscriptionDao().deleteAll()
    netWorthDao().deleteAll()
    assetDao().deleteAll()
    liabilityDao().deleteAll()
}
