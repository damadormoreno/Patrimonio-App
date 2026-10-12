package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.ContributionShare
import com.denebapps.patrimonio.domain.calc.RebalanceStatus
import com.denebapps.patrimonio.domain.calc.allocationByAssetClass
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.rebalanceStatus
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.ui.components.formatEur

// What the portfolio detail shows of the targets of a portfolio: how it stands against them, where to put new money
// and what a given contribution would do. The math is `rebalanceStatus`; this turns it into texts.

internal const val TARGETS_TITLE = "Objetivo de reparto"
internal const val TARGETS_EMPTY_TEXT =
    "Define qué porcentaje quieres en cada clase de activo y te avisaremos cuando la cartera se desvíe."
internal const val TARGETS_PICK_PORTFOLIO_TEXT = "Elige una cartera para definir su objetivo"
internal const val TARGETS_DEFINE_LABEL = "Definir objetivo"
internal const val TARGETS_EDIT_LABEL = "Editar"
internal const val CALCULATOR_LABEL = "¿Cuánto vas a aportar?"
internal const val CALCULATOR_ERROR = "Escribe un importe válido."

private const val WITHIN_BAND_TEXT = "Dentro del margen"
private const val OUT_OF_BAND_TEXT = "Fuera del margen"
private const val NOTHING_TO_COMPARE_TEXT = "Aún no hay productos con clase para comparar"

/** The classified value of a portfolio per class, and the holdings that have no class (left out of every share). */
internal data class ClassSplit(
    val values: Map<AssetClass, Money>,
    val unclassified: Money,
    val unclassifiedCount: Int,
)

/**
 * The split of [holdings] by effective asset class, from the same source as "Reparto por clase de activo". `null` when
 * a total does not fit in a [Long].
 */
internal fun classSplitOf(holdings: List<PortfolioHolding>, rates: FxRates): ClassSplit? = try {
    val entries = allocationByAssetClass(holdings, rates)
    ClassSplit(
        values = entries.mapNotNull { entry -> entry.key?.let { it to entry.valueEur } }.toMap(),
        unclassified = entries.firstOrNull { it.key == null }?.valueEur ?: Money.ZERO,
        unclassifiedCount = holdings.count { holding ->
            holding.effectiveAssetClass() == null && (holding.value()?.amount?.minorUnits ?: 0L) > 0L
        },
    )
} catch (overflow: ArithmeticException) {
    null
}

/** How [split] stands against [targets]; `null` when the figures do not fit or the targets are not valid. */
internal fun rebalanceStatusOf(split: ClassSplit, targets: AllocationTargets): RebalanceStatus? = try {
    rebalanceStatus(split.values, split.unclassified, split.unclassifiedCount, targets.targetsBp, targets.toleranceBp)
} catch (invalid: IllegalArgumentException) {
    null
} catch (overflow: ArithmeticException) {
    null
}

/** [status] of the portfolio [holdings] against [targets], or `null` as for [rebalanceStatusOf]. */
internal fun rebalanceStatusFor(
    holdings: List<PortfolioHolding>,
    rates: FxRates,
    targets: AllocationTargets,
): RebalanceStatus? = classSplitOf(holdings, rates)?.let { rebalanceStatusOf(it, targets) }

/** A class and what to put in it, as "Renta fija: 1.200,00 €". */
data class ContributionLineUi(val assetClass: AssetClass, val label: String, val amountText: String) {
    val text: String get() = "$label: $amountText"
}

/** Where to put new money to get back on target without selling: the [headline] and a line per class that needs it. */
data class SuggestionUi(val headline: String, val lines: List<ContributionLineUi>)

/**
 * One class against its target: [detailText] reads "Actual 72 % · Objetivo 80 %", [driftText] "−8 pts" and
 * [outOfBand] colors it. The fractions (0 to 1) place the actual share and the target on the bar.
 */
data class TargetRowUi(
    val assetClass: AssetClass,
    val label: String,
    val detailText: String,
    val driftText: String,
    val outOfBand: Boolean,
    val actualFraction: Float,
    val targetFraction: Float,
)

/**
 * The little calculator: what the user typed ([amountText]), where it would go ([shares], only the classes that get
 * something) and [error] while the text is not an amount.
 */
data class CalculatorUi(val amountText: String, val shares: List<ContributionLineUi>, val error: String?)

