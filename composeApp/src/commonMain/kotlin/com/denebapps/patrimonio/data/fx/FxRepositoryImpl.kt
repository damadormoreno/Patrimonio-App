package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.FxRateDao
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.repository.FxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/** Every currency requested from Frankfurter — EUR is the fixed base and is never returned in the
 *  `rates` map (design.md FX Pipeline). */
private val REQUESTED_CURRENCIES = listOf(Currency.USD, Currency.GBP, Currency.JPY)

/** Rolling window, not a calendar-day boundary (spec: rolling-24h-refresh). */
private const val STALE_WINDOW_MS = 24L * 60 * 60 * 1000

/**
 * [FxRepository] implementation: cached-rate reads + rolling 24h-stale Frankfurter refresh
 * (design.md FX Pipeline / Refresh).
 */
class FxRepositoryImpl(
    private val dao: FxRateDao,
    private val api: FrankfurterApi,
    private val clock: Clock,
    private val seedingGate: SeedingGate,
) : FxRepository {
    override fun observeRates(): Flow<FxRates> = flow {
        seedingGate.await()
        emitAll(dao.observeAll().map(::toDomain))
    }

    override suspend fun refreshIfStale() {
        seedingGate.await()
        val oldestNonEurFetch = dao.list()
            .filter { it.currency != Currency.EUR.code }
            .minOfOrNull { it.fetchedAtEpochMs } ?: 0L
        val now = clock.now().toEpochMilliseconds()
        if (now - oldestNonEurFetch < STALE_WINDOW_MS) return

        runCatching {
            val response = api.latest(base = Currency.EUR.code, symbols = REQUESTED_CURRENCIES.map { it.code })
            val scaled = validateRates(response.rates, REQUESTED_CURRENCIES)
            dao.upsertAll(scaled.map { (currency, rateScaled) -> FxRateEntity(currency.code, rateScaled, now) })
        }.onFailure { error ->
            AppLogger.error("FxRepository", "refreshIfStale failed, keeping cached/seed rates", error)
        }
    }
}

private fun toDomain(rows: List<FxRateEntity>): FxRates =
    FxRates(rows.associate { Currency.valueOf(it.currency) to it.rateToEurScaled })
