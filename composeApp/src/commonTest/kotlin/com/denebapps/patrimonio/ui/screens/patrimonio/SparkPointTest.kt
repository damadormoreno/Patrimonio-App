package com.denebapps.patrimonio.ui.screens.patrimonio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparkPointTest {
    @Test
    fun `points min-max normalize within padding oldest-first`() {
        val points = netWorthSparkPoints(values = listOf(100L, 200L, 150L), width = 340f, height = 64f, padding = 8f)

        assertEquals(8f, points[0].x, absoluteTolerance = 0.001f)
        assertEquals(170f, points[1].x, absoluteTolerance = 0.001f)
        assertEquals(332f, points[2].x, absoluteTolerance = 0.001f)
        assertEquals(56f, points[0].y, absoluteTolerance = 0.001f)
        assertEquals(8f, points[1].y, absoluteTolerance = 0.001f)
        assertEquals(32f, points[2].y, absoluteTolerance = 0.001f)
    }

    @Test
    fun `all-equal values sit on the baseline without division by zero`() {
        val points = netWorthSparkPoints(values = listOf(50L, 50L), width = 200f, height = 64f, padding = 8f)

        assertEquals(listOf(8f, 192f), points.map { it.x })
        assertEquals(listOf(56f, 56f), points.map { it.y })
    }

    @Test
    fun `fewer than two values returns an empty list`() {
        assertTrue(netWorthSparkPoints(values = emptyList(), width = 340f, height = 64f, padding = 8f).isEmpty())
        assertTrue(netWorthSparkPoints(values = listOf(100L), width = 340f, height = 64f, padding = 8f).isEmpty())
    }
}
