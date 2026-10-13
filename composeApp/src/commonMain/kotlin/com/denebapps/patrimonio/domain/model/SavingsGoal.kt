package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * A goal follows any mix of accounts ([linkedAssetIds]), portfolio products ([linkedHoldingIds]) and persisted
 * account groups ([linkedGroupIds]), several of each; with none of the three it is unlinked and keeps its own
 * allocation ledger. The balance a linked goal shows counts every account and product once, however many of the
 * links reach it (see `trackedBalance`). The builtin "all accounts" group is never linkable.
 */
data class SavingsGoal(
    val id: String,
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate?,
    val linkedAssetIds: Set<String>,
    val lifecycle: SavingsGoalLifecycle,
    val progress: Money,
    val linkedHoldingIds: Set<String> = emptySet(),
    val linkedGroupIds: Set<String> = emptySet(),
) {
    /** True when the goal follows at least one account, product or group. */
    val hasLinks: Boolean
        get() = linkedAssetIds.isNotEmpty() || linkedHoldingIds.isNotEmpty() || linkedGroupIds.isNotEmpty()

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

/** One link change. `from*`/`to*` hold the previous/new target, each of them an asset id, a product (holding) id or
 *  a group id (at most one of the three per side) or none. A goal following several accounts, products or groups
 *  records one LINK/UNLINK per target added or removed. RELINK only appears in history written before a goal could
 *  follow several groups (a group swapped for another). */
data class SavingsGoalLinkEvent(
    val id: Long,
    val goalId: String,
    val fromAssetId: String?,
    val toAssetId: String?,
    val kind: SavingsGoalLinkEventKind,
    val timestampEpochMs: Long,
    val fromGroupId: String? = null,
    val toGroupId: String? = null,
    val fromHoldingId: String? = null,
    val toHoldingId: String? = null,
)

enum class SavingsGoalLinkEventKind { LINK, RELINK, UNLINK, ASSET_DELETED, GROUP_DELETED, HOLDING_DELETED }
