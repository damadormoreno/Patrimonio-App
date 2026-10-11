package com.denebapps.patrimonio.ui.components.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeatmapColorsTest {
    private val negative = 0xFFBF5B2C.toInt()
    private val neutral = 0xFFFBF7EE.toInt()
    private val positive = 0xFF2F6DA8.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `blending at the ends returns the ends`() {
        assertEquals(negative, blendArgb(negative, positive, 0f))
        assertEquals(positive, blendArgb(negative, positive, 1f))
    }

    @Test
    fun `blending halfway averages every channel`() {
        // (0x00 + 0xFF) / 2 = 127,5, rounded to 128 = 0x80.
        assertEquals(0xFF808080.toInt(), blendArgb(black, white, 0.5f))
        assertEquals(0x80808080.toInt(), blendArgb(0x00000000, 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun `a fraction outside zero to one is clamped`() {
        assertEquals(negative, blendArgb(negative, positive, -3f))
        assertEquals(positive, blendArgb(negative, positive, 7f))
    }

    @Test
    fun `a correlation of minus one, zero and one picks the negative, neutral and positive colors`() {
        assertEquals(negative, divergingArgb(-1.0, negative, neutral, positive))
        assertEquals(neutral, divergingArgb(0.0, negative, neutral, positive))
        assertEquals(positive, divergingArgb(1.0, negative, neutral, positive))
    }

    @Test
    fun `a missing correlation is neutral`() {
        assertEquals(neutral, divergingArgb(null, negative, neutral, positive))
    }

    @Test
    fun `half a correlation is halfway between neutral and the end on its side`() {
        assertEquals(blendArgb(neutral, positive, 0.5f), divergingArgb(0.5, negative, neutral, positive))
        assertEquals(blendArgb(neutral, negative, 0.25f), divergingArgb(-0.25, negative, neutral, positive))
    }

    @Test
    fun `a value beyond the scale saturates`() {
        assertEquals(positive, divergingArgb(1.4, negative, neutral, positive))
        assertEquals(negative, divergingArgb(-2.0, negative, neutral, positive))
    }

    @Test
    fun `contrast of black on white is the maximum and of a color on itself the minimum`() {
        assertEquals(21.0, contrastRatio(black, white), 1e-9)
        assertEquals(1.0, contrastRatio(positive, positive), 1e-9)
        assertEquals(contrastRatio(white, black), contrastRatio(black, white), 1e-12)
    }

    @Test
    fun `text on a dark cell is the light option and on a light cell the dark one`() {
        assertEquals(white, readableArgb(background = black, first = black, second = white))
        assertEquals(black, readableArgb(background = white, first = black, second = white))
        assertEquals(black, readableArgb(background = neutral, first = white, second = black))
    }

    @Test
    fun `every cell color of the scale has a readable text option`() {
        val ink = 0xFF1C1814.toInt()
        val paper = 0xFFFBF7EE.toInt()
        for (step in -10..10) {
            val cell = divergingArgb(step / 10.0, negative, neutral, positive)
            assertTrue(contrastRatio(cell, readableArgb(cell, ink, paper)) >= 3.0, "step $step")
        }
    }
}
