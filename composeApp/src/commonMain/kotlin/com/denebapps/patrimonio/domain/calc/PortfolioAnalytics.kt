package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PERCENT_SCALE
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.domain.model.Units
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.math.roundToLong
import kotlin.math.sqrt

// Analytics over the whole portfolio. Amounts (values, costs, allocation) are exact `Money` in EUR, summed with
// `checkedAdd`; shares, weights, coverage, correlations and the average risk are `Double` ratios for display.

/** The longest gap, in days, a price is carried forward to a later date when composing the portfolio series. */
const val MAX_FORWARD_FILL_DAYS = 7

/** A single holding weighing more than this share of the portfolio is flagged as a concentration risk (40 %). */
const val CONCENTRATION_THRESHOLD = 0.40

/** Below this variance (of weekly returns) a series is considered constant and has no defined correlation. */
private const val MIN_VARIANCE_SUM = 1e-18

/** One price per `PRICE_SCALE`-scaled unit of a EUR cent: `10^6 / 10^2`. */
private const val CENT_TO_PRICE_SCALE = 10_000L

/** `100 * PERCENT_SCALE`: a percent-scaled rate divided by this is the fraction to multiply an amount by. */
private const val PERCENT_TO_FRACTION = 100L * PERCENT_SCALE

// ---- EUR conversion of a series ---------------------------------------------------------------------------

/**
 * [points] converted to EUR with the rate [fx] has for each point's date (the nearest previous one within its
 * fallback window; EUR is the identity). A point without a rate is dropped, never converted at an invented rate.
 */
fun toEurSeries(points: List<PricePoint>, fx: FxHistory): List<PricePoint> = points.mapNotNull { point ->
    val currency = point.price.currency
    if (currency == Currency.EUR) return@mapNotNull point
    val rate = fx.rateOn(currency, point.date) ?: return@mapNotNull null
    PricePoint(point.date, Price(mulDivHalfEven(point.price.scaled, rate, RATE_SCALE), Currency.EUR))
}

// ---- correlation ------------------------------------------------------------------------------------------

/** The EUR price history of the holding identified by [holdingId]. */
data class HoldingPriceSeries(val holdingId: String, val points: List<PricePoint>)

/** The Pearson [correlation] (-1 to 1, a `Double`) of one pair over [weeks] common weekly returns, `null` if undefined. */
data class CorrelationCell(val correlation: Double?, val weeks: Int)

/** Correlation of every pair of [holdingIds] (in input order); [cells] is square and symmetric. */
data class CorrelationMatrix(val holdingIds: List<String>, val cells: List<List<CorrelationCell>>) {
    operator fun get(row: Int, column: Int): CorrelationCell = cells[row][column]
}

/**
 * Pearson correlation of the weekly returns of every pair of [series] over the weeks they have in common: the last
 * [ANALYSIS_WINDOW_YEARS] years before [today] or, if that holds fewer than [MIN_WEEKLY_RETURNS] common weeks, all of
 * them. A pair with fewer than [MIN_WEEKLY_RETURNS] common weeks, or with a constant series, has a `null` correlation
 * (its [CorrelationCell.weeks] still tells how many it had). The diagonal is exactly 1.0.
 */
fun correlationMatrix(series: List<HoldingPriceSeries>, today: LocalDate): CorrelationMatrix {
    val returns = series.map { weeklyReturnsByWeek(it.points) }
    val size = series.size
    val cells = List(size) { MutableList(size) { CorrelationCell(null, 0) } }
    for (i in 0 until size) {
        cells[i][i] = CorrelationCell(1.0, recentOrAll(returns[i].keys.toList(), today) { it }.size)
        for (j in i + 1 until size) {
            cells[i][j] = pairCorrelation(returns[i], returns[j], today)
            cells[j][i] = cells[i][j]
        }
    }
    return CorrelationMatrix(series.map { it.holdingId }, cells)
}

/** The weekly returns of [points] by the Monday of their week, to correlate two series on their common weeks. */
internal fun weeklyReturnsByWeek(points: List<PricePoint>): Map<LocalDate, Double> =
    weeklyReturns(weeklyCloses(points)).associate { it.weekStart to it.ratio }

/** The correlation of two [weeklyReturnsByWeek] over their common weeks, under the rules of [correlationMatrix]. */
internal fun pairCorrelation(a: Map<LocalDate, Double>, b: Map<LocalDate, Double>, today: LocalDate): CorrelationCell {
    val weeks = recentOrAll(a.keys.filter { it in b }, today) { it }
    val correlation = if (weeks.size < MIN_WEEKLY_RETURNS) {
        null
    } else {
        pearson(weeks.map { a.getValue(it) }, weeks.map { b.getValue(it) })
    }
    return CorrelationCell(correlation, weeks.size)
}

