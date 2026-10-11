package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.LocalDate

// How a product that is not in the portfolio yet would fit it. Correlations and weights are `Double` ratios (statistics
// to display); the amounts and the cost figures stay exact `Money` / fixed point. A result that cannot be computed
// (short history, nothing to compare with, an amount that overflows) is `null`, never an exception.

/** A correlation at or above this adds almost no diversification (0.9). */
const val LOW_DIVERSIFICATION_CORRELATION = 0.9

/** Below [LOW_DIVERSIFICATION_CORRELATION] and at or above this the product diversifies moderately (0.5). */
const val MODERATE_DIVERSIFICATION_CORRELATION = 0.5

/** The id of the stand-in holding that represents the candidate once it is added; no real holding can have it. */
private const val CANDIDATE_HOLDING_ID = "instrument-preview-candidate"
private const val CANDIDATE_PORTFOLIO_ID = "instrument-preview"

/** What a correlation says about adding a product to the portfolio. */
enum class FitVerdict { LOW_DIVERSIFICATION, MODERATE, DIVERSIFIES }

/** The [FitVerdict] of a [correlation]: 0.9 and above, from 0.5 up to 0.9, below 0.5. */
fun fitVerdict(correlation: Double): FitVerdict = when {
    correlation >= LOW_DIVERSIFICATION_CORRELATION -> FitVerdict.LOW_DIVERSIFICATION
    correlation >= MODERATE_DIVERSIFICATION_CORRELATION -> FitVerdict.MODERATE
    else -> FitVerdict.DIVERSIFIES
}

/** How alike the candidate and the quoted [holding] move: their weekly-return [correlation] (with its common weeks). */
data class HoldingSimilarity(val holding: PortfolioHolding, val correlation: CorrelationCell)

/** The product that would weigh more than [CONCENTRATION_THRESHOLD] of the portfolio: [name] at [weight]. */
data class ConcentrationWarning(val name: String, val weight: Double, val isCandidate: Boolean)

/**
 * The portfolio after adding [amount] (EUR) of the candidate. [newWeight] is the share of the candidate in the new
 * total; [portfolioTotal] is the value before adding. The cost and risk summaries have the candidate's fundamentals
 * counted when it has any (its coverage shows when it has not). [concentrationWarning] is set when the largest product
 * afterwards is above [CONCENTRATION_THRESHOLD] (the first product of an empty portfolio is not a warning).
 */
data class AddProjection(
    val amount: Money,
    val newWeight: Double,
    val portfolioTotal: Money,
    val costBefore: CostSummary,
    val costAfter: CostSummary,
    val riskBefore: RiskSummary,
    val riskAfter: RiskSummary,
    val concentrationWarning: ConcentrationWarning?,
)

/**
 * The fit of the candidate: [holdings] holds its similarity to every quoted holding that has a history, most similar
 * first and undefined correlations last; [portfolio] its correlation with the constant-composition series of those
 * holdings (`null` when there is none to compare with); [verdict] what that says (`null` when no correlation could be
 * computed); [projection] the portfolio after adding an amount (`null` without a positive amount).
 */
data class InstrumentFit(
    val holdings: List<HoldingSimilarity>,
    val portfolio: CorrelationCell?,
    val verdict: FitVerdict?,
    val projection: AddProjection?,
)

/**
 * How [candidate] fits the [holdings] of one portfolio scope. [eurSeriesById] holds the EUR price history of each
 * quoted holding; correlations follow the rules of [correlationMatrix] (weekly returns in EUR, the last
 * [ANALYSIS_WINDOW_YEARS] years or all of them, at least [MIN_WEEKLY_RETURNS] common weeks). The verdict comes from the
 * correlation with the portfolio and, when that is undefined, from the highest one with a single holding. With a
 * positive [amount] (EUR) the result also projects the portfolio after adding it.
 */
fun instrumentFit(
    candidate: InstrumentPreviewData,
    holdings: List<PortfolioHolding>,
    eurSeriesById: Map<String, List<PricePoint>>,
    rates: FxRates,
    today: LocalDate,
    amount: Money?,
): InstrumentFit {
    val candidateReturns = weeklyReturnsByWeek(candidate.eurHistory)
    val similarities = holdings
        .filter { it.valuation is HoldingValuation.Quoted && !eurSeriesById[it.id].isNullOrEmpty() }
        .map { holding ->
            HoldingSimilarity(
                holding,
                pairCorrelation(candidateReturns, weeklyReturnsByWeek(eurSeriesById.getValue(holding.id)), today),
            )
        }
        .sortedByDescending { it.correlation.correlation ?: Double.NEGATIVE_INFINITY }
    val portfolio = portfolioCorrelation(candidateReturns, holdings, eurSeriesById, today)
    val basis = portfolio?.correlation ?: similarities.firstOrNull()?.correlation?.correlation
    return InstrumentFit(
        holdings = similarities,
        portfolio = portfolio,
        verdict = basis?.let(::fitVerdict),
        projection = amount?.takeIf { it.minorUnits > 0 }?.let { projectAdd(candidate, holdings, rates, it) },
    )
}

/** The correlation with the constant-composition series of the holdings; `null` when no holding has a history. */
private fun portfolioCorrelation(
    candidateReturns: Map<LocalDate, Double>,
    holdings: List<PortfolioHolding>,
    eurSeriesById: Map<String, List<PricePoint>>,
    today: LocalDate,
): CorrelationCell? = try {
    val composition = constantCompositionSeries(holdings, eurSeriesById)
    if (composition.includedHoldingIds.isEmpty()) {
        null
    } else {
        pairCorrelation(candidateReturns, weeklyReturnsByWeek(composition.toPricePoints()), today)
    }
} catch (overflow: ArithmeticException) {
    null
}

/**
 * The portfolio with [amount] of the candidate added. The candidate stands in as a holding worth [amount] with its own
 * fundamentals, so the weights, cost, risk and concentration follow exactly the rules of the portfolio screens.
 */
private fun projectAdd(
    candidate: InstrumentPreviewData,
    holdings: List<PortfolioHolding>,
    rates: FxRates,
    amount: Money,
): AddProjection? = try {
    val product = candidate.candidate
    val standIn = PortfolioHolding(
        id = CANDIDATE_HOLDING_ID,
        portfolioId = CANDIDATE_PORTFOLIO_ID,
        name = product.name.ifBlank { product.ref.code },
        type = product.type,
        isin = product.isin,
        ticker = product.ticker,
        valuation = HoldingValuation.Manual(CurrencyAmount(amount, Currency.EUR), valuedAtEpochMs = 0L),
        fundamentals = candidate.fundamentals,
    )
    val after = holdings + standIn
    val weights = holdingWeights(after, rates)
    val largest = weights.first()
    AddProjection(
        amount = amount,
        newWeight = weights.first { it.holding.id == CANDIDATE_HOLDING_ID }.weight,
        portfolioTotal = portfolioTotalEur(holdings, rates),
        costBefore = costSummary(holdings, rates),
        costAfter = costSummary(after, rates),
        riskBefore = riskSummary(holdings, rates),
        riskAfter = riskSummary(after, rates),
        // A portfolio of one product being all of it is not news.
        concentrationWarning = if (weights.size > 1 && largest.weight > CONCENTRATION_THRESHOLD) {
            ConcentrationWarning(largest.holding.name, largest.weight, largest.holding.id == CANDIDATE_HOLDING_ID)
        } else {
            null
        },
    )
} catch (overflow: ArithmeticException) {
    null
}
