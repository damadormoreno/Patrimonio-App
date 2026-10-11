package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.Drawdown
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.RangeChange
import com.denebapps.patrimonio.domain.calc.Volatility
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PositionFormatTest {
    private fun point(date: LocalDate, price: Long, currency: Currency = Currency.EUR) =
        PricePoint(date, Price(price * 1_000_000, currency))

    @Test
    fun `percentages carry an explicit sign, a decimal comma and two decimals`() {
        assertEquals("+12,34 %", formatPercent(0.1234))
        assertEquals("−3,50 %", formatPercent(-0.035))
        assertEquals("−32,10 %", formatPercent(-0.321))
        assertEquals("+0,01 %", formatPercent(0.0001))
    }

    @Test
    fun `a percentage that rounds to zero has no sign`() {
        assertEquals("0,00 %", formatPercent(0.0))
        assertEquals("0,00 %", formatPercent(-0.00001))
        assertEquals("0,00 %", formatPercent(0.00004))
    }

    @Test
    fun `large percentages are grouped by thousands`() {
        assertEquals("+1.234,50 %", formatPercent(12.345))
        assertEquals("−1.000,00 %", formatPercent(-10.0))
    }

    @Test
    fun `an unsigned percentage drops the plus and takes the decimals asked`() {
        assertEquals("23,4 %", formatPercent(0.2341, signed = false, decimals = 1))
        assertEquals("100,0 %", formatPercent(1.0, signed = false, decimals = 1))
        assertEquals("12,34 %", formatPercent(0.1234, signed = false))
    }

    @Test
    fun `a percentage that is not a number reads as a dash`() {
        assertEquals("—", formatPercent(Double.NaN))
        assertEquals("—", formatPercent(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a stored percentage shows at least two decimals and every significant one`() {
        assertEquals("0,18 %", formatPercentScaled(1_800))
        assertEquals("1,00 %", formatPercentScaled(10_000))
        assertEquals("0,1825 %", formatPercentScaled(1_825))
        assertEquals("0,00 %", formatPercentScaled(0))
        assertEquals("12,50 %", formatPercentScaled(125_000))
    }

    @Test
    fun `a price change shows its sign and the price decimals`() {
        assertEquals("+1.234,56 €", formatSignedPrice(1_234_560_000, Currency.EUR))
        assertEquals("−15,30 €", formatSignedPrice(-15_300_000, Currency.EUR))
        assertEquals("0,00 €", formatSignedPrice(0, Currency.EUR))
        assertEquals("+0,125 $", formatSignedPrice(125_000, Currency.USD))
    }

    @Test
    fun `short day and month are zero padded`() {
        assertEquals("14/03", formatDayMonthShort(LocalDate(2019, 3, 14)))
        assertEquals("05/11", formatDayMonthShort(LocalDate(2019, 11, 5)))
    }

    @Test
    fun `the deepest fall shows the day of its peak and of its trough`() {
        val drawdown = Drawdown(
            fall = 0.321,
            peak = point(LocalDate(2021, 12, 31), 120),
            trough = point(LocalDate(2022, 10, 3), 81),
        )

        assertEquals("31/12/2021 → 03/10/2022", drawdownDatesText(drawdown))
    }

    @Test
    fun `volatility says three years when the history reaches that far back`() {
        val label = volatilityLabel(
            Volatility(annualized = 0.12, weeks = 156),
            historyStart = LocalDate(2020, 1, 3),
            today = LocalDate(2026, 10, 11),
        )

        assertEquals("Volatilidad (3 años)", label)
    }

    @Test
    fun `volatility says the weeks used when the history is shorter than three years`() {
        val label = volatilityLabel(
            Volatility(annualized = 0.12, weeks = 40),
            historyStart = LocalDate(2026, 1, 2),
            today = LocalDate(2026, 10, 11),
        )

        assertEquals("Volatilidad (40 semanas)", label)
    }

    @Test
    fun `a volatility that could not be computed is labelled for three years`() {
        assertEquals(
            "Volatilidad (3 años)",
            volatilityLabel(null, historyStart = LocalDate(2026, 1, 2), today = LocalDate(2026, 10, 11)),
        )
    }

    @Test
    fun `range chips use the short Spanish names`() {
        assertEquals(
            listOf("1M", "6M", "1A", "5A", "Máx"),
            HistoryRange.entries.map(::rangeLabel),
        )
    }

    @Test
    fun `annualized labels are singular for one year`() {
        assertEquals("1 año", annualizedLabel(1))
        assertEquals("3 años", annualizedLabel(3))
        assertEquals("5 años", annualizedLabel(5))
    }

    @Test
    fun `the change over a range joins the price change and the relative change`() {
        val change = RangeChange(
            first = point(LocalDate(2025, 10, 11), 100),
            last = point(LocalDate(2026, 10, 9), 112),
            absoluteScaled = 12_000_000,
            relative = 0.12,
        )

        assertEquals("+12,00 € · +12,00 %", rangeChangeText(change, Currency.EUR))
    }

    @Test
    fun `a falling range shows minus signs on both parts`() {
        val change = RangeChange(
            first = point(LocalDate(2025, 10, 11), 100),
            last = point(LocalDate(2026, 10, 9), 90),
            absoluteScaled = -10_000_000,
            relative = -0.1,
        )

        assertEquals("−10,00 € · −10,00 %", rangeChangeText(change, Currency.EUR))
    }

    @Test
    fun `scrubbing shows the day and the price of the point`() {
        val text = scrubText(PricePoint(LocalDate(2026, 5, 4), Price(245_120_000, Currency.EUR)))

        assertEquals("04/05/2026 · 245,12 €", text)
    }

    @Test
    fun `the tone follows the sign`() {
        assertEquals(PerformanceTone.GAIN, toneOf(0.01))
        assertEquals(PerformanceTone.LOSS, toneOf(-0.01))
        assertEquals(PerformanceTone.NEUTRAL, toneOf(0.0))
        assertEquals(PerformanceTone.NEUTRAL, toneOf(0.000001, decimals = 2))
    }

    private fun start() = LocalDate(2019, 3, 14)
}
