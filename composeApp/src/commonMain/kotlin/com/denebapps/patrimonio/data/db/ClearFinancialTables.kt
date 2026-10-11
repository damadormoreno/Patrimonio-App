package com.denebapps.patrimonio.data.db

/**
 * Deletes every financial row in FK-safe child→parent order:
 *
 * 1. Savings-goal ledgers, link history and linked accounts, then goals (the linked accounts reference
 *    assets; goals reference groups).
 * 2. Group members, then groups (members reference both groups and assets).
 * 3. Net-worth snapshots, assets, liabilities (no outbound FKs once the above are gone), and the
 *    custom account types they used.
 * 4. Portfolio holdings (no FKs either way), and the price history cached for their instruments: it is useless
 *    without them and downloads again when a holding is added.
 *
 * `fx_rates` and `fx_history` (reference caches, re-fetchable, tied to no holding) are deliberately untouched. Callers MUST run this
 * inside [writeTransaction] so a failure leaves the previous data intact.
 */
internal suspend fun AppDatabase.clearFinancialTables() {
    savingsGoalDao().deleteAllAllocationEvents()
    savingsGoalDao().deleteAllLinkEvents()
    savingsGoalDao().deleteAllLinkedAssets()
    savingsGoalDao().deleteAllGoals()
    accountGroupDao().deleteAllMembers()
    accountGroupDao().deleteAllGroups()
    netWorthDao().deleteAll()
    assetDao().deleteAll()
    liabilityDao().deleteAll()
    accountTypeDao().deleteAll()
    portfolioHoldingDao().deleteAll()
    instrumentPriceDao().deleteAllPrices()
    instrumentPriceDao().deleteAllSyncs()
}
