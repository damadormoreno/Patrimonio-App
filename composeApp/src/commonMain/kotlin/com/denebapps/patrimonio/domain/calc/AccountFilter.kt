package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Asset

/** Where an account is used, for [AccountFilter.assignment]. */
enum class AccountAssignment { ALL, IN_GOALS, IN_GROUPS, UNASSIGNED }

/**
 * The Patrimonio account filter: by assignment and by asset type id ([types] empty means every type). Both
 * apply at once, so "investments followed by a goal" is [AccountAssignment.IN_GOALS] plus `INVEST`.
 */
data class AccountFilter(
    val assignment: AccountAssignment = AccountAssignment.ALL,
    val types: Set<String> = emptySet(),
) {
    val isActive: Boolean
        get() = assignment != AccountAssignment.ALL || types.isNotEmpty()

    /** [usage] is the asset's entry in [accountUsage], null when nothing uses it. */
    fun matches(asset: Asset, usage: AccountUsage?): Boolean {
        if (types.isNotEmpty() && asset.group !in types) return false
        val inGoal = usage?.goalNames.orEmpty().isNotEmpty()
        val inGroup = usage?.groupNames.orEmpty().isNotEmpty()
        return when (assignment) {
            AccountAssignment.ALL -> true
            AccountAssignment.IN_GOALS -> inGoal
            AccountAssignment.IN_GROUPS -> inGroup
            AccountAssignment.UNASSIGNED -> !inGoal && !inGroup
        }
    }
}
