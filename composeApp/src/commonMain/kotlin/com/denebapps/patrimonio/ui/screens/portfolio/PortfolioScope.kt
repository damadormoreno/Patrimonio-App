package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding

/** A portfolio the user can pick: in the selector of Cartera, a form picker or the target of a delete. */
data class PortfolioOptionUi(val id: String, val name: String)

/** Below this many portfolios nothing needs choosing, so the selectors and pickers stay hidden. */
internal const val MIN_PORTFOLIOS_TO_CHOOSE = 2

internal fun List<Portfolio>.toOptions(): List<PortfolioOptionUi> = map { PortfolioOptionUi(it.id, it.name) }

/** The holdings in portfolio [portfolioId]; all of them when it is `null` (the "Todas" scope). */
internal fun List<PortfolioHolding>.inPortfolio(portfolioId: String?): List<PortfolioHolding> =
    if (portfolioId == null) this else filter { it.portfolioId == portfolioId }

/**
 * The portfolio a new holding is added to by default: the one selected in Cartera when it still exists, otherwise the
 * first one. `null` only when there are no portfolios, which the app never has.
 */
internal fun defaultPortfolioId(portfolios: List<Portfolio>, selectedId: String?): String? =
    portfolios.firstOrNull { it.id == selectedId }?.id ?: portfolios.firstOrNull()?.id
