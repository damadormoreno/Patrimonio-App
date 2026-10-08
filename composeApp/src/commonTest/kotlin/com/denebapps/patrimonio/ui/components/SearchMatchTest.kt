package com.denebapps.patrimonio.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchMatchTest {
    @Test
    fun `a blank query matches everything`() {
        assertTrue(matchesSearch("", "ING"))
        assertTrue(matchesSearch("   ", null))
    }

    @Test
    fun `case and Spanish accents are ignored`() {
        assertTrue(matchesSearch("espana", "Cuenta España"))
        assertTrue(matchesSearch("AHÓRRO", "ahorro vivienda"))
        assertTrue(matchesSearch("nomina", "Nómina BBVA"))
    }

    @Test
    fun `every word must appear, in any of the texts and any order`() {
        assertTrue(matchesSearch("naranja ing", "ING Cuenta Naranja"))
        assertTrue(matchesSearch("ing ahorro", "ING", "Ahorro · EUR"))
        assertFalse(matchesSearch("ing bbva", "ING Cuenta Naranja"))
    }

    @Test
    fun `missing texts are skipped`() {
        assertTrue(matchesSearch("revolut", "Revolut", null))
        assertFalse(matchesSearch("x", null))
    }
}
