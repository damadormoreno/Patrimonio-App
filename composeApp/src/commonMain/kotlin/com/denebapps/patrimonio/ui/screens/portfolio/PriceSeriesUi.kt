package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.calendarYearReturns
import com.denebapps.patrimonio.domain.calc.maxDrawdown
import com.denebapps.patrimonio.domain.calc.pointsIn
import com.denebapps.patrimonio.domain.calc.rangeChange
import com.denebapps.patrimonio.domain.calc.volatility
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.ui.components.charts.ChartPoint
import kotlinx.datetime.LocalDate

// What the position detail and the portfolio detail share: turning a series of EUR or product prices into a chart
// with range chips, the return per calendar year and the risk figures, every text ready to show.

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

/** One row per calendar year of [points], the running one first. */
internal fun yearReturnsUi(points: List<PricePoint>, today: LocalDate): List<YearReturnUi> =
    calendarYearReturns(points, today).map {
        YearReturnUi(
            yearReturnLabel(it, points.first().date),
            formatPercent(it.returnRatio),
            toneOf(it.returnRatio),
        )
    }

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
