package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

data class SavingsGoal(
    val id: Long,
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate?,
    val linkedAssetId: String?,
    val lifecycle: SavingsGoalLifecycle,
    val progress: Money,
) {
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

data class SavingsGoalLinkEvent(
    val id: Long,
    val goalId: Long,
    val fromAssetId: String?,
    val toAssetId: String?,
    val kind: SavingsGoalLinkEventKind,
    val timestampEpochMs: Long,
)

enum class SavingsGoalLinkEventKind { LINK, RELINK, UNLINK, ASSET_DELETED }
