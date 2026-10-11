package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.InstrumentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetClassUiTest {
    @Test
    fun `every class has its Spanish name and no class is unclassified`() {
        assertEquals("Renta variable", assetClassLabel(AssetClass.EQUITY))
        assertEquals("Renta fija", assetClassLabel(AssetClass.FIXED_INCOME))
        assertEquals("Monetario", assetClassLabel(AssetClass.MONEY_MARKET))
        assertEquals("Mixto", assetClassLabel(AssetClass.MIXED))
        assertEquals("Oro y materias primas", assetClassLabel(AssetClass.COMMODITIES))
        assertEquals("Inmobiliario", assetClassLabel(AssetClass.REAL_ESTATE))
        assertEquals("Cripto", assetClassLabel(AssetClass.CRYPTO))
        assertEquals("Otros", assetClassLabel(AssetClass.OTHER))
        assertEquals("Sin clasificar", assetClassLabel(null))
    }

    @Test
    fun `a class inferred from the Morningstar category says where it comes from`() {
        val choice = assetClassChoice(explicit = null, category = "RV Global Emergente", type = InstrumentType.FUND)

        assertEquals(AssetClass.EQUITY, choice.effective)
        assertEquals("Renta variable (según Morningstar)", choice.text)
        assertEquals("Automática (según Morningstar)", choice.automaticLabel)
        assertTrue(choice.isAutomatic)
    }

    @Test
    fun `a class implied by the type says so`() {
        val choice = assetClassChoice(explicit = null, category = null, type = InstrumentType.CRYPTO)

        assertEquals(AssetClass.CRYPTO, choice.effective)
        assertEquals("Cripto (según el tipo)", choice.text)
        assertEquals("Automática (según el tipo)", choice.automaticLabel)
    }

    @Test
    fun `without a category or an explicit class the product is unclassified`() {
        val choice = assetClassChoice(explicit = null, category = null, type = InstrumentType.ETF)

        assertNull(choice.effective)
        assertEquals("Sin clasificar", choice.text)
        assertEquals("Sin clasificar", choice.automaticLabel)
        assertTrue(choice.isAutomatic)
    }

    @Test
    fun `an explicit class wins over the inferred one`() {
        val choice = assetClassChoice(AssetClass.COMMODITIES, category = "RV Global", type = InstrumentType.FUND)

        assertEquals(AssetClass.COMMODITIES, choice.effective)
        assertEquals("Oro y materias primas", choice.text)
        assertEquals("Automática (según Morningstar)", choice.automaticLabel)
        assertFalse(choice.isAutomatic)
    }
}
