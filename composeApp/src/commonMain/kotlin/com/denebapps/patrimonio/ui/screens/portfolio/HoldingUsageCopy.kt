package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HoldingUsage
import com.denebapps.patrimonio.domain.calc.holdingUsage
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.ui.screens.patrimonio.deleteWarningFor
import com.denebapps.patrimonio.ui.screens.patrimonio.joinSpanishList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** [HoldingUsage] by holding id, kept up to date with the user's groups and goals. */
fun observeHoldingUsage(
    groups: AccountGroupRepository,
    goals: SavingsGoalRepository,
): Flow<Map<String, HoldingUsage>> = combine(groups.observeAll(), goals.observeAll()) { allGroups, allGoals ->
    holdingUsage(allGoals, allGroups)
}

/**
 * The muted line of the position detail: "En el grupo Casa · Cuenta para la meta Casa". Either half is left out when it
 * does not apply, and the line is `null` when the product is in no group.
 */
fun holdingUsageLine(usage: HoldingUsage?): String? {
    if (usage == null) return null
    val parts = listOfNotNull(
        usage.groupNames.takeIf { it.isNotEmpty() }
            ?.let { "En ${if (it.size == 1) "el grupo" else "los grupos"} ${joinSpanishList(it)}" },
        usage.goalNames.takeIf { it.isNotEmpty() }
            ?.let { "Cuenta para ${if (it.size == 1) "la meta" else "las metas"} ${joinSpanishList(it)}" },
    )
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/**
 * What deleting the product takes it out of, for the confirmation: "Está en la meta «Entrada» y en el grupo «Casa»; se
 * quitará de ellos." Goals are the ones that follow the groups that hold it. `null` when it is in no group.
 */
fun holdingDeleteWarning(usage: HoldingUsage?): String? = usage?.let { deleteWarningFor(it) }
