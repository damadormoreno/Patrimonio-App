package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.AllocationEntry
import com.denebapps.patrimonio.domain.calc.CorrelationCell
import com.denebapps.patrimonio.domain.calc.HoldingPriceSeries
import com.denebapps.patrimonio.domain.calc.allocationByBroker
import com.denebapps.patrimonio.domain.calc.allocationByCurrency
import com.denebapps.patrimonio.domain.calc.allocationByType
import com.denebapps.patrimonio.domain.calc.concentration
import com.denebapps.patrimonio.domain.calc.constantCompositionSeries
import com.denebapps.patrimonio.domain.calc.correlationMatrix
import com.denebapps.patrimonio.domain.calc.costSummary
import com.denebapps.patrimonio.domain.calc.eurSeriesById
import com.denebapps.patrimonio.domain.calc.holdingWeights
import com.denebapps.patrimonio.domain.calc.portfolioTotalEur
import com.denebapps.patrimonio.domain.calc.riskSummary
import com.denebapps.patrimonio.domain.calc.toPricePoints
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.ui.components.charts.heatmapLabels
import com.denebapps.patrimonio.ui.components.formatEur
import kotlinx.datetime.LocalDate

// Everything the portfolio detail computes from the data alone: no selected range, no download state. It is the heavy
// part (a correlation per pair of products over years of weekly returns), so the ViewModel runs it off the main thread.

internal const val EVOLUTION_SUBTITLE = "Valor de tus participaciones actuales a precios pasados"
internal const val CORRELATION_EXPLANATION =
    "Correlación de las rentabilidades semanales en euros (últimos 3 años). " +
        "Cerca de 1: se mueven igual; cerca de 0 o negativa: diversifican."
internal const val CORRELATION_NEEDS_TWO =
    "Necesitas al menos dos productos con histórico para ver la correlación."
internal const val PORTFOLIO_NOTE = "Rentabilidades de los productos con tu composición actual, no de tus compras."

private const val SHARE_DECIMALS = 1
private const val WARNING_DECIMALS = 0

/** The raw data of the screen. [histories] holds, per quoted holding id, its price history in its own currency. */
internal data class PortfolioInputs(
    val holdings: List<PortfolioHolding>,
    val histories: Map<String, List<PricePoint>>,
    val fxHistory: FxHistory,
    val rates: FxRates,
)

/**
 * What [analyzePortfolio] found. [series] is the constant-composition value as EUR price points (empty when there is
 * none), with the [yearReturns] and [risk] of the same series. [hasQuoted] says whether any holding is quoted, which
 * decides what the chart area says while the series is missing.
 */
internal data class PortfolioAnalysis(
    val header: PortfolioHeaderUi,
    val hasQuoted: Boolean,
    val series: List<PricePoint>,
    val excludedText: String?,
    val yearReturns: List<YearReturnUi>,
    val risk: RiskUi?,
    val correlation: CorrelationSectionUi?,
    val allocations: List<AllocationGroupUi>,
    val costsRisk: CostsRiskUi?,
    val concentration: ConcentrationUi?,
)

internal fun analyzePortfolio(inputs: PortfolioInputs, today: LocalDate): PortfolioAnalysis {
    val holdings = inputs.holdings
    val eurSeries = eurSeriesById(inputs.histories, inputs.fxHistory)
    val composition = try {
        constantCompositionSeries(holdings, eurSeries)
    } catch (overflow: ArithmeticException) {
        null
    }
    val series = try {
        composition?.toPricePoints().orEmpty()
    } catch (overflow: ArithmeticException) {
        emptyList()
    }
    val charted = series.size >= MIN_CHART_POINTS
    val names = holdings.associate { it.id to it.name }
    val excluded = composition?.excludedHoldingIds.orEmpty().mapNotNull { names[it] }
    return PortfolioAnalysis(
        header = header(holdings, inputs.rates),
        hasQuoted = holdings.any { it.valuation is HoldingValuation.Quoted },
        series = series,
        excludedText = excludedText(excluded),
        yearReturns = if (charted) yearReturnsUi(series, today) else emptyList(),
        risk = if (charted) riskUi(series, today) else null,
        correlation = correlationSection(holdings, eurSeries, today),
        allocations = allocations(holdings, inputs.rates),
        costsRisk = costsRisk(holdings, inputs.rates),
        concentration = concentrationUi(holdings, inputs.rates),
    )
}

private fun header(holdings: List<PortfolioHolding>, rates: FxRates): PortfolioHeaderUi {
    val quoted = holdings.count { it.valuation is HoldingValuation.Quoted }
    return PortfolioHeaderUi(
        totalText = formatEur(portfolioTotalEur(holdings, rates)),
        productsText = countLabel(holdings.size),
        mixText = holdingsMixText(quoted = quoted, manual = holdings.size - quoted),
    )
}

// ---- correlation ------------------------------------------------------------------------------------------

