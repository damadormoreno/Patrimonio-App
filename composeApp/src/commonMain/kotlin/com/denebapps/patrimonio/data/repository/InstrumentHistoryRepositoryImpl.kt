package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.dao.InstrumentPriceDao
import com.denebapps.patrimonio.data.db.dao.pruneUnreferenced
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceSyncEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.HistorySyncFailure
import com.denebapps.patrimonio.domain.repository.HistorySyncResult
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.coroutines.cancellation.CancellationException

/** A series synced less than this long ago is left alone by a non-forced sync. */
private const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

/** An incremental download restarts this many days before the last stored day, to pick up late revisions. */
private const val OVERLAP_DAYS = 7

/** Downloads in flight at once: enough to be quick, few enough to stay polite to the public endpoints. */
private const val MAX_CONCURRENT_SYNCS = 4

/**
 * [InstrumentHistoryRepository] over Room and the [PriceHistorySource] port.
 *
 * The sync state is its own row per series (`instrument_price_sync`) rather than the newest stored day: the newest day
 * can legitimately be days old (weekends, a delisted product), so it cannot tell when the provider was last asked.
 * No row means "never synced": the next sync downloads everything. A failed download writes nothing, so it is retried
 * on the next call instead of being skipped for six hours.
 */
class InstrumentHistoryRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val dao: InstrumentPriceDao,
    private val source: PriceHistorySource,
    private val clock: Clock,
) : InstrumentHistoryRepository {
    override fun observe(ref: InstrumentRef): Flow<List<PricePoint>> =
        dao.observe(ref.provider.name, ref.code).map { rows -> rows.map(::toPoint) }

    override suspend fun sync(ref: InstrumentRef, force: Boolean) {
        download(ref, force)
    }

    override suspend fun syncAll(refs: Collection<InstrumentRef>, force: Boolean): HistorySyncResult {
        val limiter = Semaphore(MAX_CONCURRENT_SYNCS)
        val outcomes = coroutineScope {
            refs.distinct().map { ref ->
                async { limiter.withPermit { attempt(ref, force) } }
            }.awaitAll()
        }
        appDatabase.writeTransaction { dao.pruneUnreferenced() }
        return HistorySyncResult(
            synced = outcomes.count { it == Outcome.Downloaded },
            upToDate = outcomes.count { it == Outcome.Fresh },
            failures = outcomes.filterIsInstance<Outcome.Failed>().map { it.failure },
        )
    }

    private sealed interface Outcome {
        data object Downloaded : Outcome

        data object Fresh : Outcome

        data class Failed(val failure: HistorySyncFailure) : Outcome
    }

    private suspend fun attempt(ref: InstrumentRef, force: Boolean): Outcome = try {
        if (download(ref, force)) Outcome.Downloaded else Outcome.Fresh
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: HistoryUnavailableException) {
        AppLogger.error("InstrumentHistory", "history of ${ref.code} failed: ${failure.message}", failure.cause)
        Outcome.Failed(HistorySyncFailure(ref, failure.message.orEmpty()))
    }

    /** @return `true` when the provider was asked, `false` when the series was fresh. */
    private suspend fun download(ref: InstrumentRef, force: Boolean): Boolean {
        val provider = ref.provider.name
        val now = clock.now().toEpochMilliseconds()
        val state = dao.findSync(provider, ref.code)
        if (!force && state != null && isFresh(state, now)) return false
        val lastDay = dao.lastEpochDay(provider, ref.code)
        val from = if (state == null || lastDay == null) null else incrementalStart(lastDay)
        val points = try {
            source.priceHistory(ref, from)
        } catch (failure: QuoteUnavailableException) {
            throw HistoryUnavailableException("No price history for ${ref.code}: ${failure.message}", failure)
        }
        appDatabase.writeTransaction {
            dao.upsertAll(points.map { toEntity(ref, it) })
            dao.upsertSync(InstrumentPriceSyncEntity(provider, ref.code, now))
        }
        return true
    }
}

/** Synced less than six hours ago; a clock that went backwards counts as stale. */
private fun isFresh(state: InstrumentPriceSyncEntity, now: Long): Boolean {
    val age = now - state.lastSyncedAtEpochMs
    return age in 0 until FRESH_WINDOW_MS
}

private fun incrementalStart(lastEpochDay: Long): LocalDate =
    LocalDate.fromEpochDays(lastEpochDay.toInt()).minus(OVERLAP_DAYS, DateTimeUnit.DAY)

private fun toEntity(ref: InstrumentRef, point: PricePoint) = InstrumentPriceEntity(
    provider = ref.provider.name,
    code = ref.code,
    epochDay = point.date.toEpochDays().toLong(),
    priceScaled = point.price.scaled,
    currency = point.price.currency.code,
)

private fun toPoint(row: InstrumentPriceEntity) = PricePoint(
    date = LocalDate.fromEpochDays(row.epochDay.toInt()),
    price = Price(row.priceScaled, Currency.valueOf(row.currency)),
)
