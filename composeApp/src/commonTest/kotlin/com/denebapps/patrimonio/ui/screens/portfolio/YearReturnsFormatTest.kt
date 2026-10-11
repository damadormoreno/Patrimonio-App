package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YearReturnsFormatTest {
    private fun year(year: Int, ratio: Double, partialSince: String? = null, yearToDate: Boolean = false) =
        YearReturnUi(
            year = year,
            ratio = ratio,
            valueText = formatPercent(ratio, signed = false),
            tone = toneOf(ratio),
            partialSince = partialSince,
            yearToDate = yearToDate,
        )

    private fun eur(date: LocalDate, price: Long) = PricePoint(date, Price(price * 1_000_000, Currency.EUR))

    // ---- footnote ----

    @Test
    fun `complete years need no footnote`() {
        assertNull(yearReturnsFootnote(listOf(year(2024, 0.1), year(2025, -0.2))))
        assertNull(yearReturnsFootnote(emptyList()))
    }

    @Test
    fun `the footnote explains the partial first year and the running one`() {
        val years = listOf(
            year(2019, 0.1, partialSince = "14/03"),
            year(2020, 0.2),
            year(2026, 0.05, yearToDate = true),
        )

        assertEquals("2019: desde 14/03 · 2026: en curso", yearReturnsFootnote(years))
    }

    @Test
    fun `only the running year is explained when the first one is complete`() {
        val years = listOf(year(2025, 0.1), year(2026, 0.05, yearToDate = true))

        assertEquals("2026: en curso", yearReturnsFootnote(years))
    }

    @Test
    fun `only the partial first year is explained when there is no running one`() {
        assertEquals("2019: desde 14/03", yearReturnsFootnote(listOf(year(2019, 0.1, partialSince = "14/03"))))
    }

    @Test
    fun `a history that starts this year is both partial and running`() {
        val years = listOf(year(2026, 0.1, partialSince = "03/02", yearToDate = true))

        assertEquals("2026: desde 03/02 y en curso", yearReturnsFootnote(years))
    }

    // ---- accessibility summary ----

    @Test
    fun `the summary lists the years from the newest with their values`() {
        val years = listOf(year(2023, -0.0025), year(2024, 0.0363), year(2025, 0.047))

        assertEquals(
            "Rentabilidad por año: 2025 4,70 %, 2024 3,63 %, 2023 −0,25 %",
            yearReturnsDescription(years),
        )
    }

    @Test
    fun `the summary reads the chart from the newest year back and says which are partial`() {
        val years = listOf(
            year(2019, -0.0025, partialSince = "14/03"),
            year(2025, 0.047),
            year(2026, 0.0095, yearToDate = true),
        )

        assertEquals(
            "Rentabilidad por año: 2026 0,95 % (en curso), 2025 4,70 %, 2019 −0,25 % (desde 14/03)",
            yearReturnsDescription(years),
        )
    }

    @Test
    fun `the summary of no years is just the title`() {
        assertEquals("Rentabilidad por año", yearReturnsDescription(emptyList()))
    }

    // ---- bars ----

    @Test
    fun `a bar carries the year, the value and its text`() {
        val bar = year(2025, 0.047).toBar()

        assertEquals("2025", bar.label)
        assertEquals(0.047, bar.value, 1e-12)
        assertEquals("4,70 %", bar.valueText)
        assertEquals(false, bar.faded)
    }

    @Test
    fun `the partial and the running years are drawn faded`() {
        assertEquals(true, year(2019, 0.1, partialSince = "14/03").toBar().faded)
        assertEquals(true, year(2026, 0.1, yearToDate = true).toBar().faded)
    }

    @Test
    fun `a return that rounds to zero is a zero bar so it gets the neutral mark`() {
        val bar = year(2025, 0.00001).toBar()

        assertEquals(0.0, bar.value, 0.0)
        assertEquals("0,00 %", bar.valueText)
    }

    // ---- from prices ----

    @Test
    fun `years are built oldest first with the raw ratio and the partial and running marks`() {
        val points = listOf(
            eur(LocalDate(2024, 3, 14), 100),
            eur(LocalDate(2024, 12, 31), 110),
            eur(LocalDate(2025, 12, 31), 99),
            eur(LocalDate(2026, 10, 9), 100),
        )

        val years = yearReturnsUi(points, today = LocalDate(2026, 10, 11))

        assertEquals(listOf(2024, 2025, 2026), years.map { it.year })
        assertEquals(listOf("10,00 %", "−10,00 %", "1,01 %"), years.map { it.valueText })
        assertEquals(listOf("14/03", null, null), years.map { it.partialSince })
        assertEquals(listOf(false, false, true), years.map { it.yearToDate })
        assertEquals(0.1, years[0].ratio, 1e-9)
        assertEquals(
            listOf(PerformanceTone.GAIN, PerformanceTone.LOSS, PerformanceTone.GAIN),
            years.map { it.tone },
        )
    }
}
