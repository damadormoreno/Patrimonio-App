package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.SavingsGoal

/** Where an account is used: the open goals that follow it and the persisted groups that hold it. */
data class AccountUsage(val goalNames: List<String> = emptyList(), val groupNames: List<String> = emptyList())

/**
 * [AccountUsage] by asset id, only for assets used somewhere. A goal counts for an account when it follows it
 * directly or through a linked group that holds it (once, whatever the number of links). The goal and group being
 * edited ([excludeGoalId], [excludeGroupId]) are left out so a form never tags an account with itself: goals that
 * reach the account only through the excluded group are not counted either. The builtin "all accounts" group is
 * never counted.
 */
fun accountUsage(
    goals: List<SavingsGoal>,
    groups: List<AccountGroup>,
    excludeGoalId: String? = null,
    excludeGroupId: String? = null,
): Map<String, AccountUsage> {
    val persisted = groups.filter { it.id != AccountGroup.ALL_ACCOUNTS_ID && it.id != excludeGroupId }
    val goalNames = goals
        .filter { it.tracksLinkedBalance && it.id != excludeGoalId }
        .flatMap { goal -> goalAssetIds(goal, persisted).map { it to goal } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, followers) -> followers.distinctBy { it.id }.map { it.name } }
    val groupNames = persisted
        .flatMap { group -> group.memberAssetIds.orEmpty().map { it to group.name } }
        .groupBy({ it.first }, { it.second })
    return (goalNames.keys + groupNames.keys).associateWith { assetId ->
        AccountUsage(goalNames[assetId].orEmpty(), groupNames[assetId].orEmpty())
    }
}

/** The accounts [goal] follows: its own and the members of its linked [persisted] groups. */
private fun goalAssetIds(goal: SavingsGoal, persisted: List<AccountGroup>): Set<String> =
    goal.linkedAssetIds + persisted.filter { it.id in goal.linkedGroupIds }.flatMap { it.memberAssetIds.orEmpty() }

/** Names of the open goals, other than [excludeGoalId], that follow each persisted group, by group id. */
fun goalNamesByLinkedGroup(goals: List<SavingsGoal>, excludeGoalId: String? = null): Map<String, List<String>> = goals
    .filter { it.tracksLinkedBalance && it.id != excludeGoalId }
    .flatMap { goal -> goal.linkedGroupIds.map { it to goal.name } }
    .groupBy({ it.first }, { it.second })
