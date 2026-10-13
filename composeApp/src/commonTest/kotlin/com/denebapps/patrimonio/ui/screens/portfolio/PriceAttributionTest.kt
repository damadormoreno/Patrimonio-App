package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.QuoteProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceAttributionTest {
    @Test
    fun `crypto prices are attributed to CoinGecko`() {
        assertEquals("Powered by CoinGecko", priceAttribution(QuoteProvider.COINGECKO))
    }

    @Test
    fun `other providers ask for no attribution`() {
        assertNull(priceAttribution(QuoteProvider.MORNINGSTAR))
        assertNull(priceAttribution(QuoteProvider.YAHOO))
    }

    @Test
    fun `the CoinGecko chart note keeps the history limit and carries the attribution`() {
        val note = historyNote(QuoteProvider.COINGECKO)

        assertEquals("Histórico del último año (límite de la API). Powered by CoinGecko.", note)
        assertTrue("Powered by CoinGecko" in note.orEmpty())
    }

    @Test
    fun `other providers have no chart note`() {
        assertNull(historyNote(QuoteProvider.MORNINGSTAR))
        assertNull(historyNote(QuoteProvider.YAHOO))
    }
}