private fun pearson(x: List<Double>, y: List<Double>): Double? {
    val meanX = x.average()
    val meanY = y.average()
    var covariance = 0.0
    var varianceX = 0.0
    var varianceY = 0.0
    for (k in x.indices) {
        val dx = x[k] - meanX
        val dy = y[k] - meanY
        covariance += dx * dy
        varianceX += dx * dx
        varianceY += dy * dy
    }
    if (varianceX < MIN_VARIANCE_SUM || varianceY < MIN_VARIANCE_SUM) return null
    return (covariance / sqrt(varianceX * varianceY)).coerceIn(-1.0, 1.0)
}

// ---- constant-composition value series --------------------------------------------------------------------

/** The portfolio worth [value] (EUR) on [date]. */
data class PortfolioValuePoint(val date: LocalDate, val value: Money)

/**
 * What today's quoted holdings would have been worth on each past [values] date had their units never changed.
 * [includedHoldingIds] are the holdings summed, [excludedHoldingIds] the ones left out (manual valuations and quoted
 * holdings without a cached history), both in input order.
 */
data class ConstantCompositionSeries(
    val values: List<PortfolioValuePoint>,
    val includedHoldingIds: List<String>,
    val excludedHoldingIds: List<String>,
)

/**
 * `sum(units_i x EUR price_i)` of the quoted holdings with a series in [eurSeriesById], on every date from the first
 * one on which all of them have a price. A holding's latest price is carried forward at most [MAX_FORWARD_FILL_DAYS]
 * days; when any holding is staler than that, the date is skipped. Every series must be in EUR and ascending.
 *
 * @throws IllegalArgumentException when a price is not in EUR.
 * @throws ArithmeticException when a value overflows a [Long].
 */
fun constantCompositionSeries(
    holdings: List<PortfolioHolding>,
    eurSeriesById: Map<String, List<PricePoint>>,
): ConstantCompositionSeries {
    val included = mutableListOf<Triple<String, Units, List<PricePoint>>>()
    val excluded = mutableListOf<String>()
    for (holding in holdings) {
        val quoted = holding.valuation as? HoldingValuation.Quoted
        val series = eurSeriesById[holding.id]
        if (quoted != null && !series.isNullOrEmpty()) {
            require(series.all { it.price.currency == Currency.EUR }) { "series of ${holding.id} must be in EUR" }
            included += Triple(holding.id, quoted.units, series)
        } else {
            excluded += holding.id
        }
    }
    val dates = included.flatMap { (_, _, series) -> series.map { it.date } }.distinct().sorted()
    val cursors = IntArray(included.size) { -1 }
    val values = mutableListOf<PortfolioValuePoint>()
    for (date in dates) {
        val oldestAllowed = date.minus(MAX_FORWARD_FILL_DAYS, DateTimeUnit.DAY)
        var total = 0L
        var complete = true
        for ((index, entry) in included.withIndex()) {
            val series = entry.third
            while (cursors[index] + 1 < series.size && series[cursors[index] + 1].date <= date) cursors[index]++
            val price = series.getOrNull(cursors[index])
            if (price == null || price.date < oldestAllowed) {
                complete = false
                break
            }
            total = checkedAdd(total, quotedValue(entry.second, price.price).amount.minorUnits)
        }
        if (complete) values += PortfolioValuePoint(date, Money(total))
    }
    return ConstantCompositionSeries(values, included.map { it.first }, excluded)
}

/**
 * The value series as EUR price points (each value as a `Price` with 6 decimals), to reuse the position analytics
 * ([calendarYearReturns], [volatility], [maxDrawdown], [rangeChange]) on it.
 *
 * @throws ArithmeticException when a value does not fit once rescaled.
 */
fun ConstantCompositionSeries.toPricePoints(): List<PricePoint> = values.map {
    PricePoint(it.date, Price(mulDivHalfEven(it.value.minorUnits, CENT_TO_PRICE_SCALE, 1L), Currency.EUR))
}

// ---- weights and allocation -------------------------------------------------------------------------------

/** A holding's [valueEur] and its [weight] (share of the portfolio total, a `Double` ratio). */
data class HoldingWeight(val holding: PortfolioHolding, val valueEur: Money, val weight: Double)

/** One bucket of an allocation: the holdings sharing [key], their [valueEur] and their [share] of the total. */
data class AllocationEntry<K>(val key: K, val valueEur: Money, val share: Double)

private data class ValuedHolding(val holding: PortfolioHolding, val currency: Currency, val valueEur: Money)

/** The holdings that have a value (quoted ones without a price are skipped), converted to EUR with [rates]. */
private fun valuedHoldings(holdings: List<PortfolioHolding>, rates: FxRates): List<ValuedHolding> =
    holdings.mapNotNull { holding ->
        holding.value()?.let { ValuedHolding(holding, it.currency, it.toEur(rates)) }
    }

private fun List<ValuedHolding>.totalEur(): Long =
    fold(0L) { total, item -> checkedAdd(total, item.valueEur.minorUnits) }

private fun share(part: Long, total: Long): Double = if (total == 0L) 0.0 else part.toDouble() / total.toDouble()

