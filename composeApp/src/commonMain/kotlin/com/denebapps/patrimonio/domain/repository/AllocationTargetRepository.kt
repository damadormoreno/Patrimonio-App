package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.calc.TOTAL_TARGET_BP
import com.denebapps.patrimonio.domain.calc.TargetsError
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import kotlinx.coroutines.flow.Flow

/** The widest tolerance band accepted, in basis points: 100 percentage points either side. */
const val MAX_REBALANCE_TOLERANCE_BP = TOTAL_TARGET_BP

/**
 * The share of each asset class a portfolio aims for, and how far it may drift from it (the tolerance). Kept apart
 * from [PortfolioCollectionRepository], which manages the portfolios themselves: a screen that only manages portfolios
 * does not need targets. The tolerance is stored with the portfolio (`Portfolio.rebalanceToleranceBp`) but is only
 * ever changed here, together with the targets.
 *
 * Targets are in basis points (10 000 = 100 %), per portfolio and per [AssetClass]; the rules of a valid set are those
 * of `validateTargets`. Deleting a portfolio deletes its targets; nothing here changes a value, so none of it touches
 * the net-worth snapshot.
 */
interface AllocationTargetRepository {
    /**
     * The targets of [portfolioId]; re-emits when they or the tolerance change. `null` while the portfolio has none
     * (also for an unknown portfolio).
     */
    fun observeTargets(portfolioId: String): Flow<AllocationTargets?>

    /** The targets of every portfolio that has some, by portfolio id; re-emits when any of them changes. */
    fun observeAllTargets(): Flow<Map<String, AllocationTargets>>

    /**
     * Replaces the targets and the tolerance of [portfolioId] in one transaction. Classes with a target of 0 are not
     * stored. Nothing is written when anything is invalid.
     *
     * @throws InvalidAllocationTargetsException when [targetsBp] is not valid (see `validateTargets`); an empty map is
     *   one of them: use [clearTargets] to remove the targets.
     * @throws InvalidRebalanceToleranceException when [toleranceBp] is not within 0 to [MAX_REBALANCE_TOLERANCE_BP].
     * @throws PortfolioNotFoundException when there is no such portfolio.
     */
    suspend fun setTargets(portfolioId: String, targetsBp: Map<AssetClass, Int>, toleranceBp: Int)

    /**
     * Removes every target of [portfolioId]; its tolerance is kept, so defining targets again starts from the band
     * the user had. Removing nothing is not an error.
     *
     * @throws PortfolioNotFoundException when there is no such portfolio.
     */
    suspend fun clearTargets(portfolioId: String)
}

/** The targets offered are not valid; [error] says why. */
class InvalidAllocationTargetsException(val error: TargetsError) :
    IllegalArgumentException("Invalid allocation targets: $error")

/** [toleranceBp] is outside 0 to [MAX_REBALANCE_TOLERANCE_BP]. */
class InvalidRebalanceToleranceException(val toleranceBp: Int) :
    IllegalArgumentException("Rebalance tolerance $toleranceBp is outside 0 to $MAX_REBALANCE_TOLERANCE_BP")
