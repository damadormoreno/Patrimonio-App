package com.denebapps.patrimonio.ui.screens.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

class AverageTerFormatTest {
    @Test
    fun `a weighted average ongoing charge reads with two decimals`() {
        // 0,1668 % -> 0,17 %; 0,23 % stays; whole percentages keep two decimals.
        assertEquals("0,17 %", formatAverageTer(1_668))
        assertEquals("0,23 %", formatAverageTer(2_300))
        assertEquals("1,00 %", formatAverageTer(10_000))
    }

    @Test
    fun `halves round to the even hundredth`() {
        // 0,0750 % -> 0,08 % (7,5 hundredths rounds to 8); 0,0650 % -> 0,06 %.
        assertEquals("0,08 %", formatAverageTer(750))
        assertEquals("0,06 %", formatAverageTer(650))
    }
}
