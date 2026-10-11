package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.annualizedReturn
import com.denebapps.patrimonio.domain.calc.calendarYearReturns
import com.denebapps.patrimonio.domain.calc.maxDrawdown
import com.denebapps.patrimonio.domain.calc.pointsIn
import com.denebapps.patrimonio.domain.calc.rangeChange
import com.denebapps.patrimonio.domain.calc.volatility
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.ui.components.charts.BarChartBar
import com.denebapps.patrimonio.ui.components.charts.ChartPoint
import kotlinx.datetime.LocalDate

// What the position detail and the portfolio detail share: turning a series of EUR or product prices into a chart
// with range chips, the return per calendar year and the risk figures, every text ready to show.

/** The horizons of the annualized returns, in years. */
private val CAGR_YEARS = listOf(1, 3, 5)

/** A chart needs a line, that is two points. */
internal const val MIN_CHART_POINTS = 2

/** The range chips and the chart of the selected range, or of all the history if that one has no data. */
internal fun priceChartUi(
    points: List<PricePoint>,
    selected: HistoryRange,
    today: LocalDate,
): Pair<List<RangeChipUi>, ChartUi> {
    val byRange = HistoryRange.entries.associateWith { pointsIn(points, it, today) }
    val shown = if ((byRange.getValue(selected).size) >= MIN_CHART_POINTS) selected else HistoryRange.MAX
    val chips = HistoryRange.entries.map {
        RangeChipUi(
            it,
            rangeLabel(it),
            enabled = byRange.getValue(it).size >= MIN_CHART_POINTS,
            selected = it == shown,
        )
    }
    val prices = byRange.getValue(shown)
    val currency = prices.last().price.currency
    val change = rangeChange(prices)
    val chart = ChartUi(
        points = prices.map {
            ChartPoint(
                it.date.toEpochDays().toLong(),
                it.price.scaled / PRICE_SCALE.toDouble(),
            )
        },
        prices = prices,
        currency = currency,
        changeText = change?.let { rangeChangeText(it, currency) },
        changeTone = change?.let { toneOf(it.relative) } ?: PerformanceTone.NEUTRAL,
        minText = "Mín ${formatPrice(prices.minBy { it.price.scaled }.price)}",
        maxText = "Máx ${formatPrice(prices.maxBy { it.price.scaled }.price)}",
        firstDateText = formatShortDate(prices.first().date),
        lastDateText = formatShortDate(prices.last().date),
    )
    return chips to chart
}

/** One bar per calendar year of [points], the oldest first (the order the chart draws them in). */
internal fun yearReturnsUi(points: List<PricePoint>, today: LocalDate): List<YearReturnUi> {
    val historyStart = formatDayMonthShort(points.first().date)
    return calendarYearReturns(points, today).reversed().map {
        YearReturnUi(
            year = it.year,
            ratio = it.returnRatio,
            valueText = formatPercent(it.returnRatio, signed = false),
            tone = toneOf(it.returnRatio),
            partialSince = if (it.partial) historyStart else null,
            yearToDate = it.yearToDate,
        )
    }
}

/** Why a year is not a whole one: "desde 14/03" for the first of the history, "en curso" for the running one. */
private fun YearReturnUi.reasons(): List<String> = listOfNotNull(
    partialSince?.let { "desde $it" },
    "en curso".takeIf { yearToDate },
)

/**
 * What explains the lighter bars under the chart: "2019: desde 14/03 · 2026: en curso". `null` when every year is a
 * whole one.
 */
internal fun yearReturnsFootnote(years: List<YearReturnUi>): String? = years
    .filter { it.reasons().isNotEmpty() }
    .map { "${it.year}: ${it.reasons().joinToString(" y ")}" }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" · ")

/** What a screen reader says about the chart: "Rentabilidad por año: 2026 0,95 % (en curso), 2025 4,70 %". */
internal fun yearReturnsDescription(years: List<YearReturnUi>): String {
    val title = "Rentabilidad por año"
    if (years.isEmpty()) return title
    return years.asReversed().joinToString(prefix = "$title: ") { year ->
        val reasons = year.reasons()
        val note = if (reasons.isEmpty()) "" else " (${reasons.joinToString(" y ")})"
        "${year.year} ${year.valueText}$note"
    }
}

/**
 * The bar of a year. A return that rounds to zero is a zero bar, so the chart draws it as the neutral mark its text
 * says it is; the partial first year and the running one are drawn lighter.
 */
internal fun YearReturnUi.toBar(): BarChartBar = BarChartBar(
    label = year.toString(),
    value = if (tone == PerformanceTone.NEUTRAL) 0.0 else ratio,
    valueText = valueText,
    faded = partialSince != null || yearToDate,
)

/** The volatility and the deepest fall of [points]. */
internal fun riskUi(points: List<PricePoint>, today: LocalDate): RiskUi {
    val volatility = volatility(points, today)
    val drawdown = maxDrawdown(points)
    return RiskUi(
        volatilityLabel = volatilityLabel(volatility, points.first().date, today),
        volatilityText = volatility?.let { formatPercent(it.annualized, signed = false) } ?: NO_FIGURE_TEXT,
        drawdownText = drawdown?.let { formatPercent(-it.fall) } ?: NO_FIGURE_TEXT,
        drawdownDates = drawdown?.takeIf { it.fall > 0 }?.let(::drawdownDatesText),
    )
}

/** The annualized return over 1, 3 and 5 years of [points]; "—" for a horizon the history does not reach. */
internal fun annualizedFigures(points: List<PricePoint>): List<FigureUi> = CAGR_YEARS.map { years ->
    val annualized = annualizedReturn(points, years)
    FigureUi(
        label = annualizedLabel(years),
        valueText = annualized?.let { formatPercent(it) } ?: NO_FIGURE_TEXT,
        tone = annualized?.let { toneOf(it) } ?: PerformanceTone.NEUTRAL,
    )
}

/**
 * What the provider says about a product, `null` when it says nothing. [yearlyCostText] turns the ongoing charge
 * (percent times `PERCENT_SCALE`) into the line under it, or `null` for none.
 */
internal fun productDataUi(
    fundamentals: InstrumentFundamentals?,
    yearlyCostText: (chargePercentScaled: Long) -> String?,
): ProductDataUi? {
    val known = fundamentals?.takeUnless { it.isEmpty } ?: return null
    val charge = known.ongoingChargePercentScaled
    return ProductDataUi(
        terText = charge?.let(::formatPercentScaled),
        yearlyCostText = charge?.let(yearlyCostText),
        riskLevel = known.riskLevel,
        category = known.category,
        manager = known.managementCompany,
    )
}
