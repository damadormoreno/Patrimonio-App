package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small pills telling where an account (or a group) is already used, under its name in the goal and
 * group forms: "Meta · Viaje" / "2 metas" and "Grupo · Ahorro" / "2 grupos". Nothing when both are empty.
 * [goalTone] lets the goal form warn (a shared account counts twice) while the group form just informs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountUsageTags(
    goalNames: List<String>,
    groupNames: List<String>,
    modifier: Modifier = Modifier,
    goalTone: PillTone = PillTone.Brand,
) {
    val goals = usageTag(goalNames, singular = "Meta", plural = "metas")
    val groups = usageTag(groupNames, singular = "Grupo", plural = "grupos")
    if (goals == null && groups == null) return
    // Wraps to a second line on narrow screens instead of squeezing the pills.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        goals?.let { Pill(text = it, tone = goalTone) }
        groups?.let { Pill(text = it, tone = PillTone.Neutral) }
    }
}

/** "Meta · Viaje" for one name (shortened past [MAX_TAG_NAME_LENGTH]), "2 metas" for more, null for none. */
internal fun usageTag(names: List<String>, singular: String, plural: String): String? = when (names.size) {
    0 -> null
    1 -> "$singular · ${names.single().ellipsized(MAX_TAG_NAME_LENGTH)}"
    else -> "${names.size} $plural"
}

private fun String.ellipsized(maxLength: Int): String = if (length <= maxLength) this else take(maxLength - 1) + "…"

private const val MAX_TAG_NAME_LENGTH = 16
