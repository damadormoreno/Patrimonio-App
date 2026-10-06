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

    @Test
    fun `convertTo is identity for the same currency without consulting rates`() {
        val amount = CurrencyAmount(Money(12_345), Currency.USD)
        assertEquals(Money(12_345), amount.convertTo(Currency.USD, FxRates(emptyMap())))
    }

    @Test
    fun `convertTo EUR matches toEur`() {
        val rates = FxRates(mapOf(Currency.USD to 920_000L))
        val amount = CurrencyAmount(Money(10_000), Currency.USD)
        assertEquals(amount.toEur(rates), amount.convertTo(Currency.EUR, rates))
    }

    @Test
    fun `convertTo a foreign currency divides by the target rate`() {
        val rates = FxRates(mapOf(Currency.USD to 920_000L)) // 1 USD = 0.92 EUR
        val amount = CurrencyAmount(Money(9_200), Currency.EUR) // 92.00 EUR
        assertEquals(Money(10_000), amount.convertTo(Currency.USD, rates)) // 100.00 USD
    }

    @Test
    fun `convertTo crosses currencies through EUR`() {
        val rates = FxRates(mapOf(Currency.USD to 920_000L, Currency.GBP to 1_150_000L))
        val amount = CurrencyAmount(Money(10_000), Currency.USD) // 100.00 USD = 92.00 EUR
        assertEquals(Money(8_000), amount.convertTo(Currency.GBP, rates)) // 80.00 GBP
    }

    @Test
    fun `convertTo a zero-decimal currency applies the minor-unit scale factor`() {
        val rates = FxRates(mapOf(Currency.JPY to 6_100L)) // 1 JPY = 0.0061 EUR
        assertEquals(Money(1_000), CurrencyAmount(Money(610), Currency.EUR).convertTo(Currency.JPY, rates))
    }

    @Test
    fun `convertTo rounds half to even`() {
        val rates = FxRates(mapOf(Currency.USD to 400_000L)) // 1 USD = 0.4 EUR
        assertEquals(Money(8), CurrencyAmount(Money(3), Currency.EUR).convertTo(Currency.USD, rates)) // 7.5 -> 8
        assertEquals(Money(12), CurrencyAmount(Money(5), Currency.EUR).convertTo(Currency.USD, rates)) // 12.5 -> 12
    }
}
