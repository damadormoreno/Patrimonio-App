package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.coroutines.flow.Flow

/**
 * The price history of the products in the portfolio: a cache on the device that is downloaded once and refreshed
 * incrementally. It is NOT user data — it is never part of a backup and can always be downloaded again — so it is
 * wiped together with the holdings and pruned when no holding uses a series any more.
 */
interface InstrumentHistoryRepository {
    /** The stored history of [ref], ascending by date; empty until the first sync, and re-emits after each one. */
    fun observe(ref: InstrumentRef): Flow<List<PricePoint>>

    /**
     * Brings the history of [ref] up to date: a full download when it was never synced, otherwise the days since a
     * week before the last stored one. Does nothing when the series was synced less than six hours ago, unless [force].
     *
     * @throws HistoryUnavailableException when the download fails; what is cached stays as it was.
     */
    suspend fun sync(ref: InstrumentRef, force: Boolean)

    /**
     * [sync]s every series in [refs] (the instruments of the holdings), at most four requests at a time, and prunes the
     * series no holding references any more. One failure never stops the others; failures are in the result.
     */
    suspend fun syncAll(refs: Collection<InstrumentRef>, force: Boolean): HistorySyncResult
}

/** Outcome of [InstrumentHistoryRepository.syncAll]: series downloaded, series left alone because fresh, and [failures]. */
data class HistorySyncResult(val synced: Int, val upToDate: Int, val failures: List<HistorySyncFailure>)

/** A series that could not be downloaded; [reason] is a short technical description for logs. */
data class HistorySyncFailure(val ref: InstrumentRef, val reason: String)

/** A history (prices or exchange rates) could not be downloaded: network failure, bad status or unexpected payload. */
class HistoryUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
