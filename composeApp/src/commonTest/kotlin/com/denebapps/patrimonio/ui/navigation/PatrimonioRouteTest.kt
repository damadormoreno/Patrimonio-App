package com.denebapps.patrimonio.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PatrimonioRouteTest {
    @Test
    fun `add patrimonio route defaults to asset mode with no group prefill`() {
        val route = AddPatrimonio()

        assertFalse(route.isLiability)
        assertNull(route.groupId)
    }

    @Test
    fun `add patrimonio route carries the active view and a per-group prefill`() {
        val route = AddPatrimonio(isLiability = true, groupId = "MORTGAGE")

        assertEquals(true, route.isLiability)
        assertEquals("MORTGAGE", route.groupId)
    }

    @Test
    fun `add patrimonio is not a tab route`() {
        assertNull(TabMapping.tabForRoute(AddPatrimonio()::class.qualifiedName))
        assertFalse(TabMapping.fabVisible(AddPatrimonio()::class.qualifiedName))
    }
}
