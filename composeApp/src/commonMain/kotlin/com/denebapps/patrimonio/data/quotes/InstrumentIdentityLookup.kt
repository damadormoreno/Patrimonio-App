package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.db.dao.PortfolioHoldingDao
import com.denebapps.patrimonio.domain.model.InstrumentRef

/**
 * What a holding says about the product behind an [InstrumentRef], which the ref alone does not: its [isin] and
 * [ticker] (either may be `null`) and the [currencyCode] it is held in (`null` when unknown).
 */
data class InstrumentIdentity(val isin: String?, val ticker: String?, val currencyCode: String?)

/** Maps an [InstrumentRef] to the identity of the holding that tracks it. */
fun interface InstrumentIdentityLookup {
    /** The identity of a holding quoted as [ref], or `null` when no holding is (a search candidate, for example). */
    suspend fun identityOf(ref: InstrumentRef): InstrumentIdentity?
}

/** [InstrumentIdentityLookup] over the `portfolio_holdings` table; with several holdings on one ref, the one with an ISIN wins. */
class RoomInstrumentIdentityLookup(private val dao: PortfolioHoldingDao) : InstrumentIdentityLookup {
    override suspend fun identityOf(ref: InstrumentRef): InstrumentIdentity? =
        dao.findByInstrument(ref.provider.name, ref.code)?.let { row ->
            InstrumentIdentity(isin = row.isin, ticker = row.ticker, currencyCode = row.currency)
        }
}
