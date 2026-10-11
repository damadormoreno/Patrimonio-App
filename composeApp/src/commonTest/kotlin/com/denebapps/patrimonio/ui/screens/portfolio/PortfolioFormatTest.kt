package com.denebapps.patrimonio.ui.screens.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortfolioFormatTest {
    @Test
    fun `a correlation has two decimals and a decimal comma`() {
        assertEquals("0,85", formatCorrelation(0.85))
        assertEquals("1,00", formatCorrelation(1.0))
        assertEquals("0,07", formatCorrelation(0.0712))
    }

    @Test
    fun `a negative correlation uses a true minus sign`() {
        assertEquals("−0,12", formatCorrelation(-0.123))
        assertEquals("−1,00", formatCorrelation(-1.0))
    }

    @Test
    fun `a correlation that rounds to zero has no sign`() {
        assertEquals("0,00", formatCorrelation(0.004))
        assertEquals("0,00", formatCorrelation(-0.004))
        assertEquals("0,00", formatCorrelation(0.0))
    }

    @Test
    fun `a missing or not finite correlation is a dash`() {
        assertEquals("—", formatCorrelation(null))
        assertEquals("—", formatCorrelation(Double.NaN))
    }

    @Test
    fun `weeks are counted with the right plural`() {
        assertEquals("1 semana", weeksText(1))
        assertEquals("52 semanas", weeksText(52))
        assertEquals("0 semanas", weeksText(0))
    }

    @Test
    fun `the product mix says how many are quoted and how many manual`() {
        assertEquals("3 con cotización · 2 manuales", holdingsMixText(quoted = 3, manual = 2))
        assertEquals("1 con cotización · 1 manual", holdingsMixText(quoted = 1, manual = 1))
    }

    @Test
    fun `a portfolio of one kind says so`() {
        assertEquals("Todos con cotización", holdingsMixText(quoted = 3, manual = 0))
        assertEquals("Todos manuales", holdingsMixText(quoted = 0, manual = 2))
        assertEquals("Con cotización", holdingsMixText(quoted = 1, manual = 0))
        assertEquals("Manual", holdingsMixText(quoted = 0, manual = 1))
        assertNull(holdingsMixText(quoted = 0, manual = 0))
    }

    @Test
    fun `the excluded holdings are listed, a few names and then how many more`() {
        assertNull(excludedText(emptyList()))
        assertEquals("Excluye: Cuadro", excludedText(listOf("Cuadro")))
        assertEquals("Excluye: A, B, C", excludedText(listOf("A", "B", "C")))
        assertEquals("Excluye: A, B, C y 2 más", excludedText(listOf("A", "B", "C", "D", "E")))
        assertEquals("Excluye: A, B, C y 1 más", excludedText(listOf("A", "B", "C", "D")))
    }

    @Test
    fun `a long name is cut with an ellipsis`() {
        assertEquals("Vanguard Global Stock", shortName("Vanguard Global Stock"))
        assertEquals("Vanguard Global Stock Inde…", shortName("Vanguard Global Stock Index Fund EUR Acc", max = 27))
        assertEquals("Ab…", shortName("Abcdefgh", max = 3))
    }

    @Test
    fun `the average risk has one decimal out of seven`() {
        assertEquals("5,4 de 7", averageRiskText(5.4291))
        assertEquals("4,0 de 7", averageRiskText(4.0))
        assertEquals("6,6 de 7", averageRiskText(6.56))
    }

    @Test
    fun `coverage is only mentioned when some products have no data`() {
        assertNull(coverageNote(covered = 4, total = 4))
        assertNull(coverageNote(covered = 0, total = 0))
        assertEquals("Con datos para 3 de 4 productos", coverageNote(covered = 3, total = 4))
    }
}
