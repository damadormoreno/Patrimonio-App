package com.denebapps.patrimonio.ui.navigation

import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.QuoteProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstrumentPreviewRouteTest {
    @Test
    fun `the route identifies the instrument by provider and code and gives it back`() {
        val ref = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS")

        val route = InstrumentPreview.of(ref)

        assertEquals(InstrumentPreview("YAHOO", "IWDA.AS"), route)
        assertEquals(ref, route.ref)
    }

    @Test
    fun `the preview is a pushed screen, not a tab`() {
        assertNull(TabMapping.tabForRoute(routeName<InstrumentPreview>()))
    }
}
