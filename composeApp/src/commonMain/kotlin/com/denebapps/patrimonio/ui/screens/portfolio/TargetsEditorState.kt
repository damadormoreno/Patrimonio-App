package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.TOTAL_TARGET_BP
import com.denebapps.patrimonio.domain.calc.TargetsError
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.InvalidAllocationTargetsException
import com.denebapps.patrimonio.domain.repository.InvalidRebalanceToleranceException
import com.denebapps.patrimonio.domain.repository.MAX_REBALANCE_TOLERANCE_BP
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException

// The editor of the targets of a portfolio: what the fields hold while the user types, what that adds up to and what
// can be saved. Pure: the ViewModel keeps the draft and calls these.

internal const val TARGETS_SAVE_FAILED = "No se pudo guardar el objetivo."
internal const val TOLERANCE_ERROR = "Escribe un margen entre 0 y 100 puntos."
private const val BP_PER_PERCENT = 100

/**
 * What the sheet holds for [portfolioId]: the text of each class field, of the tolerance ("Margen (± puntos)"), whether
 * the portfolio [hasTargets] to remove, the [error] of the last save and whether one is [saving].
 */
internal data class TargetsDraft(
    val portfolioId: String,
    val texts: Map<AssetClass, String>,
    val tolerance: String,
    val hasTargets: Boolean,
    val error: String? = null,
    val saving: Boolean = false,
) {
    fun withText(assetClass: AssetClass, text: String) = copy(texts = texts + (assetClass to text), error = null)

    fun withTolerance(text: String) = copy(tolerance = text, error = null)
}

/** One class field of the editor: [text] is what it holds ("12,5"; empty for none). */
data class TargetFieldUi(val assetClass: AssetClass, val label: String, val text: String)

/**
 * The editor sheet. [sumText] reads "Suma: 100 %" and [sumValid] says it is exactly that; [toleranceError] is set while
 * the tolerance is not a number of points from 0 to 100. [canSave] needs both; [canRemove] is whether there are
 * targets to remove; [error] is why the last save was refused.
 */
data class TargetsEditorUi(
    val fields: List<TargetFieldUi>,
    val sumText: String,
    val sumValid: Boolean,
    val toleranceText: String,
    val toleranceError: String?,
    val canSave: Boolean,
    val canRemove: Boolean,
    val saving: Boolean,
    val error: String?,
)

/**
 * The draft a portfolio starts with: its [targets] when it has some, otherwise the current [split] in whole percents
 * (a suggestion to adjust). The tolerance is the one stored with the targets, or [portfolioToleranceBp].
 */
internal fun initialDraft(
    portfolioId: String,
    split: ClassSplit?,
    targets: AllocationTargets?,
    portfolioToleranceBp: Int,
): TargetsDraft {
    val shares = targets?.targetsBp
        ?: roundedPercentSplit(split?.values.orEmpty()).mapValues { it.value * BP_PER_PERCENT }
    return TargetsDraft(
        portfolioId = portfolioId,
        texts = AssetClass.entries.associateWith { assetClass ->
            shares[assetClass]?.takeIf { it > 0 }?.let(::bpInputText).orEmpty()
        },
        tolerance = bpInputText(targets?.toleranceBp ?: portfolioToleranceBp),
        hasTargets = targets != null,
    )
}

/** The targets in the fields in basis points, or `null` when some field is not a share. */
internal fun TargetsDraft.targetsBp(): Map<AssetClass, Int>? {
    val parsed = AssetClass.entries.associateWith { parsePercentToBp(texts[it].orEmpty()) }
    if (parsed.values.any { it == null }) return null
    return parsed.mapNotNull { (assetClass, bp) -> bp?.takeIf { it > 0 }?.let { assetClass to it } }.toMap()
}

/** The tolerance in basis points, or `null` unless it is a number of points from 0 to 100. */
internal fun TargetsDraft.toleranceBp(): Int? =
    parsePercentToBp(tolerance)?.takeIf { tolerance.isNotBlank() && it in 0..MAX_REBALANCE_TOLERANCE_BP }

/** What the sheet shows for [draft]. */
internal fun editorUi(draft: TargetsDraft): TargetsEditorUi {
    val targets = draft.targetsBp()
    val sum = targets?.values?.sum()
    val sumValid = sum == TOTAL_TARGET_BP
    val tolerance = draft.toleranceBp()
    return TargetsEditorUi(
        fields = AssetClass.entries.map { TargetFieldUi(it, assetClassLabel(it), draft.texts[it].orEmpty()) },
        sumText = "Suma: ${sum?.let(::formatBp) ?: NO_FIGURE_TEXT}",
        sumValid = sumValid,
        toleranceText = draft.tolerance,
        toleranceError = TOLERANCE_ERROR.takeIf { tolerance == null },
        canSave = sumValid && tolerance != null && !draft.saving,
        canRemove = draft.hasTargets && !draft.saving,
        saving = draft.saving,
        error = draft.error,
    )
}

/** Why a save was refused, in words for the user. */
internal fun targetsSaveErrorMessage(failure: Exception): String = when (failure) {
    is InvalidAllocationTargetsException -> when (val error = failure.error) {
        is TargetsError.WrongSum -> "Los porcentajes deben sumar 100 %."
        is TargetsError.OutOfRange -> "«${assetClassLabel(error.assetClass)}» debe estar entre 0 y 100 %."
    }
    is InvalidRebalanceToleranceException -> "El margen debe estar entre 0 y 100 puntos."
    is PortfolioNotFoundException -> "Esta cartera ya no existe."
    else -> TARGETS_SAVE_FAILED
}
