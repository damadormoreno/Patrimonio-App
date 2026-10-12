package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.SavingsGoal

/**
 * Where a portfolio holding is used: the persisted groups that hold it and the open goals that follow those groups.
 * Same shape as [AccountUsage]; a holding has no goals of its own, goals reach it only through a group.
 */
data class HoldingUsage(val goalNames: List<String> = emptyList(), val groupNames: List<String> = emptyList())

/**
 * [HoldingUsage] by holding id, only for holdings that sit in a group. The goal and group being edited
 * ([excludeGoalId], [excludeGroupId]) are left out so a form never tags a holding with itself. A goal is listed once
 * even when it follows several groups of the holding; the builtin "all accounts" group is never counted.
 */
fun holdingUsage(
    goals: List<SavingsGoal>,
    groups: List<AccountGroup>,
    excludeGoalId: String? = null,
    excludeGroupId: String? = null,
): Map<String, HoldingUsage> {
    val persisted = groups.filter { it.id != AccountGroup.ALL_ACCOUNTS_ID && it.id != excludeGroupId }
    val goalsByGroup = goals
        .filter { it.tracksLinkedBalance && it.id != excludeGoalId }
        .groupBy { it.linkedGroupId }
    val groupsByHolding = persisted
        .flatMap { group -> group.memberHoldingIds.map { it to group } }
        .groupBy({ it.first }, { it.second })
    return groupsByHolding.mapValues { (_, holdingGroups) ->
        HoldingUsage(
            goalNames = holdingGroups.flatMap { goalsByGroup[it.id].orEmpty() }.distinct().map { it.name },
            groupNames = holdingGroups.map { it.name },
        )
    }
}
