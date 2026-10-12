package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.entity.AllocationTargetEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.calc.validateTargets
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.AllocationTargetRepository
import com.denebapps.patrimonio.domain.repository.InvalidAllocationTargetsException
import com.denebapps.patrimonio.domain.repository.InvalidRebalanceToleranceException
import com.denebapps.patrimonio.domain.repository.MAX_REBALANCE_TOLERANCE_BP
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [AllocationTargetRepository] over Room. The inputs are checked before the database is touched, and a write is one
 * transaction (the old targets go, the new ones and the tolerance are stored), so a failure leaves the previous ones.
 * The tolerance lives in the `portfolios` row, the targets in `allocation_targets`; the foreign key deletes a
 * portfolio's targets with it.
 */
class AllocationTargetRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val seedingGate: SeedingGate,
) : AllocationTargetRepository {
    private val portfolioDao = appDatabase.portfolioDao()
    private val targetDao = appDatabase.allocationTargetDao()

    override fun observeTargets(portfolioId: String): Flow<AllocationTargets?> =
        observeAllTargets().map { it[portfolioId] }.distinctUntilChanged()

    override fun observeAllTargets(): Flow<Map<String, AllocationTargets>> = flow {
        seedingGate.await()
        emitAll(
            combine(portfolioDao.observeAll(), targetDao.observeAll()) { portfolios, rows ->
                val byPortfolio = rows.groupBy { it.portfolioId }
                portfolios.mapNotNull { portfolio ->
                    val targets = byPortfolio[portfolio.id]?.let(::toTargetsBp).orEmpty()
                    targets.takeIf { it.isNotEmpty() }
                        ?.let { AllocationTargets(portfolio.id, it, portfolio.rebalanceToleranceBp) }
                }.associateBy { it.portfolioId }
            }.distinctUntilChanged(),
        )
    }

    override suspend fun setTargets(portfolioId: String, targetsBp: Map<AssetClass, Int>, toleranceBp: Int) {
        if (toleranceBp !in 0..MAX_REBALANCE_TOLERANCE_BP) throw InvalidRebalanceToleranceException(toleranceBp)
        validateTargets(targetsBp)?.let { throw InvalidAllocationTargetsException(it) }
        val rows = targetsBp.filterValues { it > 0 }.map { (assetClass, bp) ->
            AllocationTargetEntity(portfolioId, assetClass.name, bp)
        }
        seedingGate.await()
        appDatabase.writeTransaction {
            if (portfolioDao.find(portfolioId) == null) throw PortfolioNotFoundException(portfolioId)
            targetDao.deleteFor(portfolioId)
            targetDao.insertAll(rows)
            portfolioDao.updateTolerance(portfolioId, toleranceBp)
        }
    }

    override suspend fun clearTargets(portfolioId: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            if (portfolioDao.find(portfolioId) == null) throw PortfolioNotFoundException(portfolioId)
            targetDao.deleteFor(portfolioId)
        }
    }
}

/** The rows of one portfolio as a map in [AssetClass] order; a class name this version does not know is skipped. */
private fun toTargetsBp(rows: List<AllocationTargetEntity>): Map<AssetClass, Int> {
    val byName = rows.associate { it.assetClass to it.targetBp }
    return AssetClass.entries.mapNotNull { assetClass -> byName[assetClass.name]?.let { assetClass to it } }.toMap()
}
