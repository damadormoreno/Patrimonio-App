package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.datasources.CachedSymbol
import com.denebapps.patrimonio.data.datasources.DataSourceSettingsStore
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Adds the user's own EODHD key to the market data, without changing what a holding is: the holding keeps its
 * Morningstar or Yahoo [InstrumentRef] and [delegate] still serves it. For the latest price and the price history:
 *
 * - **Fallback (the default)**: [delegate] answers; only when it fails with a [QuoteUnavailableException] is EODHD asked,
 *   and if EODHD cannot answer either, the delegate's own exception is rethrown.
 * - **Primary** ([com.denebapps.patrimonio.data.datasources.DataSourceSettings.eodhdPrimary]): EODHD first, [delegate]
 *   when EODHD cannot answer.
 *
 * EODHD is skipped, and every call is a pure pass-through, when there is no key, the key was refused, the daily quota ran
 * out today (UTC), the ref is a crypto, or no holding gives the ref an ISIN or ticker to look up. A refused key or an
 * exhausted quota is remembered in the settings and never reaches the caller as an EODHD error. The search and the
 * fundamentals are not EODHD's to answer and always pass through.
 *
 * The EODHD symbol of an instrument costs one search call: it is found by ISIN (or, failing that, by the ticker through
 * its exchange suffix) among the listings in the holding's currency, and remembered, a miss included. A quote or price
 * in another currency than the holding's is never returned.
 */
class EodhdQuoteSource(
    private val delegate: MarketDataSource,
    private val eodhd: EodhdApi,
    private val settings: DataSourceSettingsStore,
    private val identities: InstrumentIdentityLookup,
    private val clock: Clock = Clock.System,
) : MarketDataSource {
    override suspend fun search(query: String): List<InstrumentCandidate> = delegate.search(query)

    override suspend fun fundamentals(isin: String, preferred: InstrumentRef?): InstrumentFundamentals? =
        delegate.fundamentals(isin, preferred)

    override suspend fun latestQuote(ref: InstrumentRef): Quote = route(
        ref = ref,
        viaDelegate = { delegate.latestQuote(ref) },
        viaEodhd = { target, listing ->
            eodhd.latestQuote(target.key, listing.symbol, listing.currencyCode, ref)
                .takeIf { target.holdsIn(it.price.currency.code) }
        },
    )

    override suspend fun priceHistory(ref: InstrumentRef, from: LocalDate?): List<PricePoint> = route(
        ref = ref,
        viaDelegate = { delegate.priceHistory(ref, from) },
        viaEodhd = { target, listing ->
            eodhd.history(target.key, listing.symbol, listing.currencyCode, from)
                // An empty window since a date is an answer; nothing at all for "everything" is EODHD not knowing.
                .takeUnless { it.isEmpty() && from == null }
                ?.takeIf { points -> points.all { target.holdsIn(it.price.currency.code) } }
        },
    )

    /** What one call needs of EODHD: the key, the order, and the instrument's identity. */
    private class Target(
        val key: String,
        val primary: Boolean,
        val isin: String?,
        val ticker: String?,
        val holdingCurrency: String?,
    ) {
        val cacheKey: String get() = if (isin != null) "ISIN:$isin" else "TICKER:$ticker"

        fun holdsIn(priceCurrency: String): Boolean =
            holdingCurrency == null || sameCurrency(holdingCurrency, priceCurrency)
    }

    /** An EODHD listing to price: [currencyCode] is EODHD's own (`GBX` for pence). */
    private class Listing(val symbol: String, val currencyCode: String?)

    private suspend fun <T : Any> route(
        ref: InstrumentRef,
        viaDelegate: suspend () -> T,
        viaEodhd: suspend (Target, Listing) -> T?,
    ): T {
        val target = targetOf(ref) ?: return viaDelegate()
        if (target.primary) return tryEodhd(target, viaEodhd) ?: viaDelegate()
        val original = try {
            return viaDelegate()
        } catch (failure: QuoteUnavailableException) {
            failure
        }
        return tryEodhd(target, viaEodhd) ?: throw original
    }

    /** The answer of EODHD, or `null` when it cannot give one; a refused key or a used up quota is recorded on the way. */
    private suspend fun <T : Any> tryEodhd(target: Target, block: suspend (Target, Listing) -> T?): T? = try {
        resolve(target)?.let { block(target, it) }
    } catch (_: EodhdInvalidKeyException) {
        settings.markInvalidKey(target.key)
        null
    } catch (_: EodhdQuotaExceededException) {
        settings.markQuotaExhausted(target.key, today())
        null
    } catch (_: QuoteUnavailableException) {
        null
    }

    /** `null` when EODHD must not be used for [ref] right now. */
    private suspend fun targetOf(ref: InstrumentRef): Target? {
        if (ref.provider == QuoteProvider.COINGECKO) return null
        val current = settings.current()
        val key = current.apiKey?.takeIf { it.isNotBlank() }
        if (key == null || current.invalidKey || current.quotaExhaustedOn == today()) return null
        val identity = identities.identityOf(ref) ?: return null
        val isin = identity.isin?.trim()?.uppercase()?.takeIf(::looksLikeIsin)
        val ticker = tickerOf(ref, identity)
        if (isin == null && ticker == null) return null
        return Target(key, current.eodhdPrimary, isin, ticker, identity.currencyCode)
    }

    /** A Yahoo holding is its own ticker; a Morningstar SecId or a Yahoo `0P…` fund code is not one EODHD knows. */
    private fun tickerOf(ref: InstrumentRef, identity: InstrumentIdentity): String? {
        val stored = identity.ticker?.trim()?.takeIf { it.isNotEmpty() }
        val ticker = stored ?: ref.code.takeIf { ref.provider == QuoteProvider.YAHOO }
        return ticker?.takeIf { eodhdTickerParts(it) != null }
    }

    /** The cached listing, or one search (two with an ISIN EODHD lacks and a ticker) that is cached, a miss too. */
    private suspend fun resolve(target: Target): Listing? {
        val today = today()
        val cached = settings.cachedSymbol(target.cacheKey)
        if (cached?.symbol != null) return Listing(cached.symbol, cached.currency ?: target.holdingCurrency)
        if (cached != null && cached.checkedOn.daysUntil(today) < NEGATIVE_CACHE_DAYS) return null
        val found = target.isin?.let { searchByIsin(target, it) } ?: target.ticker?.let { searchByTicker(target, it) }
        settings.cacheSymbol(target.cacheKey, CachedSymbol(found?.symbol, found?.currencyCode, today))
        return found
    }

    private suspend fun searchByIsin(target: Target, isin: String): Listing? =
        pick(target, eodhd.search(target.key, isin).filter { it.isin.equals(isin, ignoreCase = true) })

    private suspend fun searchByTicker(target: Target, ticker: String): Listing? {
        val (code, exchange) = eodhdTickerParts(ticker) ?: return null
        return pick(
            target,
            eodhd.search(target.key, code).filter {
                it.code.equals(code, ignoreCase = true) && it.exchange.equals(exchange, ignoreCase = true)
            },
        )
    }

    /** The best listing in the holding's currency (pence count as pounds): same currency, then a fund line, then primary. */
    private fun pick(target: Target, listings: List<EodhdSymbol>): Listing? {
        val holding = target.holdingCurrency
        val eligible = listings.filter { listing ->
            val listed = listing.currency
            (listed != null || holding != null) && (holding == null || listed == null || sameCurrency(holding, listed))
        }
        val best = eligible.maxByOrNull { listing ->
            val sameCurrency = holding != null && listing.currency?.let { sameCurrency(holding, it) } == true
            val fundLine = listing.type.equals("FUND", ignoreCase = true) &&
                listing.exchange.equals(FUND_EXCHANGE, ignoreCase = true)
            (if (sameCurrency) 4 else 0) + (if (fundLine) 2 else 0) + (if (listing.primary) 1 else 0)
        } ?: return null
        return Listing(best.symbol, best.currency ?: holding)
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.UTC).date

    private companion object {
        /** A "not on EODHD" answer is trusted this long before the instrument is searched again. */
        const val NEGATIVE_CACHE_DAYS = 30
        const val FUND_EXCHANGE = "EUFUND"
    }
}

