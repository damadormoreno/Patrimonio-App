package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class FxConversionTest {
    @Test
    fun `non-EUR amount converts using persisted rate`() {
        val rates = FxRates(mapOf(Currency.USD to 920_000L)) // 0.92
        val amount = CurrencyAmount(Money(10000), Currency.USD) // 100.00 USD
        assertEquals(Money(9200), amount.toEur(rates)) // 92.00 EUR
    }

    @Test
    fun `exact midpoint rounds down to the nearest even minor unit`() {
        val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 0.5
        val amount = CurrencyAmount(Money(12345), Currency.USD) // -> 6172.5 EUR cents pre-round
        assertEquals(Money(6172), amount.toEur(rates)) // 6172 already even
    }

    @Test
    fun `exact midpoint rounds up to the nearest even minor unit`() {
        val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 0.5
        val amount = CurrencyAmount(Money(12343), Currency.USD) // -> 6171.5 EUR cents pre-round
        assertEquals(Money(6172), amount.toEur(rates)) // nearest even between 6171/6172 is 6172
    }

    @Test
    fun `EUR to EUR is identity`() {
        val amount = CurrencyAmount(Money(999999), Currency.EUR)
        assertEquals(Money(999999), amount.toEur(FxRates(emptyMap())))
    }

    @Test
    fun `JPY zero-decimal conversion applies the minor-unit scale factor`() {
        val rates = FxRates(mapOf(Currency.JPY to 6_100L)) // 0.0061
        assertEquals(Money(1), CurrencyAmount(Money(1), Currency.JPY).toEur(rates)) // 1 JPY -> 0.01 EUR
        assertEquals(Money(610), CurrencyAmount(Money(1000), Currency.JPY).toEur(rates)) // 1000 JPY -> 6.10 EUR
    }

    @Test
    fun `missing persisted rate falls back to the per-currency seed`() {
        // No JPY entry in the persisted map -> FxSeedRates.scaled[JPY] must be used, not 1:1.
        val rates = FxRates(emptyMap())
        assertEquals(Money(610), CurrencyAmount(Money(1000), Currency.JPY).toEur(rates))
    }

    @Test
    fun `overflow-safe for real-estate magnitudes`() {
        val rates = FxRates(mapOf(Currency.GBP to 1_170_000L)) // 1.17
        val amount = CurrencyAmount(Money(2_000_000_000L), Currency.GBP) // 20,000,000.00 GBP
        assertEquals(Money(2_340_000_000L), amount.toEur(rates))
    }

    @Test
    fun `roundHalfEven handles negative numerators`() {
        assertEquals(-6172L, roundHalfEven(-6_172_500_000L, 1_000_000L))
    }
}
