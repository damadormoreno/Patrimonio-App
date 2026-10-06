package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.model.SavingsGoalCoverage

/** Coverage state rendered by the Metas de ahorro section (spec: Shared Undercoverage Warning
 *  Only). Intentionally has no per-goal variant — the UI never shows a per-goal covered amount or
 *  priority ordering, only ONE shared warning across every currently-linked goal. */
sealed interface PatrimonioCoverageUi {
    data object None : PatrimonioCoverageUi

    data object Warning : PatrimonioCoverageUi
}

/** Pure mapping from a single [SavingsGoalCoverage] result (savings-goals-core's
 *  `savingsGoalCoverage`) to the UI's binary warning state. `Unavailable` and a covered
 *  [SavingsGoalCoverage.SharedAsset] both map to [PatrimonioCoverageUi.None] — only
 *  [SavingsGoalCoverage.SharedAsset.undercovered] maps to [PatrimonioCoverageUi.Warning]. */
fun coverageWarning(coverage: SavingsGoalCoverage): PatrimonioCoverageUi = when (coverage) {
    SavingsGoalCoverage.Unavailable -> PatrimonioCoverageUi.None
    is SavingsGoalCoverage.SharedAsset -> if (coverage.undercovered) {
        PatrimonioCoverageUi.Warning
    } else {
        PatrimonioCoverageUi.None
    }
}