/** Yahoo exchange suffixes and the EODHD exchange code of the same market, where the two differ or the market is common. */
private val EODHD_EXCHANGES = mapOf(
    "AS" to "AS",
    "PA" to "PA",
    "BR" to "BR",
    "LS" to "LS",
    "MI" to "MI",
    "MC" to "MC",
    "SW" to "SW",
    "VI" to "VI",
    "HE" to "HE",
    "CO" to "CO",
    "ST" to "ST",
    "OL" to "OL",
    "IR" to "IR",
    "F" to "F",
    "DE" to "XETRA",
    "L" to "LSE",
    "TO" to "TO",
    "V" to "V",
    "HK" to "HK",
    "T" to "TSE",
    "AX" to "AU",
)

private const val US_EXCHANGE = "US"

/**
 * Splits a Yahoo [ticker] (`IWDA.AS`, `SAP.DE`, `AAPL`) into the code and the EODHD exchange to look for: the suffix
 * through [EODHD_EXCHANGES] (an unknown suffix is tried as it is), no suffix meaning a US listing. `null` for what is
 * not a listed share: indices, currency pairs and Yahoo's `0P…` fund codes. The search result, not this mapping, decides
 * whether the listing exists.
 */
internal fun eodhdTickerParts(ticker: String): Pair<String, String>? {
    val text = ticker.trim()
    if (text.isEmpty() || text.startsWith("0P") || text.any { it == '^' || it == '=' || it.isWhitespace() }) return null
    val dot = text.lastIndexOf('.')
    if (dot < 0) return text to US_EXCHANGE
    val code = text.substring(0, dot)
    val suffix = text.substring(dot + 1).uppercase()
    if (code.isEmpty() || suffix.isEmpty()) return null
    return code to (EODHD_EXCHANGES[suffix] ?: suffix)
}

/** Whether two ISO currency codes are the same money; EODHD lists London shares in pence (`GBX`), which is pounds here. */
private fun sameCurrency(first: String, second: String): Boolean = poundsOf(first) == poundsOf(second)

private fun poundsOf(code: String): String = code.trim().let { if (it in PENCE_CODES) "GBP" else it.uppercase() }
