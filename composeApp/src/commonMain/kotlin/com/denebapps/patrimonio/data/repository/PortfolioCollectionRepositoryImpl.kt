package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.data.db.randomPortfolioId
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.normalizePortfolioName
import com.denebapps.patrimonio.domain.repository.DuplicatePortfolioNameException
import com.denebapps.patrimonio.domain.repository.LastPortfolioException
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotEmptyException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [PortfolioCollectionRepository] over Room. Every write is one transaction: deleting a portfolio moves its holdings
 * and removes it together (the foreign key restricts, so a holding can never go with its portfolio), and the checks
 * that depend on the other rows (a unique name ignoring case, the last portfolio) run inside it. Nothing here changes
 * a value, so the month's net-worth snapshot needs no refresh.
 */
class PortfolioCollectionRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val seedingGate: SeedingGate,
    private val newId: () -> String = ::randomPortfolioId,
) : PortfolioCollectionRepository {
    private val portfolioDao = appDatabase.portfolioDao()
    private val holdingDao = appDatabase.portfolioHoldingDao()

    override fun observePortfolios(): Flow<List<Portfolio>> = flow {
        seedingGate.await()
        emitAll(portfolioDao.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override suspend fun createPortfolio(name: String): Portfolio {
        val normalized = normalizePortfolioName(name)
        seedingGate.await()
        return appDatabase.writeTransaction {
            requireNameIsFree(normalized, ignoringId = null)
            val entity = PortfolioEntity(newId(), normalized, portfolioDao.maxSortOrder() + 1)
            portfolioDao.insert(entity)
            toDomain(entity)
        }
    }

    override suspend fun renamePortfolio(id: String, name: String) {
        val normalized = normalizePortfolioName(name)
        seedingGate.await()
        appDatabase.writeTransaction {
            if (portfolioDao.find(id) == null) throw PortfolioNotFoundException(id)
            requireNameIsFree(normalized, ignoringId = id)
            portfolioDao.updateName(id, normalized)
        }
    }

    override suspend fun deletePortfolio(id: String, moveHoldingsTo: String?) {
        seedingGate.await()
        appDatabase.writeTransaction {
            if (portfolioDao.find(id) == null) throw PortfolioNotFoundException(id)
            if (portfolioDao.count() == 1) throw LastPortfolioException()
            val holdingCount = holdingDao.countInPortfolio(id)
            if (holdingCount > 0) {
                val target = moveHoldingsTo ?: throw PortfolioNotEmptyException(id, holdingCount)
                require(target != id) { "Holdings of portfolio '$id' cannot be moved to the portfolio being deleted" }
                if (portfolioDao.find(target) == null) throw PortfolioNotFoundException(target)
                holdingDao.moveAll(from = id, to = target)
            }
            portfolioDao.delete(id)
        }
    }

    override suspend fun moveHolding(holdingId: String, portfolioId: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            if (holdingDao.find(holdingId) == null) throw PortfolioHoldingNotFoundException(holdingId)
            if (portfolioDao.find(portfolioId) == null) throw PortfolioNotFoundException(portfolioId)
            holdingDao.updatePortfolio(holdingId, portfolioId)
        }
    }

    /**
     * Names are unique ignoring case. Compared in Kotlin, not with SQLite's `COLLATE NOCASE`, which only folds ASCII
     * letters and would let "Jubilación" and "JUBILACIÓN" coexist.
     */
    private suspend fun requireNameIsFree(name: String, ignoringId: String?) {
        val taken = portfolioDao.list().any { it.id != ignoringId && it.name.lowercase() == name.lowercase() }
        if (taken) throw DuplicatePortfolioNameException(name)
    }
}

private fun toDomain(row: PortfolioEntity) = Portfolio(id = row.id, name = row.name, sortOrder = row.sortOrder)
