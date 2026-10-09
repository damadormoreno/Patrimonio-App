package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.FxRateDao
import com.denebapps.patrimonio.data.db.dao.LiabilityDao
import com.denebapps.patrimonio.data.db.dao.NetWorthDao
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Recomputes the current month's net-worth snapshot from the latest cached [FxRates] and upserts
 * it. MUST be called INSIDE the same DB write transaction as the triggering asset/liability
 * mutation (Decision #7) — never a separate post-commit step. Past months are never touched; only
 * the current [YearMonth] row (from the injected [clock]) is ever written.
 */
class NetWorthSnapshotUpserter(
    private val assetDao: AssetDao,
    private val liabilityDao: LiabilityDao,
    private val fxRateDao: FxRateDao,
    private val netWorthDao: NetWorthDao,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
) {
    suspend fun refreshCurrentMonth() {
        val today = clock.todayIn(zoneProvider())
        val ym = YearMonth(today.year, today.monthNumber)
        val rates = FxRates(
            fxRateDao.list().associate { row -> Currency.valueOf(row.currency) to row.rateToEurScaled },
        )
        val assets = assetDao.list().map(::assetToDomain)
        val liabilities = liabilityDao.list().map(::liabilityToDomain)
        val assetsMinor = assets.fold(Money.ZERO) { acc, a -> acc + a.amount.toEur(rates) }
        val liabsMinor = liabilities.fold(Money.ZERO) { acc, l -> acc + l.amount.toEur(rates) }
        netWorthDao.upsert(
            NetWorthSnapshotEntity(
                yearMonth = ym.asKey(),
                assetsMinor = assetsMinor.minorUnits,
                liabsMinor = liabsMinor.minorUnits,
            ),
        )
    }
}

private fun assetToDomain(entity: com.denebapps.patrimonio.data.db.entity.AssetEntity): Asset = Asset(
    id = entity.id,
    group = entity.group,
    name = entity.name,
    subtitle = entity.subtitle,
    amount = CurrencyAmount(Money(entity.amountMinor), Currency.valueOf(entity.currency)),
)

private fun liabilityToDomain(entity: com.denebapps.patrimonio.data.db.entity.LiabilityEntity): Liability = Liability(
    id = entity.id,
    group = entity.group,
    name = entity.name,
    subtitle = entity.subtitle,
    amount = CurrencyAmount(Money(entity.amountMinor), Currency.valueOf(entity.currency)),
)