/** The "Objetivo de reparto" section of the portfolio detail. */
sealed interface TargetSectionUi {
    /** All the portfolios together: targets are per portfolio, so the user is asked to pick one. */
    data class PickPortfolio(val message: String) : TargetSectionUi

    /** A portfolio with no targets. */
    data class Empty(val message: String) : TargetSectionUi

    /**
     * A portfolio with targets. [rows] has the classes held or targeted; [statusText] says whether it is within the
     * band ([toleranceText]); [suggestion] only exists out of band and when money can fix it; [onlySellingNotes] name
     * the classes out of band that have no target (money cannot reduce them) and [unclassifiedNote] the products
     * without a class, which count for nothing.
     */
    data class Defined(
        val rows: List<TargetRowUi>,
        val toleranceText: String,
        val statusText: String,
        val outOfBand: Boolean,
        val suggestion: SuggestionUi?,
        val onlySellingNotes: List<String>,
        val unclassifiedNote: String?,
        val calculator: CalculatorUi,
    ) : TargetSectionUi
}

private const val BP_FRACTION = 10_000f

/** The section for a portfolio that has targets; [calculatorText] is what the user typed in the calculator. */
internal fun definedSection(status: RebalanceStatus, calculatorText: String): TargetSectionUi.Defined {
    val hasValue = status.totalValue.minorUnits > 0
    val outOfBand = status.needsRebalancing
    val outOfBandSelling = status.classes.filter { it.targetBp == 0 && it.outOfBand }.map { it.assetClass }
    return TargetSectionUi.Defined(
        rows = status.classes.map { item ->
            TargetRowUi(
                assetClass = item.assetClass,
                label = assetClassLabel(item.assetClass),
                detailText = "Actual ${formatBp(item.weightBp)} · Objetivo ${formatBp(item.targetBp)}",
                driftText = formatPoints(item.driftBp),
                outOfBand = item.outOfBand,
                actualFraction = item.weightBp / BP_FRACTION,
                targetFraction = item.targetBp / BP_FRACTION,
            )
        },
        toleranceText = "Margen ±${formatPoints(status.toleranceBp, signed = false)}",
        statusText = when {
            !hasValue -> NOTHING_TO_COMPARE_TEXT
            outOfBand -> OUT_OF_BAND_TEXT
            else -> WITHIN_BAND_TEXT
        },
        outOfBand = outOfBand,
        suggestion = suggestionOf(status).takeIf { outOfBand },
        onlySellingNotes = outOfBandSelling.map { onlySellingNote(it) },
        unclassifiedNote = unclassifiedNote(status.unclassifiedCount),
        calculator = calculatorOf(status, calculatorText),
    )
}

private fun onlySellingNote(assetClass: AssetClass): String =
    "${assetClassLabel(assetClass)} no tiene objetivo: solo se corrige vendiendo."

private fun unclassifiedNote(count: Int): String? = when (count) {
    0 -> null
    1 -> "1 producto sin clase no cuenta."
    else -> "$count productos sin clase no cuentan."
}

/** The no-sell contribution of [status] as a sentence and a line per class; `null` when nothing needs adding. */
internal fun suggestionOf(status: RebalanceStatus): SuggestionUi? {
    if (status.contribution.minorUnits <= 0) return null
    return SuggestionUi(
        headline = "Para volver al objetivo sin vender: aporta ${formatEur(status.contribution)}",
        lines = status.contributionSplit.toLines(),
    )
}

private fun calculatorOf(status: RebalanceStatus, text: String): CalculatorUi {
    if (text.isBlank()) return CalculatorUi(text, emptyList(), null)
    val minor = parseAmountToMinor(text, Currency.EUR)
        ?: return CalculatorUi(text, emptyList(), CALCULATOR_ERROR)
    val shares = try {
        status.splitContribution(Money(minor)).toLines()
    } catch (overflow: ArithmeticException) {
        return CalculatorUi(text, emptyList(), CALCULATOR_ERROR)
    }
    return CalculatorUi(text, shares, null)
}

private fun List<ContributionShare>.toLines(): List<ContributionLineUi> = filter { it.amount.minorUnits > 0 }
    .map { ContributionLineUi(it.assetClass, assetClassLabel(it.assetClass), formatEur(it.amount)) }