/** Weight of each valued holding in the portfolio total, largest first (ties keep the input order). */
fun holdingWeights(holdings: List<PortfolioHolding>, rates: FxRates): List<HoldingWeight> {
    val valued = valuedHoldings(holdings, rates)
    val total = valued.totalEur()
    return valued
        .map { HoldingWeight(it.holding, it.valueEur, share(it.valueEur.minorUnits, total)) }
        .sortedByDescending { it.valueEur.minorUnits }
}

/** EUR value of the portfolio per [InstrumentType], largest first. */
fun allocationByType(holdings: List<PortfolioHolding>, rates: FxRates): List<AllocationEntry<InstrumentType>> =
    allocate(valuedHoldings(holdings, rates)) { it.holding.type }

/** EUR value per bank or broker, largest first; holdings without one share the `null` key ("no broker"). */
fun allocationByBroker(holdings: List<PortfolioHolding>, rates: FxRates): List<AllocationEntry<String?>> =
    allocate(valuedHoldings(holdings, rates)) { it.holding.broker }

/** EUR value per currency the holdings are valued in, largest first. */
fun allocationByCurrency(holdings: List<PortfolioHolding>, rates: FxRates): List<AllocationEntry<Currency>> =
    allocate(valuedHoldings(holdings, rates)) { it.currency }

private fun <K> allocate(valued: List<ValuedHolding>, keyOf: (ValuedHolding) -> K): List<AllocationEntry<K>> {
    val total = valued.totalEur()
    return valued
        .groupBy(keyOf)
        .map { (key, items) ->
            val amount = items.totalEur()
            AllocationEntry(key, Money(amount), share(amount, total))
        }
        .sortedByDescending { it.valueEur.minorUnits }
}

// ---- costs and risk ---------------------------------------------------------------------------------------

/**
 * The ongoing charges of the portfolio. [averageTerPercentScaled] is the TER (percent times `PERCENT_SCALE`) averaged
 * by value over the holdings that have one, `null` if none does; [yearlyCost] is the exact sum of `value x TER`
 * (EUR, each holding rounded half-even to cents); [coverage] is the share of the portfolio value held in holdings
 * with a TER, [coveredHoldings] how many they are.
 */
data class CostSummary(
    val averageTerPercentScaled: Long?,
    val yearlyCost: Money,
    val coverage: Double,
    val coveredHoldings: Int,
)

/** The value weighted SRRI class ([averageRiskLevel], 1 to 7) over the holdings that have one, with its [coverage]. */
data class RiskSummary(val averageRiskLevel: Double?, val coverage: Double, val coveredHoldings: Int)

/** Weighted average TER, yearly cost and coverage; weights are renormalized over the holdings that have a TER. */
fun costSummary(holdings: List<PortfolioHolding>, rates: FxRates): CostSummary {
    val valued = valuedHoldings(holdings, rates)
    val withTer = valued.filter { it.holding.fundamentals?.ongoingChargePercentScaled != null }
    val ters = withTer.associateWith { it.holding.fundamentals?.ongoingChargePercentScaled ?: 0L }
    val covered = withTer.totalEur()
    val average = if (covered == 0L) {
        null
    } else {
        val weighted = ters.entries.sumOf { (item, ter) -> item.valueEur.minorUnits.toDouble() * ter.toDouble() }
        (weighted / covered.toDouble()).roundToLong()
    }
    val yearlyCost = ters.entries.fold(0L) { total, (item, ter) ->
        checkedAdd(total, mulDivHalfEven(item.valueEur.minorUnits, ter, PERCENT_TO_FRACTION))
    }
    return CostSummary(average, Money(yearlyCost), share(covered, valued.totalEur()), withTer.size)
}

/** Value weighted average SRRI class and its coverage; weights are renormalized over the holdings that have one. */
fun riskSummary(holdings: List<PortfolioHolding>, rates: FxRates): RiskSummary {
    val valued = valuedHoldings(holdings, rates)
    val withRisk = valued.filter { it.holding.fundamentals?.riskLevel != null }
    val covered = withRisk.totalEur()
    val average = if (covered == 0L) {
        null
    } else {
        withRisk.sumOf { it.valueEur.minorUnits.toDouble() * (it.holding.fundamentals?.riskLevel ?: 0) } /
            covered.toDouble()
    }
    return RiskSummary(average, share(covered, valued.totalEur()), withRisk.size)
}

// ---- concentration ----------------------------------------------------------------------------------------

/** The largest [holding] of the portfolio, its [weight], and whether that is above [CONCENTRATION_THRESHOLD]. */
data class Concentration(val holding: PortfolioHolding, val weight: Double, val exceedsThreshold: Boolean)

/** The largest holding by value, or `null` when no holding has a value. Exactly the threshold is not exceeding it. */
fun concentration(holdings: List<PortfolioHolding>, rates: FxRates): Concentration? =
    holdingWeights(holdings, rates).firstOrNull()?.let {
        Concentration(it.holding, it.weight, it.weight > CONCENTRATION_THRESHOLD)
    }
