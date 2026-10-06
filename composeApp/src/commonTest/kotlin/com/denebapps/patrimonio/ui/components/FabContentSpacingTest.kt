package com.denebapps.patrimonio.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class FabContentSpacingTest {
    @Test
    fun `default scroll clearance includes FAB size edge inset and content gap`() {
        assertEquals(88, fabContentBottomSpacingDp())
    }

    @Test
    fun `clearance policy scales from component dimensions rather than device values`() {
        assertEquals(
            72,
            fabContentBottomSpacingDp(fabSizeDp = 48, fabEdgeInsetDp = 12, contentGapDp = 12),
        )
    }
}
