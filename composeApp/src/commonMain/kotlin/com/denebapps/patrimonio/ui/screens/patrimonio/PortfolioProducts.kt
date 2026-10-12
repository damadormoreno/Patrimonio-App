package com.denebapps.patrimonio.ui.screens.patrimonio

import com.denebapps.patrimonio.domain.calc.AccountAssignment
import com.denebapps.patrimonio.domain.calc.AccountFilter
import com.denebapps.patrimonio.domain.calc.HoldingUsage
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.PortfolioHolding

/** A position that sold everything: it stays listed, worth nothing, after the open ones. */
internal val PortfolioHolding.isClosedPosition: Boolean
    get() = (valuation as? HoldingValuation.Quoted)?.isClosed == true

/** [this] with the closed positions after the open ones, each keeping its order (as the Cartera list does). */
internal fun List<PortfolioHolding>.closedLast(): List<PortfolioHolding> = sortedBy { it.isClosedPosition }

/**
 * Whether a portfolio product passes the account filter's assignment. Products have no account type, so a type chip
 * leaves them all out; [usage] is its entry in `holdingUsage`, `null` when it is in no group.
 */
internal fun AccountFilter.matchesProduct(usage: HoldingUsage?): Boolean {
    if (types.isNotEmpty()) return false
    val inGoal = usage?.goalNames.orEmpty().isNotEmpty()
    val inGroup = usage?.groupNames.orEmpty().isNotEmpty()
    return when (assignment) {
        AccountAssignment.ALL -> true
        AccountAssignment.IN_GOALS -> inGoal
        AccountAssignment.IN_GROUPS -> inGroup
        AccountAssignment.UNASSIGNED -> !inGoal && !inGroup
    }
}

/** "1 producto", "5 productos", or "2 de 5 productos" while a filter hides some. */
fun productCountLabel(shown: Int, total: Int): String = when {
    shown < total -> "$shown de $total productos"
    total == 1 -> "1 producto"
    else -> "$total productos"
}
