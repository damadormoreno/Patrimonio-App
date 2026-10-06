package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class MonthLabelsTest {
    @Test
    fun `monthLabelEs capitalizes the Spanish month name and appends the year`() {
        assertEquals("Mayo 2026", monthLabelEs(YearMonth(2026, 5)))
    }

    @Test
    fun `monthLabelEs covers the December-January boundary`() {
        assertEquals("Enero 2027", monthLabelEs(YearMonth(2027, 1)))
        assertEquals("Diciembre 2026", monthLabelEs(YearMonth(2026, 12)))
    }
}
