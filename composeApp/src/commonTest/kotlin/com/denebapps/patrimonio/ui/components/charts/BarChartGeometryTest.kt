package com.denebapps.patrimonio.ui.components.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BarChartGeometryTest {
    private fun assertTicks(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size, "ticks: $actual")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-9) }
    }

    // ---- scale ----

    @Test
    fun `a scale of only positive values starts at zero and rounds the top up to a nice step`() {
        val scale = barScale(listOf(0.0584, 0.0270, 0.0043))

        assertEquals(0.0, scale.min, 1e-12)
        assertEquals(0.06, scale.max, 1e-9)
        assertEquals(0.02, scale.step, 1e-12)
        assertTicks(listOf(0.0, 0.02, 0.04, 0.06), scale.ticks)
    }

    @Test
    fun `a scale of only negative values ends at zero and rounds the bottom down`() {
        val scale = barScale(listOf(-0.05, -0.01))

        assertEquals(-0.06, scale.min, 1e-9)
        assertEquals(0.0, scale.max, 1e-12)
        assertTicks(listOf(-0.06, -0.04, -0.02, 0.0), scale.ticks)
    }

    @Test
    fun `a mixed scale covers the biggest gain and the biggest loss`() {
        val scale = barScale(listOf(0.0584, -0.0071, 0.0271, -0.0025))

        assertEquals(-0.02, scale.min, 1e-9)
        assertEquals(0.06, scale.max, 1e-9)
        assertTicks(listOf(-0.02, 0.0, 0.02, 0.04, 0.06), scale.ticks)
    }

    @Test
    fun `a value exactly on a step does not add a spare step above it`() {
        val scale = barScale(listOf(0.08))

        assertEquals(0.08, scale.max, 1e-9)
    }

    @Test
    fun `every value fits inside the scale`() {
        val values = listOf(1.37, -0.42, 0.9, -2.2, 0.0)
        val scale = barScale(values)

        assertTrue(scale.max >= values.max())
        assertTrue(scale.min <= values.min())
        assertTrue(scale.min <= 0.0 && scale.max >= 0.0)
    }

    @Test
    fun `without values or with all zeros the scale still has a positive height`() {
        for (values in listOf(emptyList(), listOf(0.0, 0.0))) {
            val scale = barScale(values)

            assertEquals(0.0, scale.min, 1e-12)
            assertTrue(scale.max > 0.0)
            assertTrue(scale.ticks.size >= 2)
        }
    }

    // ---- slots ----

    private fun fewSlots(count: Int, availableWidth: Float) =
        barSlots(count, availableWidth, minSlotWidth = 48f, barWidthRatio = 0.5f, maxBarWidth = 40f)

    @Test
    fun `few bars spread over the width with a capped bar width`() {
        val slots = fewSlots(3, 300f)

        assertEquals(100f, slots.slotWidth, 1e-4f)
        assertEquals(40f, slots.barWidth, 1e-4f)
        assertEquals(300f, slots.contentWidth, 1e-4f)
        assertFalse(slots.scrolls)
    }

    @Test
    fun `a bar is a share of its slot when that is below the cap`() {
        val slots = fewSlots(6, 300f)

        assertEquals(50f, slots.slotWidth, 1e-4f)
        assertEquals(25f, slots.barWidth, 1e-4f)
        assertFalse(slots.scrolls)
    }

    @Test
    fun `bars that do not fit at the minimum slot scroll`() {
        val slots = fewSlots(26, 300f)

        assertEquals(48f, slots.slotWidth, 1e-4f)
        assertEquals(24f, slots.barWidth, 1e-4f)
        assertEquals(26 * 48f, slots.contentWidth, 1e-3f)
        assertTrue(slots.scrolls)
    }

    @Test
    fun `bars that fit exactly at the minimum slot do not scroll`() {
        val slots = fewSlots(5, 240f)

        assertFalse(slots.scrolls)
        assertEquals(48f, slots.slotWidth, 1e-4f)
        assertEquals(240f, slots.contentWidth, 1e-4f)
    }

    @Test
    fun `no bars fill the width and do not scroll`() {
        val slots = barSlots(count = 0, availableWidth = 300f)

        assertFalse(slots.scrolls)
        assertEquals(300f, slots.contentWidth, 1e-4f)
    }

    // ---- layout ----

    private val slots = BarSlots(slotWidth = 40f, barWidth = 20f, contentWidth = 120f, scrolls = false)
    private val scale = BarScale(min = -0.02, max = 0.06, step = 0.02)

    private fun layout(values: List<Double>) = barLayout(
        values = values,
        scale = scale,
        slots = slots,
        plotHeight = 100f,
        labelSpace = 10f,
        labelGap = 4f,
        minBarHeight = 2f,
    )

    @Test
    fun `the zero baseline sits where the scale says once the label rooms are kept`() {
        val layout = layout(listOf(0.06, -0.02, 0.0))

        // Rooms of 10 above and below leave 80 for 0.08: zero is 0.06 down from the top of that area.
        assertEquals(70f, layout.zeroY, 1e-3f)
    }

    @Test
    fun `a gain rises from the baseline and a loss hangs below it`() {
        val bars = layout(listOf(0.06, -0.02, 0.03)).bars

        assertEquals(10f, bars[0].top, 1e-3f)
        assertEquals(70f, bars[0].bottom, 1e-3f)
        assertEquals(70f, bars[1].top, 1e-3f)
        assertEquals(90f, bars[1].bottom, 1e-3f)
        assertEquals(40f, bars[2].top, 1e-3f)
        assertEquals(70f, bars[2].bottom, 1e-3f)
    }

    @Test
    fun `labels go above a gain and below a loss, a gap away from the bar`() {
        val bars = layout(listOf(0.06, -0.02)).bars

        assertEquals(LabelSide.ABOVE, bars[0].labelSide)
        assertEquals(6f, bars[0].labelEdgeY, 1e-3f)
        assertEquals(LabelSide.BELOW, bars[1].labelSide)
        assertEquals(94f, bars[1].labelEdgeY, 1e-3f)
    }

    @Test
    fun `a zero is a minimal mark on the baseline with its label above`() {
        val bar = layout(listOf(0.0, 0.06, -0.02)).bars.first()

        assertEquals(2f, bar.bottom - bar.top, 1e-3f)
        assertEquals(70f, (bar.top + bar.bottom) / 2, 1e-3f)
        assertEquals(LabelSide.ABOVE, bar.labelSide)
        assertEquals(bar.top - 4f, bar.labelEdgeY, 1e-3f)
    }

    @Test
    fun `a tiny value is still drawn as tall as the minimal mark`() {
        val bar = layout(listOf(0.0001, 0.06, -0.02)).bars.first()

        assertEquals(2f, bar.bottom - bar.top, 1e-3f)
        assertEquals(70f, bar.bottom, 1e-3f)
    }

    @Test
    fun `bars follow the order of the values from left to right, centered in their slots`() {
        val bars = layout(listOf(0.01, 0.02, 0.03)).bars

        assertEquals(listOf(10f, 50f, 90f), bars.map { it.left })
        assertEquals(listOf(30f, 70f, 110f), bars.map { it.right })
        assertEquals(listOf(20f, 60f, 100f), bars.map { it.centerX })
    }

    @Test
    fun `gridlines are at the scale ticks, the lowest value first`() {
        val layout = layout(listOf(0.06, -0.02))

        assertEquals(listOf(90f, 70f, 50f, 30f, 10f), layout.gridY)
    }

    @Test
    fun `without losses no room is kept below the baseline`() {
        val layout = barLayout(
            values = listOf(0.06, 0.03),
            scale = BarScale(min = 0.0, max = 0.06, step = 0.02),
            slots = slots,
            plotHeight = 100f,
            labelSpace = 10f,
            labelGap = 4f,
            minBarHeight = 2f,
        )

        assertEquals(100f, layout.zeroY, 1e-3f)
        assertEquals(10f, layout.bars[0].top, 1e-3f)
    }

    @Test
    fun `the label of the tallest bar stays inside the plot`() {
        val tallest = layout(listOf(0.06, -0.02)).bars

        // The room is the label (6) and its gap (4).
        assertTrue(tallest[0].labelEdgeY - 6f >= 0f - 1e-3f)
        assertTrue(tallest[1].labelEdgeY + 6f <= 100f + 1e-3f)
    }
}
