package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.FundamentalsSource
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.HistorySyncResult
import com.denebapps.patrimonio.domain.repository.InstrumentHistoryRepository
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlin.concurrent.Volatile

/**
 * Scriptable [PriceHistorySource]: [series] holds the full history of each ref (answered from `from` on, like the real
 * sources), [failures] makes a ref throw, and every call is recorded in [calls]. With a [gate], every call waits for
 * it to complete, so a test can count the requests in flight at once ([inFlight], [maxInFlight]).
 */
class FakePriceHistorySource(private val gate: CompletableDeferred<Unit>? = null) : PriceHistorySource {
    val series = mutableMapOf<InstrumentRef, List<PricePoint>>()
    val failures = mutableMapOf<InstrumentRef, Throwable>()
    val calls = mutableListOf<Pair<InstrumentRef, LocalDate?>>()

    @Volatile
    var maxInFlight = 0
        private set

    @Volatile
    var inFlight = 0
        private set

    override suspend fun priceHistory(ref: InstrumentRef, from: LocalDate?): List<PricePoint> {
        calls += ref to from
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            gate?.await()
            failures[ref]?.let { throw it }
            val all = series[ref] ?: throw QuoteUnavailableException("No history scripted for $ref")
            return all.filter { from == null || it.date >= from }
        } finally {
            inFlight--
        }
    }
}

/**
 * Scriptable [FundamentalsSource]: [answers] holds what each ISIN yields (a missing key or a `null` value means "the
 * source knows nothing"), [failure] makes every call throw, and every call is recorded in [calls].
 */
class FakeFundamentalsSource : FundamentalsSource {
    val answers = mutableMapOf<String, InstrumentFundamentals?>()
    var failure: Throwable? = null
    val calls = mutableListOf<Pair<String, InstrumentRef?>>()

    override suspend fun fundamentals(isin: String, preferred: InstrumentRef?): InstrumentFundamentals? {
        calls += isin to preferred
        failure?.let { throw it }
        return answers[isin]
    }
}

/**
 * In-memory [InstrumentHistoryRepository] for ViewModel tests: [set] stores the history of a ref (and re-emits it),
 * [sync] records `(ref, force)` in [syncCalls], suspends on [syncGate] when one is set, and throws [syncFailure].
 * [syncAll] records `(refs, force)` in [syncAllCalls], with the same gate and failure, and answers [syncAllResult].
 */
class FakeInstrumentHistoryRepository : InstrumentHistoryRepository {
    private val series = mutableMapOf<InstrumentRef, MutableStateFlow<List<PricePoint>>>()

    val syncCalls = mutableListOf<Pair<InstrumentRef, Boolean>>()
    var syncFailure: Throwable? = null
    var syncGate: CompletableDeferred<Unit>? = null

    val syncAllCalls = mutableListOf<Pair<Set<InstrumentRef>, Boolean>>()
    var syncAllResult = HistorySyncResult(synced = 0, upToDate = 0, failures = emptyList())

    fun set(ref: InstrumentRef, points: List<PricePoint>) {
        flowOf(ref).value = points
    }

    override fun observe(ref: InstrumentRef): Flow<List<PricePoint>> = flowOf(ref)

    override suspend fun sync(ref: InstrumentRef, force: Boolean) {
        syncCalls += ref to force
        syncGate?.await()
        syncFailure?.let { throw it }
    }

    override suspend fun syncAll(refs: Collection<InstrumentRef>, force: Boolean): HistorySyncResult {
        syncAllCalls += refs.toSet() to force
        syncGate?.await()
        syncFailure?.let { throw it }
        return syncAllResult
    }

    private fun flowOf(ref: InstrumentRef) = series.getOrPut(ref) { MutableStateFlow(emptyList()) }
}

/** One recorded [FxHistoryRepository.sync] call. */
data class FxSyncCall(val currencies: Set<Currency>, val from: LocalDate, val force: Boolean)

/**
 * In-memory [FxHistoryRepository] for ViewModel tests: [set] stores the history (and re-emits it), [sync] records its
 * call in [syncCalls], suspends on [syncGate] when one is set, and throws [syncFailure].
 */
class FakeFxHistoryRepository(initial: FxHistory = FxHistory(emptyMap())) : FxHistoryRepository {
    private val backing = MutableStateFlow(initial)

    val syncCalls = mutableListOf<FxSyncCall>()
    var syncFailure: Throwable? = null
    var syncGate: CompletableDeferred<Unit>? = null

    fun set(history: FxHistory) {
        backing.value = history
    }

    override fun observe(): Flow<FxHistory> = backing

    override suspend fun sync(currencies: Set<Currency>, from: LocalDate, force: Boolean) {
        syncCalls += FxSyncCall(currencies, from, force)
        syncGate?.await()
        syncFailure?.let { throw it }
    }
}
