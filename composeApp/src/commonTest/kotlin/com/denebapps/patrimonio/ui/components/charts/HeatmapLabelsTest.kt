package com.denebapps.patrimonio.ui.components.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeatmapLabelsTest {
    @Test
    fun `a name of several words becomes its initials, at most three`() {
        assertEquals(listOf("AC"), heatmapLabels(listOf("Acme Corp")))
        assertEquals(listOf("VGS"), heatmapLabels(listOf("Vanguard Global Stock Index")))
    }

    @Test
    fun `a one word name keeps its first three letters`() {
        assertEquals(listOf("BIT"), heatmapLabels(listOf("Bitcoin")))
        assertEquals(listOf("AB"), heatmapLabels(listOf("Ab")))
    }

    @Test
    fun `small connecting words are skipped`() {
        assertEquals(listOf("PP"), heatmapLabels(listOf("Plan de pensiones")))
        assertEquals(listOf("BP"), heatmapLabels(listOf("Bank of the Pacific")))
    }

    @Test
    fun `labels are upper case and accented letters stay`() {
        assertEquals(listOf("ÁB"), heatmapLabels(listOf("ábaco bursátil")))
    }

    @Test
    fun `symbols are not initials`() {
        assertEquals(listOf("SP"), heatmapLabels(listOf("S&P & Pacific")))
        assertEquals(listOf("ISH"), heatmapLabels(listOf("- iShares")))
    }

    @Test
    fun `equal labels get a number so every axis entry is different`() {
        val names = listOf("Vanguard Global Stock", "Vega Gold Shares", "Viva Green Sun")
        assertEquals(listOf("VGS", "VGS2", "VGS3"), heatmapLabels(names))
    }

    @Test
    fun `a number never collides with a label that already looks like it`() {
        val labels = heatmapLabels(listOf("Alfa Beta", "Alfa Bravo", "Alfa Beta 2"))
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("AB", labels.first())
    }

    @Test
    fun `a blank name still gets a label`() {
        assertEquals(listOf("?"), heatmapLabels(listOf("  ")))
    }

    @Test
    fun `labels keep the order of the names and are short`() {
        val names = listOf("Fondo Global", "Acme Corp", "ETF Mundial Dividendos Plus", "Cuadro")
        val labels = heatmapLabels(names)
        assertEquals(names.size, labels.size)
        assertEquals(listOf("FG", "AC", "EMD", "CUA"), labels)
        assertTrue(labels.all { it.length <= 4 })
    }

    @Test
    fun `no names, no labels`() {
        assertEquals(emptyList(), heatmapLabels(emptyList()))
    }
}
