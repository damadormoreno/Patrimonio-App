package com.denebapps.patrimonio.data.db

/**
 * Deletes every financial row in FK-safe child→parent order and leaves the database in its first-run state: no data
 * and exactly one empty portfolio, "Mi cartera" (there is always at least one). See [deleteFinancialRows] for the order.
 * Callers MUST run this inside [writeTransaction] so a failure leaves the previous data intact.
 */
internal suspend fun AppDatabase.clearFinancialTables() {
    deleteFinancialRows()
    ensureDefaultPortfolio()
}

/**
 * Deletes every financial row in FK-safe child→parent order, portfolios included, so a caller that is about to
 * re-insert a whole data set (the backup import) can start from nothing:
 *
 * 1. Savings-goal ledgers, link history and the accounts, products and groups they follow, then goals (those links
 *    reference assets, holdings and groups).
 * 2. Group members, the holdings' memberships, then groups (memberships reference groups, and assets or holdings).
 * 3. Net-worth snapshots, assets, liabilities (no outbound FKs once the above are gone), and the
 *    custom account types they used.
 * 4. Movements, the allocation targets, then portfolio holdings (movements follow their holding), then the
 *    portfolios they reference (holdings restrict their portfolio's deletion), and the price history cached for their
 *    instruments: it is useless without them and downloads again when a holding is added.
 *
 * `fx_rates` and `fx_history` (reference caches, re-fetchable, tied to no holding) are deliberately untouched. Callers
 * MUST run this inside [writeTransaction] so a failure leaves the previous data intact.
 */
internal suspend fun AppDatabase.deleteFinancialRows() {
    savingsGoalDao().deleteAllAllocationEvents()
    savingsGoalDao().deleteAllLinkEvents()
    savingsGoalDao().deleteAllLinkedAssets()
    savingsGoalDao().deleteAllLinkedHoldings()
    savingsGoalDao().deleteAllLinkedGroups()
    savingsGoalDao().deleteAllGoals()
    accountGroupDao().deleteAllMembers()
    accountGroupDao().deleteAllHoldingMembers()
    accountGroupDao().deleteAllGroups()
    netWorthDao().deleteAll()
    assetDao().deleteAll()
    liabilityDao().deleteAll()
    accountTypeDao().deleteAll()
    movementDao().deleteAll()
    allocationTargetDao().deleteAll()
    portfolioHoldingDao().deleteAll()
    portfolioDao().deleteAll()
    instrumentPriceDao().deleteAllPrices()
    instrumentPriceDao().deleteAllSyncs()
}
