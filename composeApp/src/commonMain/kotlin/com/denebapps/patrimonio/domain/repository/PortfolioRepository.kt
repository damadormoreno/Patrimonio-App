package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Units
import kotlinx.coroutines.flow.Flow

/** The user's investment holdings and their latest prices. */
interface PortfolioRepository {
    /** Every holding, ordered by name; re-emits when a holding or its price changes. */
    fun observeAll(): Flow<List<PortfolioHolding>>

    /** Stores a new holding; its id must not exist yet. */
    suspend fun add(holding: PortfolioHolding)

    /**
     * Sets how many units a quoted holding has.
     *
     * @throws PortfolioHoldingNotFoundException when there is no such holding.
     * @throws IllegalStateException when the holding is a manual one.
     */
    suspend fun updateUnits(id: String, units: Units)

    /**
     * Sets the value of a manual holding, stamped with the current time.
     *
     * @throws PortfolioHoldingNotFoundException when there is no such holding.
     * @throws IllegalStateException when the holding is a quoted one.
     */
    suspend fun updateManualValue(id: String, value: CurrencyAmount)

    /**
     * Sets the bank or broker of a holding, quoted or manual; `null` or blank clears it. The text is trimmed and
     * stored as typed otherwise.
     *
     * @throws PortfolioHoldingNotFoundException when there is no such holding.
     * @throws IllegalArgumentException when the trimmed text is longer than `MAX_BROKER_LENGTH`.
     */
    suspend fun updateBroker(id: String, broker: String?)

    /** Deletes a holding.
     *
     * @throws PortfolioHoldingNotFoundException when there is no such holding.
     */
    suspend fun delete(id: String)

    /**
     * Fetches the latest price of every quoted holding whose price is older than six hours (or was never fetched);
     * all of them when [force]. A holding whose fetch fails keeps its previous price and is reported in the result;
     * one failure never stops the others. Manual holdings are never fetched.
     */
    suspend fun refreshPrices(force: Boolean): PriceRefreshResult

    /**
     * Fills in or updates the fundamentals (cost, risk, category, manager) of quoted holdings that have an ISIN, from
     * the [FundamentalsSource]: the holding [holdingId], or every eligible one when it is `null`. A holding whose
     * fundamentals were fetched less than a day ago is left alone unless [force]. A failure keeps what is stored and
     * is reported in the result; a source that knows nothing about the product also keeps it, and counts as asked.
     *
     * @throws PortfolioHoldingNotFoundException when [holdingId] is given and there is no such holding.
     */
    suspend fun syncFundamentals(holdingId: String?, force: Boolean): FundamentalsSyncResult
}

/**
 * Outcome of [PortfolioRepository.syncFundamentals]: how many holdings got [updated] fundamentals, how many were left
 * alone because they were asked within a day ([upToDate]), and the [failures]. Holdings that are not eligible (manual,
 * or without an ISIN) are not counted.
 */
data class FundamentalsSyncResult(
    val updated: Int,
    val upToDate: Int,
    val failures: List<FundamentalsSyncFailure>,
)

/** A holding whose fundamentals could not be fetched; [reason] is a short technical description for logs. */
data class FundamentalsSyncFailure(val holdingId: String, val holdingName: String, val reason: String)

/**
 * Outcome of [PortfolioRepository.refreshPrices]: how many prices were [refreshed], how many quoted holdings were
 * left alone because their price was still fresh ([upToDate]), and the [failures].
 */
data class PriceRefreshResult(
    val refreshed: Int,
    val upToDate: Int,
    val failures: List<PriceRefreshFailure>,
)

/** A holding whose price could not be refreshed; [reason] is a short technical description for logs. */
data class PriceRefreshFailure(val holdingId: String, val holdingName: String, val reason: String)

class PortfolioHoldingNotFoundException(holdingId: String) :
    NoSuchElementException("Portfolio holding '$holdingId' was not found")
