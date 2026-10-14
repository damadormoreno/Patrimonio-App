package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate

/**
 * [QuoteSource] combining Morningstar, Yahoo Finance and CoinGecko, following the merge rules of rumbo
 * (MIT, github.com/danidm98/rumbo, `buscar.py`):
 *
 * - An ISIN is searched on both providers; Morningstar candidates come first. A Yahoo `0P…` symbol whose code
 *   (before the dot) is a Morningstar SecId already found is the same instrument and is dropped, and Yahoo
 *   mutual funds are dropped when Morningstar already offers a fund.
 * - A ticker or a name is searched on Yahoo and CoinGecko at once (Yahoo's own crypto results are skipped, CoinGecko is
 *   the crypto source). A coin whose symbol or name equals the query goes first (at most [TOP_COINS] of them) and the
 *   other coins are left out; without such a coin the [TOP_COINS] best ranked ones follow the Yahoo results.
 * - One provider failing does not fail a search; all the providers it asks failing does.
 *
 * Candidates are NOT validated by fetching prices — the caller prices the one the user picks.
 *
 * The same adapters also serve the price history, routed by the instrument's provider, and the fundamentals, which
 * only Morningstar publishes (its screener, by ISIN) whichever provider quotes the holding; a crypto has none.
 */
class CompositeQuoteSource(
    private val morningstar: MorningstarApi,
    private val yahoo: YahooFinanceApi,
    private val coinGecko: CoinGeckoApi,
) : MarketDataSource {
    override suspend fun search(query: String): List<InstrumentCandidate> {
        val text = query.trim()
        return when {
            text.isEmpty() -> emptyList()
            looksLikeIsin(text) -> searchByIsin(text.uppercase())
            else -> searchByTickerOrName(text)
        }
    }

    override suspend fun latestQuote(ref: InstrumentRef): Quote = when (ref.provider) {
        QuoteProvider.MORNINGSTAR -> morningstar.latestNav(ref.code)
        QuoteProvider.YAHOO -> yahoo.latestQuote(ref.code)
        QuoteProvider.COINGECKO -> coinGecko.latestQuote(ref.code)
    }

    override suspend fun priceHistory(ref: InstrumentRef, from: LocalDate?): List<PricePoint> = when (ref.provider) {
        QuoteProvider.MORNINGSTAR -> morningstar.priceHistory(ref.code, from)
        QuoteProvider.YAHOO -> yahoo.priceHistory(ref.code, from)
        QuoteProvider.COINGECKO -> coinGecko.priceHistory(ref.code, from)
    }

    override suspend fun fundamentals(isin: String, preferred: InstrumentRef?): InstrumentFundamentals? {
        if (preferred?.provider == QuoteProvider.COINGECKO) return null
        val secId = preferred?.takeIf { it.provider == QuoteProvider.MORNINGSTAR }?.code
        return morningstar.fundamentals(isin.trim().uppercase(), secId)
    }

    private suspend fun searchByTickerOrName(text: String): List<InstrumentCandidate> = coroutineScope {
        val fromYahoo = async { runCatchingUnavailable { yahoo.search(text) } }
        val fromCoinGecko = async { runCatchingUnavailable { coinGecko.search(text) } }
        val yahooResult = fromYahoo.await()
        val coinGeckoResult = fromCoinGecko.await()
        if (yahooResult.isFailure && coinGeckoResult.isFailure) {
            throw QuoteUnavailableException("No quote provider answered the search", yahooResult.exceptionOrNull())
        }
        mergeCoins(text, yahooResult.getOrDefault(emptyList()), coinGeckoResult.getOrDefault(emptyList()))
    }

    private fun mergeCoins(
        query: String,
        fromYahoo: List<InstrumentCandidate>,
        coins: List<InstrumentCandidate>,
    ): List<InstrumentCandidate> {
        val exact = coins.filter { coin ->
            coin.ticker.equals(query, ignoreCase = true) || coin.name.trim().equals(query, ignoreCase = true)
        }
        val merged = if (exact.isEmpty()) fromYahoo + coins.take(TOP_COINS) else exact.take(TOP_COINS) + fromYahoo
        return merged.distinctBy { it.ref }
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
        const val TOP_COINS = 3
    }
}
