package com.denebapps.patrimonio.data.quotes

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RoomInstrumentIdentityLookupTest {
    private fun holding(
        id: String,
        provider: String? = "MORNINGSTAR",
        code: String? = "0P00012PP6",
        isin: String? = "IE00B03HCZ61",
        ticker: String? = null,
        currency: String = "EUR",
    ) = PortfolioHoldingEntity(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = id,
        type = "FUND",
        isin = isin,
        ticker = ticker,
        kind = if (provider == null) "MANUAL" else "QUOTED",
        provider = provider,
        providerCode = code,
        currency = currency,
        unitsScaled = if (provider == null) null else 1_000_000,
        priceScaled = null,
        priceDateEpochDay = null,
        priceFetchedAtEpochMs = null,
        valueMinor = if (provider == null) 100 else null,
        valuedAtEpochMs = if (provider == null) 1 else null,
    )

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6")

    @Test
    fun `the identity of a holding is its ISIN, ticker and currency`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(
            holding("a", provider = "YAHOO", code = "IWDA.AS", isin = null, ticker = "IWDA.AS"),
        )
        val lookup = RoomInstrumentIdentityLookup(db.portfolioHoldingDao())

        val identity = lookup.identityOf(InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS"))

        assertEquals(InstrumentIdentity(isin = null, ticker = "IWDA.AS", currencyCode = "EUR"), identity)
        db.close()
    }

    @Test
    fun `an instrument no holding tracks has no identity, nor does the same code under another provider`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(holding("a"))
        db.portfolioHoldingDao().insert(holding("manual", provider = null, code = null, isin = null))
        val lookup = RoomInstrumentIdentityLookup(db.portfolioHoldingDao())

        assertNull(lookup.identityOf(InstrumentRef(QuoteProvider.MORNINGSTAR, "0P-missing")))
        assertNull(lookup.identityOf(InstrumentRef(QuoteProvider.YAHOO, "0P00012PP6")))
        db.close()
    }

    @Test
    fun `with several holdings on one instrument the one with an ISIN wins`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertTestPortfolio()
        val dao = db.portfolioHoldingDao()
        dao.insert(holding("a-no-isin", isin = null))
        dao.insert(holding("b-with-isin", isin = "IE00B03HCZ61"))
        val lookup = RoomInstrumentIdentityLookup(dao)

        assertEquals("IE00B03HCZ61", lookup.identityOf(fundRef)?.isin)
        db.close()
    }
}
