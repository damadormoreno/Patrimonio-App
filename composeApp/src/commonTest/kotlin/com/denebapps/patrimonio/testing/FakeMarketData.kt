package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.FundamentalsSource
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate

/**
 * Scriptable [PriceHistorySource]: [series] holds the full history of each ref (answered from `from` on, like the real
 * sources), [failures] makes a ref throw, and every call is recorded in [calls]. With a [latencyMs] it also measures
 * how many requests were in flight at once.
 */
class FakePriceHistorySource(private val latencyMs: Long = 0) : PriceHistorySource {
    val series = mutableMapOf<InstrumentRef, List<PricePoint>>()
    val failures = mutableMapOf<InstrumentRef, Throwable>()
    val calls = mutableListOf<Pair<InstrumentRef, LocalDate?>>()
    var maxInFlight = 0
        private set
    private var inFlight = 0

    override suspend fun priceHistory(ref: InstrumentRef, from: LocalDate?): List<PricePoint> {
        calls += ref to from
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            if (latencyMs > 0) delay(latencyMs)
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
