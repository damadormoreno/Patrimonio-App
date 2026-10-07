package com.denebapps.patrimonio.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountUsageTagsTest {
    @Test
    fun `one name is shown shortened when long and several are counted`() {
        assertNull(usageTag(emptyList(), singular = "Meta", plural = "metas"))
        assertEquals("Meta · Viaje", usageTag(listOf("Viaje"), singular = "Meta", plural = "metas"))
        assertEquals(
            "Grupo · Fondo de emerge…",
            usageTag(listOf("Fondo de emergencia"), singular = "Grupo", plural = "grupos"),
        )
        assertEquals("3 metas", usageTag(listOf("A", "B", "C"), singular = "Meta", plural = "metas"))
    }
}
