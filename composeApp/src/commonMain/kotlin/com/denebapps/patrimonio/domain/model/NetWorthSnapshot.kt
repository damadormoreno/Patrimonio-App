package com.denebapps.patrimonio.domain.model

/** A point-in-time snapshot of total assets/liabilities (EUR) for a given month. Past months are
 *  immutable; only the current month's row is ever recomputed. */
data class NetWorthSnapshot(
    val yearMonth: YearMonth,
    val assets: Money,
    val liabilities: Money,
) {
    val netWorth: Money get() = assets - liabilities
}
