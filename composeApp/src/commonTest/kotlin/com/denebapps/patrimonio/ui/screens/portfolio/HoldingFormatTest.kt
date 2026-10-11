package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HoldingFormatTest {
    @Test
    fun `type labels are the Spanish names of each kind`() {
        assertEquals("Fondo", instrumentTypeLabel(InstrumentType.FUND))
        assertEquals("ETF", instrumentTypeLabel(InstrumentType.ETF))
        assertEquals("Acción", instrumentTypeLabel(InstrumentType.STOCK))
        assertEquals("Otro", instrumentTypeLabel(InstrumentType.OTHER))
    }

    @Test
    fun `provider labels name the public source`() {
        assertEquals("Morningstar", providerLabel(QuoteProvider.MORNINGSTAR))
        assertEquals("Yahoo Finance", providerLabel(QuoteProvider.YAHOO))
    }

    @Test
    fun `short dates are day slash month slash year, zero padded`() {
        assertEquals("10/10/2026", formatShortDate(LocalDate(2026, 10, 10)))
        assertEquals("05/01/2026", formatShortDate(LocalDate(2026, 1, 5)))
    }

    @Test
    fun `prices keep at least the currency decimals and drop trailing zeros beyond them`() {
        assertEquals("245,12 €", formatPrice(Price(245_120_000, Currency.EUR)))
        assertEquals("12,345678 €", formatPrice(Price(12_345_678, Currency.EUR)))
        assertEquals("1.234,56 €", formatPrice(Price(1_234_560_000, Currency.EUR)))
        assertEquals("100,00 $", formatPrice(Price(100_000_000, Currency.USD)))
        assertEquals("0,001 £", formatPrice(Price(1_000, Currency.GBP)))
        assertEquals("150 ¥", formatPrice(Price(150_000_000, Currency.JPY)))
        assertEquals("150,5 ¥", formatPrice(Price(150_500_000, Currency.JPY)))
    }

    @Test
    fun `units input keeps digits and one comma with six decimals at most`() {
        assertEquals("12,345678", filterUnitsInput("12.3456789"))
        assertEquals("1,23", filterUnitsInput("1,2,3"))
        assertEquals("12", filterUnitsInput("abc12"))
        assertEquals("", filterUnitsInput("-"))
        assertEquals("12,5", filterUnitsInput("12,5"))
    }
}
