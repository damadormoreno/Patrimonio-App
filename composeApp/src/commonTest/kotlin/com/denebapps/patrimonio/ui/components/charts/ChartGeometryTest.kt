package com.denebapps.patrimonio.ui.components.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChartGeometryTest {
    private val points = listOf(
        ChartPoint(epochDay = 100, value = 10.0),
        ChartPoint(epochDay = 110, value = 20.0),
        ChartPoint(epochDay = 120, value = 15.0),
    )

    @Test
    fun `bounds span the data and pad the value range on both sides`() {
        val bounds = chartBounds(points, padRatio = 0.1)

        assertEquals(ChartBounds(minX = 100, maxX = 120, minY = 9.0, maxY = 21.0), bounds)
    }

    @Test
    fun `no points have no bounds`() {
        assertNull(chartBounds(emptyList()))
    }

    @Test
    fun `a flat series gets a range around its value so it draws in the middle`() {
        val flat = listOf(ChartPoint(100, 50.0), ChartPoint(101, 50.0))

        val bounds = checkNotNull(chartBounds(flat, padRatio = 0.1))

        assertEquals(47.5, bounds.minY, 1e-9)
        assertEquals(52.5, bounds.maxY, 1e-9)
        assertEquals(50f, projectY(50.0, bounds, height = 100f), 1e-4f)
    }

    @Test
    fun `a flat series at zero still has a range`() {
        val bounds = checkNotNull(chartBounds(listOf(ChartPoint(1, 0.0), ChartPoint(2, 0.0))))

        assertEquals(-1.0, bounds.minY, 1e-9)
        assertEquals(1.0, bounds.maxY, 1e-9)
    }

    @Test
    fun `x scales the dates linearly over the width`() {
        val bounds = checkNotNull(chartBounds(points, padRatio = 0.1))

        assertEquals(0f, projectX(100, bounds, width = 200f), 1e-4f)
        assertEquals(100f, projectX(110, bounds, width = 200f), 1e-4f)
        assertEquals(200f, projectX(120, bounds, width = 200f), 1e-4f)
    }

    @Test
    fun `y grows upwards so the highest value is at the top`() {
        val bounds = checkNotNull(chartBounds(points, padRatio = 0.1))

        assertEquals(10f, projectY(20.0, bounds, height = 120f), 1e-4f)
        assertEquals(120f, projectY(9.0, bounds, height = 120f), 1e-4f)
        assertEquals(0f, projectY(21.0, bounds, height = 120f), 1e-4f)
    }

    @Test
    fun `an inset keeps the line away from every edge`() {
        val bounds = checkNotNull(chartBounds(points, padRatio = 0.1))

        val plot = projectPoints(points, bounds, width = 220f, height = 140f, inset = 10f)

        assertEquals(10f, plot.first().x, 1e-4f)
        assertEquals(210f, plot.last().x, 1e-4f)
        assertEquals(20f, plot[1].y, 1e-4f)
        assertEquals(3, plot.size)
    }

    @Test
    fun `a single point sits in the horizontal middle`() {
        val single = listOf(ChartPoint(100, 10.0))
        val bounds = checkNotNull(chartBounds(single))

        val plot = projectPoints(single, bounds, width = 200f, height = 100f)

        assertEquals(100f, plot.single().x, 1e-4f)
        assertEquals(50f, plot.single().y, 1e-4f)
    }

    @Test
    fun `a touch picks the point whose date is nearest`() {
        val bounds = checkNotNull(chartBounds(points, padRatio = 0.1))

        assertEquals(0, nearestPointIndex(points, bounds, width = 200f, touchX = 20f))
        assertEquals(1, nearestPointIndex(points, bounds, width = 200f, touchX = 80f))
        assertEquals(1, nearestPointIndex(points, bounds, width = 200f, touchX = 120f))
        assertEquals(2, nearestPointIndex(points, bounds, width = 200f, touchX = 160f))
    }

    @Test
    fun `a touch outside the chart picks the first or the last point`() {
        val bounds = checkNotNull(chartBounds(points, padRatio = 0.1))

        assertEquals(0, nearestPointIndex(points, bounds, width = 200f, touchX = -50f))
        assertEquals(2, nearestPointIndex(points, bounds, width = 200f, touchX = 900f))
    }

    @Test
    fun `the nearest point accounts for the inset`() {
        val bounds = checkNotNull(chartBounds(points, padRatio = 0.1))

        assertEquals(0, nearestPointIndex(points, bounds, width = 220f, touchX = 30f, inset = 10f))
        assertEquals(1, nearestPointIndex(points, bounds, width = 220f, touchX = 110f, inset = 10f))
    }

    @Test
    fun `with a single point every touch picks it`() {
        val single = listOf(ChartPoint(100, 10.0))
        val bounds = checkNotNull(chartBounds(single))

        assertEquals(0, nearestPointIndex(single, bounds, width = 200f, touchX = 10f))
        assertEquals(0, nearestPointIndex(single, bounds, width = 200f, touchX = 190f))
    }

    @Test
    fun `without points there is nothing to pick`() {
        val bounds = ChartBounds(minX = 0, maxX = 1, minY = 0.0, maxY = 1.0)

        assertEquals(-1, nearestPointIndex(emptyList(), bounds, width = 200f, touchX = 10f))
    }

    @Test
    fun `unevenly spaced dates pick by distance in time not by index`() {
        val uneven = listOf(ChartPoint(0, 1.0), ChartPoint(2, 2.0), ChartPoint(100, 3.0))
        val bounds = checkNotNull(chartBounds(uneven))

        // Day 40 is nearer to day 2 (38 away) than to day 100 (60 away).
        assertEquals(1, nearestPointIndex(uneven, bounds, width = 100f, touchX = 40f))
        assertEquals(2, nearestPointIndex(uneven, bounds, width = 100f, touchX = 70f))
    }
}