private fun correlationSection(
    holdings: List<PortfolioHolding>,
    eurSeries: Map<String, List<PricePoint>>,
    today: LocalDate,
): CorrelationSectionUi? {
    val quoted = holdings.filter { it.valuation is HoldingValuation.Quoted }
    if (quoted.isEmpty()) return null
    val withSeries = quoted.filter { !eurSeries[it.id].isNullOrEmpty() }
    val matrix = correlationMatrix(withSeries.map { HoldingPriceSeries(it.id, eurSeries.getValue(it.id)) }, today)
    // A product whose pairs are all undefined (too little common history) has nothing to show in the matrix.
    val indices = withSeries.indices
    val kept = indices.filter { i -> indices.any { j -> j != i && matrix[i, j].correlation != null } }
    if (kept.size < 2) return CorrelationSectionUi(message = CORRELATION_NEEDS_TWO)

    val names = kept.map { withSeries[it].name }
    val cells = kept.indices.map { row ->
        kept.indices.map { column -> correlationCell(names, row, column, matrix[kept[row], kept[column]]) }
    }
    val keptIds = kept.map { withSeries[it].id }.toSet()
    return CorrelationSectionUi(
        matrix = CorrelationUi(
            labels = heatmapLabels(names),
            names = names,
            cells = cells,
            mostSimilarText = mostSimilarText(names, cells),
            explanation = CORRELATION_EXPLANATION,
        ),
        omittedText = quoted.filter { it.id !in keptIds }.map { it.name }.takeIf { it.isNotEmpty() }
            ?.let { "Sin histórico suficiente: ${namesList(it)}" },
    )
}

private fun correlationCell(names: List<String>, row: Int, column: Int, cell: CorrelationCell): CorrelationCellUi {
    val valueText = formatCorrelation(cell.correlation)
    if (row == column) return CorrelationCellUi(valueText, cell.correlation, detailText = null)
    val pair = "${names[row]} y ${names[column]}"
    val detail = if (cell.correlation == null) {
        "$pair · sin datos suficientes · ${weeksText(cell.weeks)}"
    } else {
        "$pair · ${weeksText(cell.weeks)}"
    }
    return CorrelationCellUi(valueText, cell.correlation, detail)
}

/** "Más parecidos: A y B (0,96)" for the pair with the highest correlation; the first of equals. */
private fun mostSimilarText(names: List<String>, cells: List<List<CorrelationCellUi>>): String? {
    var best: Triple<Int, Int, Double>? = null
    for (row in names.indices) {
        for (column in row + 1 until names.size) {
            val value = cells[row][column].value ?: continue
            if (best == null || value > best.third) best = Triple(row, column, value)
        }
    }
    return best?.let { (row, column, value) ->
        "Más parecidos: ${shortName(names[row])} y ${shortName(names[column])} (${formatCorrelation(value)})"
    }
}

// ---- allocation, costs, concentration ---------------------------------------------------------------------

private fun allocations(holdings: List<PortfolioHolding>, rates: FxRates): List<AllocationGroupUi> {
    val byType = allocationByType(holdings, rates)
    if (byType.isEmpty()) return emptyList()
    val byBroker = allocationByBroker(holdings, rates)
    return buildList {
        add(AllocationGroupUi("Por tipo", byType.toRows { instrumentTypeLabel(it) }))
        // A split where nothing has a bank or broker says nothing.
        if (byBroker.any { it.key != null }) {
            add(AllocationGroupUi("Por banco o bróker", byBroker.toRows { it ?: NO_BROKER_LABEL }))
        }
        add(AllocationGroupUi("Por divisa", allocationByCurrency(holdings, rates).toRows { it.code }))
    }
}

private const val NO_BROKER_LABEL = "Sin banco"

private fun <K> List<AllocationEntry<K>>.toRows(label: (K) -> String): List<AllocationRowUi> = map {
    AllocationRowUi(
        label = label(it.key),
        amountText = formatEur(it.valueEur),
        shareText = formatPercent(it.share, signed = false, decimals = SHARE_DECIMALS),
        share = it.share.toFloat(),
    )
}

private fun costsRisk(holdings: List<PortfolioHolding>, rates: FxRates): CostsRiskUi? {
    val valued = holdingWeights(holdings, rates).size
    val cost = costSummary(holdings, rates)
    val risk = riskSummary(holdings, rates)
    val terText = cost.averageTerPercentScaled?.let(::formatPercentScaled)
    val riskText = risk.averageRiskLevel?.let(::averageRiskText)
    if (terText == null && riskText == null) return null
    return CostsRiskUi(
        terText = terText,
        yearlyCostText = terText?.let { "≈ ${formatEur(cost.yearlyCost)}/año" },
        terCoverage = terText?.let { coverageNote(cost.coveredHoldings, valued) },
        riskText = riskText,
        riskCoverage = riskText?.let { coverageNote(risk.coveredHoldings, valued) },
    )
}

private fun concentrationUi(holdings: List<PortfolioHolding>, rates: FxRates): ConcentrationUi? {
    val largest = concentration(holdings, rates) ?: return null
    // With a single product "it is all of your portfolio" is not news.
    val warns = largest.exceedsThreshold && holdingWeights(holdings, rates).size > 1
    return ConcentrationUi(
        name = largest.holding.name,
        weightText = formatPercent(largest.weight, signed = false, decimals = SHARE_DECIMALS),
        warning = if (warns) {
            "«${shortName(largest.holding.name)}» supone el " +
                "${formatPercent(largest.weight, signed = false, decimals = WARNING_DECIMALS)} de tu cartera."
        } else {
            null
        },
    )
}
