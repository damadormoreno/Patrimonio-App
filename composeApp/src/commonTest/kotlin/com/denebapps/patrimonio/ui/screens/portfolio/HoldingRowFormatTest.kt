package com.denebapps.patrimonio.ui.screens.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

/** The muted second line of a Cartera row: type · class · bank or broker · (portfolio ·) units. */
class HoldingRowFormatTest {
    @Test
    fun `every part is joined in order`() {
        assertEquals(
            "Fondo · Renta variable · 12,34 part. · MyInvestor",
            holdingMetaLine("Fondo", "Renta variable", "MyInvestor", null, "12,34 part."),
        )
    }

    @Test
    fun `a missing broker leaves no gap`() {
        assertEquals(
            "ETF · Renta fija · 5,00 part.",
            holdingMetaLine("ETF", "Renta fija", null, null, "5,00 part."),
        )
    }

    @Test
    fun `an unclassified holding skips the class`() {
        assertEquals(
            "Fondo · 5,00 part. · Openbank",
            holdingMetaLine("Fondo", null, "Openbank", null, "5,00 part."),
        )
    }

    @Test
    fun `a holding with neither class nor broker is the type and the units`() {
        assertEquals(
            "Otro · Valor manual · 10/10/2026",
            holdingMetaLine("Otro", null, null, null, "Valor manual · 10/10/2026"),
        )
    }

    @Test
    fun `a closed position ends with Cerrada`() {
        assertEquals(
            "Fondo · Renta variable · Cerrada",
            holdingMetaLine("Fondo", "Renta variable", null, null, CLOSED_TEXT),
        )
    }

    @Test
    fun `the units go before the broker and the portfolio`() {
        assertEquals(
            "Acción · Renta variable · 10,00 part. · Degiro · Hijos",
            holdingMetaLine("Acción", "Renta variable", "Degiro", "Hijos", "10,00 part."),
        )
    }

    @Test
    fun `blank parts are dropped`() {
        assertEquals("Fondo · 1,00 part.", holdingMetaLine("Fondo", " ", "", null, "1,00 part."))
    }
}
