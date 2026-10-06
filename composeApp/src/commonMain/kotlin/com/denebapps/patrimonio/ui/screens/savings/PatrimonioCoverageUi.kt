package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.model.SavingsGoalCoverage

/** Coverage state rendered by the Metas de ahorro section (spec: Shared Undercoverage Warning
 *  Only). Intentionally has no per-goal variant — the UI never shows a per-goal covered amount or
 *  priority ordering, only ONE shared warning per kind of link (asset / group). */
sealed interface PatrimonioCoverageUi {
    data object None : PatrimonioCoverageUi

    /** Reservations exceed the balance of a linked asset. */
    data object Warning : PatrimonioCoverageUi

    /** Reservations exceed the balance of a linked group. [convertedAtCurrentRate] is true when the
     *  group (or the goals sharing it) mix currencies, so the balance is a conversion at the current
     *  rate rather than an exact sum. */
    data class GroupWarning(val convertedAtCurrentRate: Boolean) : PatrimonioCoverageUi
}

/** Pure mapping from a single [SavingsGoalCoverage] result (savings-goals-core's
 *  `savingsGoalCoverage`) to the UI's warning state. `Unavailable` and a covered coverage both map to
 *  [PatrimonioCoverageUi.None] — only an undercovered [SavingsGoalCoverage.SharedAsset] maps to
 *  [PatrimonioCoverageUi.Warning] and an undercovered [SavingsGoalCoverage.SharedGroup] to
 *  [PatrimonioCoverageUi.GroupWarning]. [convertedAtCurrentRate] only applies to groups: the coverage
 *  itself does not say whether a conversion happened, the caller knows from the group's members. */
fun coverageWarning(coverage: SavingsGoalCoverage, convertedAtCurrentRate: Boolean = false): PatrimonioCoverageUi =
    when (coverage) {
        SavingsGoalCoverage.Unavailable -> PatrimonioCoverageUi.None
        is SavingsGoalCoverage.SharedAsset -> if (coverage.undercovered) {
            PatrimonioCoverageUi.Warning
        } else {
            PatrimonioCoverageUi.None
        }
        is SavingsGoalCoverage.SharedGroup -> if (coverage.undercovered) {
            PatrimonioCoverageUi.GroupWarning(convertedAtCurrentRate)
        } else {
            PatrimonioCoverageUi.None
        }
    }
