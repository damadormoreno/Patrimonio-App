package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PortfolioCalcTest {
    private fun units(whole: Long, millionths: Long = 0) = Units(whole * UNITS_SCALE + millionths)

    private fun eur(whole: Long, millionths: Long = 0) = Price(whole * 1_000_000 + millionths, Currency.EUR)

    private fun quoted(units: Units, price: Price?, currency: Currency = price?.currency ?: Currency.EUR) =
        PortfolioHolding(
            id = "h-${units.scaled}-${price?.scaled}",
            name = "Fund",
            type = InstrumentType.FUND,
            isin = null,
            ticker = null,
            valuation = HoldingValuation.Quoted(
                ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P000TEST"),
                currency = currency,
                units = units,
                lastQuote = price?.let { LastQuote(it, LocalDate(2026, 10, 9), 1L) },
            ),
        )

    private fun manual(amount: Long, currency: Currency) = PortfolioHolding(
        id = "m-$amount-$currency",
        name = "Painting",
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(amount), currency), valuedAtEpochMs = 5L),
    )

    @Test
    fun `quoted value is units times price in minor units of the price currency`() {
        assertEquals(CurrencyAmount(Money(2_500), Currency.EUR), quotedValue(units(2, 500_000), eur(10)))
        // 8.100001 x 12.345678 = 100.0000041..., which rounds down to 100.00.
        assertEquals(CurrencyAmount(Money(10_000), Currency.EUR), quotedValue(Units(8_100_001), eur(12, 345_678)))
        assertEquals(CurrencyAmount(Money(0), Currency.EUR), quotedValue(units(5), eur(0)))
    }

    @Test
    fun `euro value ties round half to even`() {
        val cent = eur(0, 10_000)
        assertEquals(Money(0), quotedValue(Units(500_000), cent).amount)
        assertEquals(Money(2), quotedValue(Units(1_500_000), cent).amount)
        assertEquals(Money(2), quotedValue(Units(2_500_000), cent).amount)
        assertEquals(Money(4), quotedValue(Units(3_500_000), cent).amount)
    }

    @Test
    fun `yen value has no decimals and rounds to whole yen`() {
        val price = Price(1_234_567_000, Currency.JPY)

        assertEquals(CurrencyAmount(Money(3_704), Currency.JPY), quotedValue(units(3), price))
        val oneYen = Price(1_000_000, Currency.JPY)
        assertEquals(Money(0), quotedValue(Units(500_000), oneYen).amount)
        assertEquals(Money(2), quotedValue(Units(1_500_000), oneYen).amount)
        assertEquals(Money(2), quotedValue(Units(2_500_000), oneYen).amount)
    }

    @Test
    fun `dollar and pound values keep their currency`() {
        assertEquals(
            CurrencyAmount(Money(30_000), Currency.USD),
            quotedValue(units(3), Price(100_000_000, Currency.USD)),
        )
        assertEquals(
            CurrencyAmount(Money(15_000), Currency.GBP),
            quotedValue(units(2), Price(75_000_000, Currency.GBP)),
        )
    }

    @Test
    fun `ten million units at one hundred thousand euros does not overflow the intermediate product`() {
        val value = quotedValue(units(10_000_000), eur(100_000))

        assertEquals(CurrencyAmount(Money(100_000_000_000_000L), Currency.EUR), value)
    }

    @Test
    fun `ten million units at one hundred thousand yen does not overflow either`() {
        val value = quotedValue(units(10_000_000), Price(100_000_000_000L, Currency.JPY))

        assertEquals(CurrencyAmount(Money(1_000_000_000_000L), Currency.JPY), value)
    }

    @Test
    fun `a value that does not fit in minor units throws`() {
        assertFailsWith<ArithmeticException> {
            quotedValue(Units(Long.MAX_VALUE), Price(Long.MAX_VALUE, Currency.EUR))
        }
    }

    @Test
    fun `holding value is null until the first price arrives`() {
        assertNull(quoted(units(3), price = null).value())
    }

    @Test
    fun `quoted holding value uses its last price`() {
        assertEquals(CurrencyAmount(Money(2_500), Currency.EUR), quoted(units(2, 500_000), eur(10)).value())
    }

    @Test
    fun `manual holding value is the amount the user entered`() {
        assertEquals(CurrencyAmount(Money(123_456), Currency.USD), manual(123_456, Currency.USD).value())
    }

    @Test
    fun `units from a total divide by the price and round half to even at six decimals`() {
        // 100.00 / 12.345678 = 8.1000006...
        assertEquals(Units(8_100_001), unitsFromTotal(Money(10_000), eur(12, 345_678)))
        assertEquals(Units(100_000), unitsFromTotal(Money(1_000), eur(100)))
        // 0.01 / 4000 = 0.0000025 -> 2.5 millionths -> 2; 0.07 / 20000 = 3.5 millionths -> 4.
        assertEquals(Units(2), unitsFromTotal(Money(1), eur(4_000)))
        assertEquals(Units(4), unitsFromTotal(Money(7), eur(20_000)))
    }

    @Test
    fun `units from a total in yen use whole yen`() {
        assertEquals(Units(9_950_249), unitsFromTotal(Money(1_000), Price(100_500_000, Currency.JPY)))
    }

    @Test
    fun `units from a total round trip with the value for large positions`() {
        val price = eur(100_000)
        val total = Money(100_000_000_000_000L)

        val units = unitsFromTotal(total, price)

        assertEquals(units(10_000_000), units)
        assertEquals(total, quotedValue(units, price).amount)
    }

    @Test
    fun `units from a total reject a zero price a non positive total and a result that rounds to zero`() {
        assertFailsWith<IllegalArgumentException> { unitsFromTotal(Money(10_000), eur(0)) }
        assertFailsWith<IllegalArgumentException> { unitsFromTotal(Money(0), eur(10)) }
        assertFailsWith<IllegalArgumentException> { unitsFromTotal(Money(-5), eur(10)) }
        assertFailsWith<IllegalArgumentException> { unitsFromTotal(Money(1), eur(1_000_000_000)) }
    }

    @Test
    fun `units from a total that overflow the units range throw`() {
        assertFailsWith<ArithmeticException> { unitsFromTotal(Money(100_000_000_000_000L), Price(1, Currency.EUR)) }
    }

    @Test
    fun `portfolio total converts every value to euros and skips holdings without a price`() {
        val rates = FxRates(mapOf(Currency.USD to 920_000L, Currency.JPY to 6_100L))
        val holdings = listOf(
            quoted(units(2, 500_000), eur(10)),
            manual(10_000, Currency.USD),
            quoted(units(3), Price(1_234_567_000, Currency.JPY)),
            quoted(units(7), price = null),
        )

        // 25.00 EUR + 100.00 USD x 0.92 (92.00) + 3,704 JPY x 0.0061 (22.59).
        assertEquals(Money(2_500 + 9_200 + 2_259), portfolioTotalEur(holdings, rates))
    }

    @Test
    fun `portfolio total of nothing is zero`() {
        assertEquals(Money.ZERO, portfolioTotalEur(emptyList(), FxRates(emptyMap())))
        assertEquals(Money.ZERO, portfolioTotalEur(listOf(quoted(units(1), price = null)), FxRates(emptyMap())))
    }

    @Test
    fun `portfolio total overflow throws instead of wrapping`() {
        val huge = manual(Long.MAX_VALUE, Currency.EUR)

        assertFailsWith<ArithmeticException> {
            portfolioTotalEur(listOf(huge, manual(1, Currency.EUR)), FxRates(emptyMap()))
        }
    }
}
