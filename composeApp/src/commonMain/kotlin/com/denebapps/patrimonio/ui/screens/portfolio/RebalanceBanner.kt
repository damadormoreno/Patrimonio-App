package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.RebalanceStatus
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.ui.components.formatEur
import kotlin.math.abs

internal const val REBALANCE_BANNER_TITLE = "Tu cartera se ha desviado del objetivo"

/**
 * The notice under the total of Cartera: a portfolio drifted out of its tolerance band. For one portfolio the [title]
 * says so, [detail] names its worst drift ("Renta variable +10 pts") and [suggestion] the money that fixes it without
 * selling (null when none would). For all of them together the [title] lists the portfolios that need it and there is
 * no detail. Tapping opens the detail of [portfolioId], the first of them.
 */
data class RebalanceBannerUi(
    val title: String,
    val detail: String?,
    val suggestion: String?,
    val portfolioId: String,
)

/**
 * The banner for the scope: [scopePortfolioId] is the portfolio shown, or `null` for all of [portfolios]. Computed from
 * the whole portfolios, never from the filtered list. `null` when nothing needs rebalancing, including a portfolio
 * without targets.
 */
internal fun rebalanceBanner(
    portfolios: List<Portfolio>,
    holdings: List<PortfolioHolding>,
    rates: FxRates,
    targets: Map<String, AllocationTargets>,
    scopePortfolioId: String?,
): RebalanceBannerUi? {
    fun needing(portfolio: Portfolio): RebalanceStatus? = targets[portfolio.id]
        ?.let { rebalanceStatusFor(holdings.inPortfolio(portfolio.id), rates, it) }
        ?.takeIf { it.needsRebalancing }

    if (scopePortfolioId != null) {
        val portfolio = portfolios.firstOrNull { it.id == scopePortfolioId } ?: return null
        return needing(portfolio)?.let { singleBanner(portfolio.id, it) }
    }
    val needy = portfolios.filter { needing(it) != null }
    val first = needy.firstOrNull() ?: return null
    val verb = if (needy.size == 1) "Necesita" else "Necesitan"
    return RebalanceBannerUi(
        title = "$verb rebalanceo: ${namesList(needy.map { it.name })}",
        detail = null,
        suggestion = null,
        portfolioId = first.id,
    )
}

private fun singleBanner(portfolioId: String, status: RebalanceStatus): RebalanceBannerUi {
    // The class that is furthest from its target; of equals, the first in class order.
    val worst = status.classes.filter { it.outOfBand }.maxByOrNull { abs(it.driftBp) }
    return RebalanceBannerUi(
        title = REBALANCE_BANNER_TITLE,
        detail = worst?.let { "${assetClassLabel(it.assetClass)} ${formatPoints(it.driftBp)}" },
        suggestion = status.contribution.takeIf { it.minorUnits > 0 }
            ?.let { "Aporta ${formatEur(it)} para volver al objetivo sin vender" },
        portfolioId = portfolioId,
    )
}
