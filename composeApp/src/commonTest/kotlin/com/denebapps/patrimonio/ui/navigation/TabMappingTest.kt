package com.denebapps.patrimonio.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabMappingTest {
    @Test
    fun `route names are the serial names navigation uses, which R8 does not rename`() {
        assertEquals("com.denebapps.patrimonio.ui.navigation.Patrimonio", routeName<Patrimonio>())
        assertEquals("com.denebapps.patrimonio.ui.navigation.Subscriptions", routeName<Subscriptions>())
    }

    @Test
    fun `byId returns the matching TabKey`() {
        assertEquals(TabKey.PATRIMONIO, TabKey.byId("patrimonio"))
    }

    @Test
    fun `byId returns null for an unknown id`() {
        assertNull(TabKey.byId("unknown"))
    }

    @Test
    fun `patrimonio and subscriptions tabs show the tab bar`() {
        assertEquals(listOf(TabKey.PATRIMONIO, TabKey.SUBSCRIPTIONS), TabMapping.tabs)
        assertTrue(TabMapping.tabBarVisible)
    }

    @Test
    fun `tabForRoute maps each tab route to its TabKey`() {
        assertEquals(TabKey.PATRIMONIO, TabMapping.tabForRoute(routeName<Patrimonio>()))
        assertEquals(TabKey.SUBSCRIPTIONS, TabMapping.tabForRoute(routeName<Subscriptions>()))
        assertEquals(TabKey.SUBSCRIPTIONS, TabKey.byId("subs"))
        assertNull(TabMapping.tabForRoute(routeName<EditSubscription>()))
    }

    @Test
    fun `tabForRoute maps pushed and unknown routes to null`() {
        assertNull(TabMapping.tabForRoute(routeName<Settings>()))
        assertNull(TabMapping.tabForRoute(routeName<Profile>()))
        assertNull(TabMapping.tabForRoute(routeName<AddPatrimonio>()))
        assertNull(TabMapping.tabForRoute(null))
        assertNull(TabMapping.tabForRoute("not.a.real.Route"))
    }

    @Test
    fun `fabVisible is true on every tab route and false on non-tab routes`() {
        assertTrue(TabMapping.fabVisible(routeName<Patrimonio>()))
        assertFalse(TabMapping.fabVisible(routeName<Settings>()))
        assertFalse(TabMapping.fabVisible(routeName<Profile>()))
        assertFalse(TabMapping.fabVisible(null))
    }
}
