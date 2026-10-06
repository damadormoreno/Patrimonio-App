package com.denebapps.patrimonio.ui.screens.patrimonio

/** Pending "delete group" confirmation, present only when the group still has linked savings goals
 *  (deleting it unlinks them). [linkedGoalNames] covers goals in every lifecycle. */
data class GroupDeletionConfirmationUi(
    val groupId: String,
    val groupName: String,
    val linkedGoalNames: List<String>,
)

fun deleteGroupDialogTitle(confirmation: GroupDeletionConfirmationUi): String = "Eliminar «${confirmation.groupName}»"

fun deleteGroupDialogMessage(confirmation: GroupDeletionConfirmationUi): String {
    val names = joinSpanishList(confirmation.linkedGoalNames.map { "«$it»" })
    return if (confirmation.linkedGoalNames.size == 1) {
        "La meta $names está vinculada a este grupo y se quedará sin vincular. Conservará lo que lleva ahorrado."
    } else {
        "Las metas $names están vinculadas a este grupo y se quedarán sin vincular. " +
            "Conservarán lo que llevan ahorrado."
    }
}

/** Joins [items] as natural Spanish prose: "A", "A y B", "A, B y C". */
fun joinSpanishList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items.single()
    else -> items.dropLast(1).joinToString(", ") + " y " + items.last()
}
