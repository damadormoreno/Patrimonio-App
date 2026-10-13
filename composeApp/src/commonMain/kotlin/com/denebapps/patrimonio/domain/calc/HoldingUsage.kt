package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.SavingsGoal

/**
 * Where a portfolio holding is used: the persisted groups that hold it and the open goals that follow it, directly
 * or through one of those groups. Same shape as [AccountUsage].
 */
data class HoldingUsage(val goalNames: List<String> = emptyList(), val groupNames: List<String> = emptyList())

/**
 * [HoldingUsage] by holding id, only for holdings that sit in a group or are followed by a goal. The goal and group
 * being edited ([excludeGoalId], [excludeGroupId]) are left out so a form never tags a holding with itself: goals that
 * reach the holding only through the excluded group are not counted either. A goal is listed once even when it
 * reaches the holding through several links; the builtin "all accounts" group is never counted.
 */
fun holdingUsage(
    goals: List<SavingsGoal>,
    groups: List<AccountGroup>,
    excludeGoalId: String? = null,
    excludeGroupId: String? = null,
): Map<String, HoldingUsage> {
    val persisted = groups.filter { it.id != AccountGroup.ALL_ACCOUNTS_ID && it.id != excludeGroupId }
    val groupsByHolding = persisted
        .flatMap { group -> group.memberHoldingIds.map { it to group } }
        .groupBy({ it.first }, { it.second })
    val goalNames = goals
        .filter { it.tracksLinkedBalance && it.id != excludeGoalId }
        .flatMap { goal -> goalHoldingIds(goal, persisted).map { it to goal } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, followers) -> followers.distinctBy { it.id }.map { it.name } }
    return (groupsByHolding.keys + goalNames.keys).associateWith { holdingId ->
        HoldingUsage(
            goalNames = goalNames[holdingId].orEmpty(),
            groupNames = groupsByHolding[holdingId].orEmpty().map { it.name },
        )
    }
}

/** The holdings [goal] follows: its own and those held by its linked [persisted] groups. */
private fun goalHoldingIds(goal: SavingsGoal, persisted: List<AccountGroup>): Set<String> =
    goal.linkedHoldingIds + persisted.filter { it.id in goal.linkedGroupIds }.flatMap { it.memberHoldingIds }
