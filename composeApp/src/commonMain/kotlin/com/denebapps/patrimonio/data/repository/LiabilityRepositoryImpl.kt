package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.LiabilityDao
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Every write runs INSIDE the same DB transaction as [NetWorthSnapshotUpserter.refreshCurrentMonth]
 *  (Decision #7) — a failed snapshot recompute rolls back the mutation too. */
class LiabilityRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val liabilityDao: LiabilityDao,
    private val snapshotUpserter: NetWorthSnapshotUpserter,
    private val seedingGate: SeedingGate,
) : LiabilityRepository {
    override fun observeAll(): Flow<List<Liability>> = flow {
        seedingGate.await()
        emitAll(liabilityDao.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override suspend fun list(): List<Liability> {
        seedingGate.await()
        return liabilityDao.list().map(::toDomain)
    }

    override suspend fun insert(liability: Liability) {
        seedingGate.await()
        appDatabase.writeTransaction {
            liabilityDao.insert(toEntity(liability))
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun update(liability: Liability) {
        seedingGate.await()
        appDatabase.writeTransaction {
            liabilityDao.update(toEntity(liability))
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun deleteById(id: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            liabilityDao.deleteById(id)
            snapshotUpserter.refreshCurrentMonth()
        }
    }
}

private fun toDomain(entity: LiabilityEntity): Liability = Liability(
    id = entity.id,
    group = entity.group,
    name = entity.name,
    subtitle = entity.subtitle,
    amount = CurrencyAmount(Money(entity.amountMinor), Currency.valueOf(entity.currency)),
    emoji = entity.emoji,
)

private fun toEntity(liability: Liability): LiabilityEntity = LiabilityEntity(
    id = liability.id,
    group = liability.group,
    name = liability.name,
    subtitle = liability.subtitle,
    amountMinor = liability.amount.amount.minorUnits,
    currency = liability.amount.currency.code,
    emoji = liability.emoji,
)
