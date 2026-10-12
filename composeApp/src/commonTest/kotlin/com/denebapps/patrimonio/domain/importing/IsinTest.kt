package com.denebapps.patrimonio.domain.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsinTest {
    @Test
    fun `real ISINs pass the check digit`() {
        assertTrue(isValidIsin("IE00BD0NCM55"))
        assertTrue(isValidIsin("US0378331005"))
        assertTrue(isValidIsin("ES0113211835"))
        assertTrue(isValidIsin("LU0996182563"))
    }

    @Test
    fun `a wrong check digit, shape or case fails`() {
        assertFalse(isValidIsin("IE00BD0NCM56"))
        assertFalse(isValidIsin("US0378331006"))
        assertFalse(isValidIsin("IE00BD0NCM5"))
        assertFalse(isValidIsin("IE00BD0NCM555"))
        assertFalse(isValidIsin("ie00bd0ncm55"))
        assertFalse(isValidIsin("1E00BD0NCM55"))
        assertFalse(isValidIsin("IE00BD0NCMAB"))
        assertFalse(isValidIsin(""))
    }

    @Test
    fun `the ISIN is found in the name MyInvestor gives to the file`() {
        assertEquals("IE00BD0NCM55", isinFromFileName("Plusvalías y minusvalías fondo IE00BD0NCM55.csv"))
        assertEquals("IE00BD0NCM55", isinFromFileName("Plusvalías y minusvalías fondo IE00BD0NCM55 (1).csv"))
        assertEquals("IE00BD0NCM55", isinFromFileName("ie00bd0ncm55.csv"))
        assertEquals("IE00BD0NCM55", isinFromFileName("C:\\Descargas\\gains_IE00BD0NCM55_2026.csv"))
    }

    @Test
    fun `no ISIN is found when the name has none, the check digit fails or it is part of a longer word`() {
        assertNull(isinFromFileName("Plusvalías y minusvalías fondo.csv"))
        assertNull(isinFromFileName("Plusvalías y minusvalías fondo IE00BD0NCM56.csv"))
        assertNull(isinFromFileName("fondoIE00BD0NCM55.csv"))
        assertNull(isinFromFileName(""))
    }

    @Test
    fun `the first valid ISIN wins when the name has several tokens that look like one`() {
        assertEquals("IE00BD0NCM55", isinFromFileName("XX00000000X0 IE00BD0NCM55.csv"))
    }
}
