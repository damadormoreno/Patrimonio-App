package com.denebapps.patrimonio.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class TabBarInsetsTest {
    @Test
    fun `three button navigation adds its bottom inset above base content padding`() {
        assertEquals(50, tabBarBottomPaddingDp(navigationBarInsetDp = 42))
    }

    @Test
    fun `gesture navigation inset is applied exactly once`() {
        assertEquals(32, tabBarBottomPaddingDp(navigationBarInsetDp = 24))
        assertEquals(8, tabBarBottomPaddingDp(navigationBarInsetDp = 0))
    }
}
