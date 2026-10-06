package com.denebapps.patrimonio.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabMappingTest {
    @Test
    fun `byId returns the matching TabKey`() {
        assertEquals(TabKey.PATRIMONIO, TabKey.byId("patrimonio"))
    }

    @Test
    fun `byId returns null for an unknown id`() {
        assertNull(TabKey.byId("unknown"))
    }

    @Test
    fun `tab bar stays hidden while patrimonio is the only tab`() {
        assertEquals(listOf(TabKey.PATRIMONIO), TabMapping.tabs)
        assertFalse(TabMapping.tabBarVisible)
    }

    @Test
    fun `tabForRoute maps each tab route to its TabKey`() {
        assertEquals(TabKey.PATRIMONIO, TabMapping.tabForRoute(Patrimonio::class.qualifiedName))
    }

    @Test
    fun `tabForRoute maps pushed and unknown routes to null`() {
        assertNull(TabMapping.tabForRoute(Settings::class.qualifiedName))
        assertNull(TabMapping.tabForRoute(Profile::class.qualifiedName))
        assertNull(TabMapping.tabForRoute(AddPatrimonio::class.qualifiedName))
        assertNull(TabMapping.tabForRoute(null))
        assertNull(TabMapping.tabForRoute("not.a.real.Route"))
    }

    @Test
    fun `fabVisible is true on every tab route and false on non-tab routes`() {
        assertTrue(TabMapping.fabVisible(Patrimonio::class.qualifiedName))
        assertFalse(TabMapping.fabVisible(Settings::class.qualifiedName))
        assertFalse(TabMapping.fabVisible(Profile::class.qualifiedName))
        assertFalse(TabMapping.fabVisible(null))
    }
}
