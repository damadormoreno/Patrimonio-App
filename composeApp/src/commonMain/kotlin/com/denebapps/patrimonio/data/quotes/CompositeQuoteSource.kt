package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * [QuoteSource] combining Morningstar and Yahoo Finance, following the merge rules of rumbo
 * (MIT, github.com/danidm98/rumbo, `buscar.py`):
 *
 * - An ISIN is searched on both providers; Morningstar candidates come first. A Yahoo `0P…` symbol whose code
 *   (before the dot) is a Morningstar SecId already found is the same instrument and is dropped, and Yahoo
 *   mutual funds are dropped when Morningstar already offers a fund.
 * - A ticker or a name is searched on Yahoo only.
 * - One provider failing does not fail an ISIN search; both failing does.
 *
 * Candidates are NOT validated by fetching prices — the caller prices the one the user picks.
 */
class CompositeQuoteSource(
    private val morningstar: MorningstarApi,
    private val yahoo: YahooFinanceApi,
) : QuoteSource {
    override suspend fun search(query: String): List<InstrumentCandidate> {
        val text = query.trim()
        return when {
            text.isEmpty() -> emptyList()
            looksLikeIsin(text) -> searchByIsin(text.uppercase())
            else -> yahoo.search(text)
        }
    }

    override suspend fun latestQuote(ref: InstrumentRef): Quote = when (ref.provider) {
        QuoteProvider.MORNINGSTAR -> morningstar.latestNav(ref.code)
        QuoteProvider.YAHOO -> yahoo.latestQuote(ref.code)
    }

    private suspend fun searchByIsin(isin: String): List<InstrumentCandidate> = coroutineScope {
        val fromMorningstar = async { runCatchingUnavailable { morningstar.searchByIsin(isin) } }
        val fromYahoo = async { runCatchingUnavailable { yahoo.search(isin) } }
        val morningstarResult = fromMorningstar.await()
        val yahooResult = fromYahoo.await()
        if (morningstarResult.isFailure && yahooResult.isFailure) {
            throw QuoteUnavailableException(
                "No quote provider answered the search",
                morningstarResult.exceptionOrNull(),
            )
        }
        merge(isin, morningstarResult.getOrDefault(emptyList()), yahooResult.getOrDefault(emptyList()))
    }

    private fun merge(
        isin: String,
        fromMorningstar: List<InstrumentCandidate>,
        fromYahoo: List<InstrumentCandidate>,
    ): List<InstrumentCandidate> {
        val secIds = fromMorningstar.map { it.ref.code }.toSet()
        val morningstarHasFund = fromMorningstar.any { it.type == InstrumentType.FUND }
        val yahooKept = fromYahoo.filterNot { candidate ->
            val isMorningstarTwin = candidate.ref.code.startsWith(MORNINGSTAR_PREFIX) &&
                candidate.ref.code.substringBefore('.') in secIds
            isMorningstarTwin || (candidate.type == InstrumentType.FUND && morningstarHasFund)
        }.map { it.copy(isin = it.isin ?: isin) }
        return (fromMorningstar + yahooKept).distinctBy { it.ref }
    }

    private suspend fun <T> runCatchingUnavailable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (failure: QuoteUnavailableException) {
        Result.failure(failure)
    }

    private companion object {
        const val MORNINGSTAR_PREFIX = "0P"
    }
}
