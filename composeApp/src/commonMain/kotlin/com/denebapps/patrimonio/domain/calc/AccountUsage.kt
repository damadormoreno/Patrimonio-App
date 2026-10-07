package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.SavingsGoal

/** Where an account is used: the open goals that follow it directly and the persisted groups that hold it. */
data class AccountUsage(val goalNames: List<String> = emptyList(), val groupNames: List<String> = emptyList())

/**
 * [AccountUsage] by asset id, only for assets used somewhere. The goal and group being edited
 * ([excludeGoalId], [excludeGroupId]) are left out so a form never tags an account with itself. A goal
 * that follows a group is not counted for the group's members, as in [goalsSharingLinkedBalance]; the
 * builtin "all accounts" group is never counted.
 */
fun accountUsage(
    goals: List<SavingsGoal>,
    groups: List<AccountGroup>,
    excludeGoalId: String? = null,
    excludeGroupId: String? = null,
): Map<String, AccountUsage> {
    val goalNames = goals
        .filter { it.tracksLinkedBalance && it.id != excludeGoalId }
        .flatMap { goal -> goal.linkedAssetIds.map { it to goal.name } }
        .groupBy({ it.first }, { it.second })
    val groupNames = groups
        .filter { it.id != AccountGroup.ALL_ACCOUNTS_ID && it.id != excludeGroupId }
        .flatMap { group -> group.memberAssetIds.orEmpty().map { it to group.name } }
        .groupBy({ it.first }, { it.second })
    return (goalNames.keys + groupNames.keys).associateWith { assetId ->
        AccountUsage(goalNames[assetId].orEmpty(), groupNames[assetId].orEmpty())
    }
}

/** Names of the open goals, other than [excludeGoalId], that follow each persisted group, by group id. */
fun goalNamesByLinkedGroup(goals: List<SavingsGoal>, excludeGoalId: String? = null): Map<String, List<String>> = goals
    .filter { it.tracksLinkedBalance && it.id != excludeGoalId }
    .mapNotNull { goal -> goal.linkedGroupId?.let { it to goal.name } }
    .groupBy({ it.first }, { it.second })
