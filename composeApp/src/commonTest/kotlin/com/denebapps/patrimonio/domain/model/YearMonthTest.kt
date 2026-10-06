package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YearMonthTest {
    @Test
    fun `same month compares equal`() {
        assertEquals(0, YearMonth(2026, 5).compareTo(YearMonth(2026, 5)))
    }

    @Test
    fun `earlier and later months compare in calendar order`() {
        assertTrue(YearMonth(2026, 4) < YearMonth(2026, 5))
        assertTrue(YearMonth(2026, 6) > YearMonth(2026, 5))
    }

    @Test
    fun `december sorts before january across a year boundary`() {
        assertTrue(YearMonth(2025, 12) < YearMonth(2026, 1))
    }

    @Test
    fun `month rejects values outside one through twelve`() {
        assertFailsWith<IllegalArgumentException> { YearMonth(2026, 0) }
        assertFailsWith<IllegalArgumentException> { YearMonth(2026, 13) }
    }
}
