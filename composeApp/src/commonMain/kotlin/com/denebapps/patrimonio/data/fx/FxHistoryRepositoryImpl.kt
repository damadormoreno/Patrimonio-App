package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.db.dao.FxHistoryDao
import com.denebapps.patrimonio.data.db.entity.FxHistoryEntity
import com.denebapps.patrimonio.data.db.entity.FxHistorySyncEntity
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException

/** A currency synced less than this long ago is left alone by a non-forced sync. */
private const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

/** An incremental download restarts this many days before the last stored day, to pick up late revisions. */
private const val OVERLAP_DAYS = 7

/**
 * [FxHistoryRepository] over Room and Frankfurter's time series. Each non-EUR currency is downloaded on its own, so the
 * windows can differ (a currency added later needs its whole history, the others only the last days) and one failing
 * currency leaves the rest stored. A currency's [FxHistorySyncEntity] remembers how far back it was asked to reach, so
 * a later request for older dates downloads the missing years instead of being taken for fresh.
 */
class FxHistoryRepositoryImpl(
    private val dao: FxHistoryDao,
    private val api: FrankfurterApi,
    private val clock: Clock,
) : FxHistoryRepository {
    override fun observe(): Flow<FxHistory> = dao.observeAll().map(::toDomain)

    override suspend fun sync(currencies: Set<Currency>, from: LocalDate, force: Boolean) {
        val failures = mutableListOf<HistoryUnavailableException>()
        currencies.filter { it != Currency.EUR }.sortedBy { it.ordinal }.forEach { currency ->
            try {
                sync(currency, from, force)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: HistoryUnavailableException) {
                AppLogger.error("FxHistory", "rates of ${currency.code} failed: ${failure.message}", failure.cause)
                failures += failure
            }
        }
        failures.firstOrNull()?.let { throw it }
    }

    private suspend fun sync(currency: Currency, from: LocalDate, force: Boolean) {
        val now = clock.now()
        val nowMs = now.toEpochMilliseconds()
        val today = now.toLocalDateTime(TimeZone.UTC).date
        val wanted = minOf(from, today)
        val state = dao.findSync(currency.code)
        val needsOlder = state == null || wanted.toEpochDays().toLong() < state.coveredFromEpochDay
        if (!needsOlder && !force && state != null && isFresh(state, nowMs)) return
        val lastDay = dao.lastEpochDay(currency.code)
        val start = if (needsOlder || lastDay == null) wanted else incrementalStart(lastDay)
        val rates = try {
            scaleSeries(api.timeSeries(Currency.EUR.code, listOf(currency.code), start, today).rates, listOf(currency))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw HistoryUnavailableException("No exchange rate history for ${currency.code}", failure)
        }
        dao.upsertAll(rates.getValue(currency).map { toEntity(currency, it) })
        val wantedDay = wanted.toEpochDays().toLong()
        val covered = state?.let { minOf(it.coveredFromEpochDay, wantedDay) } ?: wantedDay
        dao.upsertSync(FxHistorySyncEntity(currency.code, covered, nowMs))
    }
}

private fun isFresh(state: FxHistorySyncEntity, now: Long): Boolean {
    val age = now - state.lastSyncedAtEpochMs
    return age in 0 until FRESH_WINDOW_MS
}

private fun incrementalStart(lastEpochDay: Long): LocalDate =
    LocalDate.fromEpochDays(lastEpochDay.toInt()).minus(OVERLAP_DAYS, DateTimeUnit.DAY)

private fun toEntity(currency: Currency, rate: DatedFxRate) =
    FxHistoryEntity(currency.code, rate.date.toEpochDays().toLong(), rate.rateToEurScaled)

private fun toDomain(rows: List<FxHistoryEntity>): FxHistory = FxHistory(
    rows.groupBy(
        keySelector = { Currency.valueOf(it.currency) },
        valueTransform = { DatedFxRate(LocalDate.fromEpochDays(it.epochDay.toInt()), it.rateToEurScaled) },
    ),
)
