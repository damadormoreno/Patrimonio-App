package com.denebapps.patrimonio.ui.screens.patrimonio

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupDeletionCopyTest {
    private fun confirmation(vararg goals: String) =
        GroupDeletionConfirmationUi(groupId = "g1", groupName = "Personal", linkedGoalNames = goals.toList())

    @Test
    fun `title quotes the group name`() {
        assertEquals("Eliminar «Personal»", deleteGroupDialogTitle(confirmation("Vacaciones")))
    }

    @Test
    fun `message for a single goal uses the singular form`() {
        assertEquals(
            "La meta «Vacaciones» está vinculada a este grupo y se quedará sin vincular. " +
                "Conservará lo que lleva ahorrado.",
            deleteGroupDialogMessage(confirmation("Vacaciones")),
        )
    }

    @Test
    fun `message for several goals uses the plural form with a natural list`() {
        assertEquals(
            "Las metas «A», «B» y «C» están vinculadas a este grupo y se quedarán sin vincular. " +
                "Conservarán lo que llevan ahorrado.",
            deleteGroupDialogMessage(confirmation("A", "B", "C")),
        )
    }

    @Test
    fun `list join handles one, two and many items`() {
        assertEquals("«A»", joinSpanishList(listOf("«A»")))
        assertEquals("«A» y «B»", joinSpanishList(listOf("«A»", "«B»")))
        assertEquals("«A», «B», «C» y «D»", joinSpanishList(listOf("«A»", "«B»", "«C»", "«D»")))
    }
}
