package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Price
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceDisplayTest {
    @Test
    fun `prices from a thousand up read with two decimals`() {
        // CoinGecko serves Bitcoin as 74781.759614.
        assertEquals("74.781,76 €", formatPrice(Price(74_781_759_614L, Currency.EUR)))
        assertEquals("1.000,00 €", formatPrice(Price(999_999_999L, Currency.EUR)))
    }

    @Test
    fun `prices from one to a thousand keep up to four decimals`() {
        assertEquals("64,761 €", formatPrice(Price(64_761_000L, Currency.EUR)))
        assertEquals("131,67 €", formatPrice(Price(131_670_000L, Currency.EUR)))
        assertEquals("12,3457 €", formatPrice(Price(12_345_678L, Currency.EUR)))
    }

    @Test
    fun `prices below one keep up to six decimals`() {
        assertEquals("0,123456 €", formatPrice(Price(123_456L, Currency.EUR)))
    }

    @Test
    fun `the currency decimals stay the minimum`() {
        assertEquals("245,12 €", formatPrice(Price(245_120_000L, Currency.EUR)))
        assertEquals("150 ¥", formatPrice(Price(150_000_000L, Currency.JPY)))
    }

    @Test
    fun `price changes follow the same rule`() {
        assertEquals("−20.601,31 €", formatSignedPrice(-20_601_312_435L, Currency.EUR))
        assertEquals("+11,197 €", formatSignedPrice(11_197_000L, Currency.EUR))
    }
}
