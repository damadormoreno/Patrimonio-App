package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.AddProjection
import com.denebapps.patrimonio.domain.calc.FitVerdict
import com.denebapps.patrimonio.domain.calc.HoldingSimilarity
import com.denebapps.patrimonio.domain.calc.InstrumentFit
import com.denebapps.patrimonio.domain.calc.holdingWeights
import com.denebapps.patrimonio.domain.calc.volatility
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
import com.denebapps.patrimonio.domain.repository.PortfolioScopeSeries
import kotlinx.datetime.LocalDate

// The "Encaje con tu cartera" section of the preview: the texts that say how a product that is not in the portfolio yet
// would fit it. The numbers come from `instrumentFit`; this only decides what to say about them.

/** How long the amount must stay unchanged before the projection follows it. */
internal const val AMOUNT_DEBOUNCE_MS = 250L

internal const val NO_HISTORY_IN_PORTFOLIO_TEXT = "Añade productos con histórico para ver cómo encaja."
internal const val CANDIDATE_TOO_SHORT_TEXT = "Este producto no tiene histórico suficiente para compararlo."
internal const val PORTFOLIO_TOO_SHORT_TEXT =
    "Los productos de tu cartera no tienen histórico suficiente para compararlos."

private const val MAX_SIMILAR = 3
private const val WEIGHT_DECIMALS = 1
private const val WARNING_DECIMALS = 1
private const val OWN_PORTFOLIO = "tu cartera"
private const val SHORT_HISTORY_NOTE = "sin histórico suficiente"
private const val ARROW = "→"

/**
 * What to say about the [fit] of the candidate [preview] with the holdings of [scope]. With no verdict it says why
 * there is nothing to compare ([FitSectionUi.message]): no product with history in the portfolio, a short history of
 * the candidate, or of the portfolio's products. The amount field ([FitSectionUi.canProject]) only needs the portfolio
 * to have a value.
 */
internal fun fitSectionUi(
    title: String,
    preview: InstrumentPreviewData,
    scope: PortfolioScopeSeries,
    fit: InstrumentFit,
    today: LocalDate,
): FitSectionUi {
    val valued = try {
        holdingWeights(scope.holdings, scope.rates).count { it.valueEur.minorUnits > 0 }
    } catch (overflow: ArithmeticException) {
        0
    }
    val verdict = verdictUi(fit)
    val message = when {
        verdict != null -> null
        fit.holdings.isEmpty() -> NO_HISTORY_IN_PORTFOLIO_TEXT
        volatility(preview.eurHistory, today) == null -> CANDIDATE_TOO_SHORT_TEXT
        else -> PORTFOLIO_TOO_SHORT_TEXT
    }
    return FitSectionUi(
        title = title,
        message = message,
        verdict = verdict,
        similar = if (verdict == null) emptyList() else fit.holdings.take(MAX_SIMILAR).map(::similarUi),
        canProject = valued > 0,
        projection = fit.projection?.takeIf { valued > 0 }?.let { projectionUi(it, preview, valuedBefore = valued) },
    )
}

private fun similarUi(similarity: HoldingSimilarity): SimilarHoldingUi {
    val correlation = similarity.correlation.correlation
    return SimilarHoldingUi(
        name = similarity.holding.name,
        correlationText = formatCorrelation(correlation),
        note = SHORT_HISTORY_NOTE.takeIf { correlation == null },
    )
}

/**
 * The banner for the correlation with the portfolio as a whole; when that is undefined the verdict comes from the
 * closest holding and says so.
 */
private fun verdictUi(fit: InstrumentFit): FitVerdictUi? {
    val verdict = fit.verdict ?: return null
    val portfolio = fit.portfolio?.takeIf { it.correlation != null }
    val closest = fit.holdings.firstOrNull()
    val (cell, subject) = when {
        portfolio != null -> portfolio to OWN_PORTFOLIO
        closest != null -> closest.correlation to "«${shortName(closest.holding.name)}»"
        else -> return null
    }
    val correlation = formatCorrelation(cell.correlation)
    val (title, detail, tone) = when (verdict) {
        FitVerdict.LOW_DIVERSIFICATION ->
            Triple("Diversifica poco", "Se mueve casi igual que $subject ($correlation).", FitTone.ALERT)
        FitVerdict.MODERATE ->
            Triple("Diversifica moderadamente", "Se parece en parte a $subject ($correlation).", FitTone.NEUTRAL)
        FitVerdict.DIVERSIFIES ->
            Triple("Aporta diversificación", "Se mueve de forma distinta a $subject ($correlation).", FitTone.POSITIVE)
    }
    return FitVerdictUi(title, detail, caption = "Correlación $correlation · ${weeksText(cell.weeks)}", tone = tone)
}

/** The weight, the average cost and risk before and after ("0,15 % → 0,16 %") and the concentration warning. */
private fun projectionUi(projection: AddProjection, preview: InstrumentPreviewData, valuedBefore: Int): ProjectionUi {
    // The product that would be added counts as one more valued product.
    val valuedAfter = valuedBefore + 1
    val terBefore = projection.costBefore.averageTerPercentScaled
    val terAfter = projection.costAfter.averageTerPercentScaled
    val riskBefore = projection.riskBefore.averageRiskLevel
    val riskAfter = projection.riskAfter.averageRiskLevel
    return ProjectionUi(
        weightText = "Pesaría un ${formatPercent(projection.newWeight, signed = false, decimals = WEIGHT_DECIMALS)} " +
            "de la cartera",
        terText = terAfter?.let { beforeAfter(terBefore?.let(::formatAverageTer), formatAverageTer(it)) },
        terCoverage = terAfter?.let { coverageNote(projection.costAfter.coveredHoldings, valuedAfter) },
        riskText = riskAfter?.let { beforeAfter(riskBefore?.let(::averageRiskNumber), averageRiskText(it)) },
        riskCoverage = riskAfter?.let { coverageNote(projection.riskAfter.coveredHoldings, valuedAfter) },
        warning = projection.concentrationWarning?.let {
            "«${shortName(it.name)}» supondría el " +
                "${formatPercent(it.weight, signed = false, decimals = WARNING_DECIMALS)} de tu cartera."
        },
        classMix = classMixUi(projection, preview),
    )
}

/**
 * The class mix before and after ("Renta variable 100 % → 84,5 %"), and the class the product counts under: the one
 * its category or type implies, the same the add form starts with.
 */
private fun classMixUi(projection: AddProjection, preview: InstrumentPreviewData): ClassMixUi {
    val before = projection.classAllocationBefore.associate { it.key to it.share }
    val (unclassified, classified) = projection.classAllocationAfter.partition { it.key == null }
    val rows = (classified + unclassified).map { entry ->
        ClassMixRowUi(
            label = assetClassLabel(entry.key),
            beforeText = before[entry.key]?.let(::classShareText) ?: NO_FIGURE_TEXT,
            afterText = classShareText(entry.share),
        )
    }
    val choice = assetClassChoice(null, preview.fundamentals?.category, preview.candidate.type)
    val note = if (choice.effective != null) {
        "Se contaría como ${choice.text}."
    } else {
        "Se contaría como $UNCLASSIFIED_LABEL: elige su clase al añadirlo."
    }
    return ClassMixUi(rows, note)
}

private fun classShareText(share: Double) = formatPercent(share, signed = false, decimals = WEIGHT_DECIMALS)

/** "0,15 % → 0,16 %"; a missing [before] reads "—". */
private fun beforeAfter(before: String?, after: String) = "${before ?: NO_FIGURE_TEXT} $ARROW $after"
