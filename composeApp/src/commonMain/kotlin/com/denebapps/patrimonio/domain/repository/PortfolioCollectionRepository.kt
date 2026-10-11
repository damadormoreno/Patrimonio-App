package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Portfolio
import kotlinx.coroutines.flow.Flow

/**
 * The user's portfolios and which holding is in which. Kept apart from [PortfolioRepository], which is about the
 * holdings themselves (their values, prices and fundamentals): a screen that only manages portfolios, or a test that
 * only needs holdings, depends on just the half it uses.
 *
 * There is always at least one portfolio: a fresh install and "clear all data" both leave an empty "Mi cartera", and
 * the last one cannot be deleted. A holding is in exactly one portfolio and none of these operations changes a value,
 * so none of them touches the net-worth snapshot.
 */
interface PortfolioCollectionRepository {
    /** Every portfolio ordered by [Portfolio.sortOrder], then by name ignoring case; re-emits when any changes. */
    fun observePortfolios(): Flow<List<Portfolio>>

    /**
     * Creates a portfolio after all the others; the name is trimmed.
     *
     * @throws com.denebapps.patrimonio.domain.model.InvalidPortfolioNameException when the name is blank or too long.
     * @throws DuplicatePortfolioNameException when another portfolio has the name, ignoring case.
     */
    suspend fun createPortfolio(name: String): Portfolio

    /**
     * Renames a portfolio; it may change the casing of its own name.
     *
     * @throws PortfolioNotFoundException when there is no such portfolio.
     * @throws com.denebapps.patrimonio.domain.model.InvalidPortfolioNameException when the name is blank or too long.
     * @throws DuplicatePortfolioNameException when another portfolio has the name, ignoring case.
     */
    suspend fun renamePortfolio(id: String, name: String)

    /**
     * Deletes a portfolio. When it has holdings they are first moved to [moveHoldingsTo], in the same transaction, so
     * a holding is never deleted with its portfolio. [moveHoldingsTo] is ignored for an empty portfolio.
     *
     * @throws PortfolioNotFoundException when [id], or [moveHoldingsTo] if it is needed, does not exist.
     * @throws LastPortfolioException when it is the only portfolio.
     * @throws PortfolioNotEmptyException when it has holdings and [moveHoldingsTo] is `null`.
     * @throws IllegalArgumentException when [moveHoldingsTo] is the portfolio being deleted.
     */
    suspend fun deletePortfolio(id: String, moveHoldingsTo: String?)

    /**
     * Moves a holding to another portfolio, leaving everything else about it as it is.
     *
     * @throws PortfolioHoldingNotFoundException when there is no such holding.
     * @throws PortfolioNotFoundException when there is no such portfolio.
     */
    suspend fun moveHolding(holdingId: String, portfolioId: String)
}

class PortfolioNotFoundException(portfolioId: String) :
    NoSuchElementException("Portfolio '$portfolioId' was not found")

/** Another portfolio already has [name], ignoring case; the message is meant to be shown to the user. */
class DuplicatePortfolioNameException(val name: String) :
    IllegalArgumentException("Ya existe una cartera con el nombre «$name».")

/** The only portfolio cannot be deleted: there is always at least one. */
class LastPortfolioException :
    IllegalStateException("La última cartera no se puede eliminar.")

/** A portfolio with [holdingCount] holdings cannot be deleted without saying where they go. */
class PortfolioNotEmptyException(portfolioId: String, val holdingCount: Int) :
    IllegalStateException("Portfolio '$portfolioId' has $holdingCount holdings; say where they go before deleting it")
