package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * A goal follows any number of accounts ([linkedAssetIds]) XOR one persisted account group
 * ([linkedGroupId]) XOR nothing. The builtin "all accounts" group is never linkable.
 */
data class SavingsGoal(
    val id: String,
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate?,
    val linkedAssetIds: Set<String>,
    val lifecycle: SavingsGoalLifecycle,
    val progress: Money,
    val linkedGroupId: String? = null,
) {
    init {
        require(linkedAssetIds.isEmpty() || linkedGroupId == null) {
            "Savings goal $id cannot be linked to assets and a group at the same time"
        }
    }

    /** Reaching the target is derived independently from the explicit lifecycle. */
    val targetReached: Boolean
        get() = progress >= target.amount
}

enum class SavingsGoalLifecycle { OPEN, CLOSED, CANCELLED }

data class SavingsGoalAllocationEvent(
    val id: Long,
    val goalId: String,
    val delta: Money,
    val timestampEpochMs: Long,
)

/** One link change. `from*`/`to*` hold the previous/new target, each of them an asset id or a group
 *  id (at most one of the two per side) or neither. A goal following several accounts records one
 *  LINK/UNLINK per account added or removed. */
data class SavingsGoalLinkEvent(
    val id: Long,
    val goalId: String,
    val fromAssetId: String?,
    val toAssetId: String?,
    val kind: SavingsGoalLinkEventKind,
    val timestampEpochMs: Long,
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
)

enum class SavingsGoalLinkEventKind { LINK, RELINK, UNLINK, ASSET_DELETED, GROUP_DELETED }
