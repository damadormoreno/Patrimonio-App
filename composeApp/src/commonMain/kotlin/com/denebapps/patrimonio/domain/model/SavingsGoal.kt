package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * A goal is linked to at most ONE target: an asset ([linkedAssetId]) XOR a persisted account group
 * ([linkedGroupId]) XOR nothing. The builtin "all accounts" group is never linkable.
 */
data class SavingsGoal(
    val id: Long,
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate?,
    val linkedAssetId: String?,
    val lifecycle: SavingsGoalLifecycle,
    val progress: Money,
    val linkedGroupId: String? = null,
) {
    init {
        require(linkedAssetId == null || linkedGroupId == null) {
            "Savings goal $id cannot be linked to an asset and a group at the same time"
        }
    }

    /** Reaching the target is derived independently from the explicit lifecycle. */
    val targetReached: Boolean
        get() = progress >= target.amount
}

enum class SavingsGoalLifecycle { OPEN, CLOSED, CANCELLED }

data class SavingsGoalAllocationEvent(
    val id: Long,
    val goalId: Long,
    val delta: Money,
    val timestampEpochMs: Long,
)

/** One link change. `from*`/`to*` hold the previous/new target, each of them an asset id or a group
 *  id (at most one of the two per side) or neither. */
data class SavingsGoalLinkEvent(
    val id: Long,
    val goalId: Long,
    val fromAssetId: String?,
    val toAssetId: String?,
    val kind: SavingsGoalLinkEventKind,
    val timestampEpochMs: Long,
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
)

enum class SavingsGoalLinkEventKind { LINK, RELINK, UNLINK, ASSET_DELETED, GROUP_DELETED }
