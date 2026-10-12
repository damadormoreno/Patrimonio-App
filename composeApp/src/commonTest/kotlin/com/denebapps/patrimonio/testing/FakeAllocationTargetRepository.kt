package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.calc.validateTargets
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.AllocationTargetRepository
import com.denebapps.patrimonio.domain.repository.InvalidAllocationTargetsException
import com.denebapps.patrimonio.domain.repository.InvalidRebalanceToleranceException
import com.denebapps.patrimonio.domain.repository.MAX_REBALANCE_TOLERANCE_BP
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * `MutableStateFlow`-backed [AllocationTargetRepository] with the rules of the real one (validation, nothing written on
 * error, zero targets not stored). The targets of a portfolio that is no longer in [portfolios] are not observed, like
 * the foreign key that deletes them; [portfolios] also decides which portfolios exist. Seed it with [seed] to give a
 * portfolio targets without going through the rules.
 */
class FakeAllocationTargetRepository(
    private val portfolios: FakePortfolioCollectionRepository = FakePortfolioCollectionRepository(),
) : AllocationTargetRepository {
    private val backing = MutableStateFlow<Map<String, AllocationTargets>>(emptyMap())

    /** Every call to [setTargets] that was accepted, in order. */
    val setCalls = mutableListOf<AllocationTargets>()

    /** Every portfolio id [clearTargets] was accepted for, in order. */
    val clearCalls = mutableListOf<String>()

    /** Gives [portfolioId] these targets, as if they had been set before. */
    fun seed(portfolioId: String, targetsBp: Map<AssetClass, Int>, toleranceBp: Int = 500) {
        backing.value = backing.value + (portfolioId to AllocationTargets(portfolioId, targetsBp, toleranceBp))
    }

    override fun observeTargets(portfolioId: String): Flow<AllocationTargets?> =
        observeAllTargets().map { it[portfolioId] }.distinctUntilChanged()

    override fun observeAllTargets(): Flow<Map<String, AllocationTargets>> =
        combine(backing, portfolios.observePortfolios()) { targets, existing ->
            val ids = existing.map { it.id }.toSet()
            targets.filterKeys { it in ids }
        }

    override suspend fun setTargets(portfolioId: String, targetsBp: Map<AssetClass, Int>, toleranceBp: Int) {
        if (toleranceBp !in 0..MAX_REBALANCE_TOLERANCE_BP) throw InvalidRebalanceToleranceException(toleranceBp)
        validateTargets(targetsBp)?.let { throw InvalidAllocationTargetsException(it) }
        requireExists(portfolioId)
        val targets = AllocationTargets(portfolioId, targetsBp.filterValues { it > 0 }, toleranceBp)
        backing.value = backing.value + (portfolioId to targets)
        setCalls += targets
    }

    override suspend fun clearTargets(portfolioId: String) {
        requireExists(portfolioId)
        backing.value = backing.value - portfolioId
        clearCalls += portfolioId
    }

    private fun requireExists(portfolioId: String) {
        if (portfolios.current.none { it.id == portfolioId }) throw PortfolioNotFoundException(portfolioId)
    }
}
