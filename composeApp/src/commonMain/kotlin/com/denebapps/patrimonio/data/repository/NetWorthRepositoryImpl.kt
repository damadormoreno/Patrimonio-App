package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.NetWorthDao
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class NetWorthRepositoryImpl(
    private val netWorthDao: NetWorthDao,
    private val seedingGate: SeedingGate,
) : NetWorthRepository {
    override fun observeSnapshots(): Flow<List<NetWorthSnapshot>> = flow {
        seedingGate.await()
        emitAll(netWorthDao.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override suspend fun findMostRecentBefore(yearMonth: YearMonth): NetWorthSnapshot? {
        seedingGate.await()
        return netWorthDao.findMostRecentBefore(yearMonth.asKey())?.let(::toDomain)
    }
}

private fun toDomain(entity: NetWorthSnapshotEntity): NetWorthSnapshot = NetWorthSnapshot(
    yearMonth = YearMonth.fromKey(entity.yearMonth),
    assets = Money(entity.assetsMinor),
    liabilities = Money(entity.liabsMinor),
)
