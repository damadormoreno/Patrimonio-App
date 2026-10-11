package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.pow
import kotlin.math.sqrt

// Analytics over the price history of ONE product (an ascending list of [PricePoint], one currency). Money never
// appears here: prices stay exact fixed point and are only turned into `Double` ratios (returns, volatility,
// drawdown), which are statistics to display, not amounts to add up. Short histories give `null`, never an exception.

/** The longest gap, in days, a "price N years ago" lookup accepts between the target date and the point it uses. */
const val ANNUALIZED_LOOKUP_DAYS = 7

/** Years of history the weekly statistics (volatility, correlation) look at when that much is available. */
const val ANALYSIS_WINDOW_YEARS = 3

/** Fewer weekly returns than this and a volatility or a correlation is not meaningful, so it is `null`. */
const val MIN_WEEKLY_RETURNS = 26

/** Weeks per year, to annualize a weekly standard deviation (`sqrt(52)`). */
const val WEEKS_PER_YEAR = 52

private const val DAYS_PER_WEEK = 7

/** The windows the position detail charts. [months] is how far back a range reaches, `null` for all history. */
enum class HistoryRange(val months: Int?) {
    ONE_MONTH(1),
    SIX_MONTHS(6),
    ONE_YEAR(12),
    FIVE_YEARS(60),
    MAX(null),
}

/** The points of [points] dated from [range] before [today] (inclusive) onwards; all of them for `MAX`. */
fun pointsIn(points: List<PricePoint>, range: HistoryRange, today: LocalDate): List<PricePoint> {
    val months = range.months ?: return points
    val start = today.minus(months, DateTimeUnit.MONTH)
    return points.filter { it.date >= start }
}

/**
 * How the price moved between the [first] and the [last] point of a range: [absoluteScaled] in price units scaled by
 * `PRICE_SCALE` (negative for a fall) and [relative] as a ratio (0.25 = +25 %, a `Double`).
 */
data class RangeChange(val first: PricePoint, val last: PricePoint, val absoluteScaled: Long, val relative: Double)

/** The change over [points], or `null` with fewer than two points or when the first price is zero. */
fun rangeChange(points: List<PricePoint>): RangeChange? {
    if (points.size < 2) return null
    val first = points.first()
    val last = points.last()
    val relative = relativeChange(first.price, last.price) ?: return null
    return RangeChange(first, last, last.price.scaled - first.price.scaled, relative)
}

/**
 * The return of one calendar [year] as a ratio ([returnRatio] 0.10 = +10 %, a `Double`). [partial] marks the first
 * year of the history, measured from its first point instead of a previous year end; [yearToDate] marks the running
 * year, measured up to the latest point.
 */
data class CalendarYearReturn(val year: Int, val returnRatio: Double, val partial: Boolean, val yearToDate: Boolean)

/**
 * Return per calendar year, most recent first: the last point of the year over the last point of the previous year
 * with data, minus one. The first year starts at the first point of the history ([CalendarYearReturn.partial]); a year
 * whose only point is that start has nothing to measure and is left out. The year of [today] is year to date.
 */
fun calendarYearReturns(points: List<PricePoint>, today: LocalDate): List<CalendarYearReturn> {
    val lastOfYear = points.groupBy { it.date.year }.mapValues { (_, inYear) -> inYear.last() }
    val firstYear = lastOfYear.keys.minOrNull() ?: return emptyList()
    var base = points.first()
    val returns = mutableListOf<CalendarYearReturn>()
    for (year in lastOfYear.keys.sorted()) {
        val end = lastOfYear.getValue(year)
        if (end != base) {
            relativeChange(base.price, end.price)?.let {
                returns += CalendarYearReturn(year, it, partial = year == firstYear, yearToDate = year == today.year)
            }
        }
        base = end
    }
    return returns.reversed()
}

/**
 * Compound annual growth rate over the last [years] years as a ratio: `(last / base) ^ (1 / years) - 1`, where `base`
 * is the latest point on or before `last date - years`, taken only if it lies at most [ANNUALIZED_LOOKUP_DAYS] days
 * before that date. `null` when the history does not reach back that far (or the base price is zero).
 */
fun annualizedReturn(points: List<PricePoint>, years: Int): Double? {
    require(years > 0) { "years must be positive, was $years" }
    val last = points.lastOrNull() ?: return null
    val target = last.date.minus(years, DateTimeUnit.YEAR)
    val base = points.lastOrNull { it.date <= target } ?: return null
    if (base.date < target.minus(ANNUALIZED_LOOKUP_DAYS, DateTimeUnit.DAY) || base.price.scaled == 0L) return null
    val growth = last.price.scaled.toDouble() / base.price.scaled.toDouble()
    return growth.pow(1.0 / years) - 1.0
}

/** The last available [point] of the ISO week (Monday to Sunday) that starts on [weekStart]. */
data class WeeklyClose(val weekStart: LocalDate, val point: PricePoint)

/** The return, as a ratio, of the week that starts on [weekStart] over the previous week's close. */
data class WeeklyReturn(val weekStart: LocalDate, val ratio: Double)

/** One [WeeklyClose] per ISO week with data, ascending. A Sunday still belongs to the week that began on Monday. */
fun weeklyCloses(points: List<PricePoint>): List<WeeklyClose> =
    points.groupBy { weekStart(it.date) }.map { (start, inWeek) -> WeeklyClose(start, inWeek.last()) }

/** Returns between consecutive weeks that both have a close; a missing week breaks the chain, nothing is bridged. */
fun weeklyReturns(closes: List<WeeklyClose>): List<WeeklyReturn> = closes.zipWithNext().mapNotNull { (a, b) ->
    if (b.weekStart != a.weekStart.plus(DAYS_PER_WEEK, DateTimeUnit.DAY)) return@mapNotNull null
    relativeChange(a.point.price, b.point.price)?.let { WeeklyReturn(b.weekStart, it) }
}

/** Annualized volatility ([annualized], a ratio) from [weeks] weekly returns. */
data class Volatility(val annualized: Double, val weeks: Int)

/**
 * Annualized volatility: the sample standard deviation of the weekly returns times `sqrt(52)`. It looks at the last
 * [ANALYSIS_WINDOW_YEARS] years before [today] and, if they hold fewer than [MIN_WEEKLY_RETURNS] weekly returns, at the
 * whole history. `null` when even that has fewer than [MIN_WEEKLY_RETURNS].
 */
fun volatility(points: List<PricePoint>, today: LocalDate): Volatility? {
    val returns = recentOrAll(weeklyReturns(weeklyCloses(points)), today) { it.weekStart }
    if (returns.size < MIN_WEEKLY_RETURNS) return null
    val ratios = returns.map { it.ratio }
    val mean = ratios.average()
    val variance = ratios.sumOf { (it - mean) * (it - mean) } / (ratios.size - 1)
    return Volatility(sqrt(variance) * sqrt(WEEKS_PER_YEAR.toDouble()), ratios.size)
}

/**
 * The deepest fall from a peak: [fall] is a non-negative ratio (0.25 = the price lost 25 % from its [peak] to its
 * [trough], a `Double`). A series that never falls reports 0 with its first point as both.
 */
data class Drawdown(val fall: Double, val peak: PricePoint, val trough: PricePoint)

/** The maximum drawdown of [points], or `null` with fewer than two points. */
fun maxDrawdown(points: List<PricePoint>): Drawdown? {
    if (points.size < 2) return null
    var peak = points.first()
    var worst = Drawdown(0.0, peak, peak)
    for (point in points.drop(1)) {
        if (point.price.scaled > peak.price.scaled) {
            peak = point
        } else if (peak.price.scaled > 0) {
            val fall = (peak.price.scaled - point.price.scaled).toDouble() / peak.price.scaled.toDouble()
            if (fall > worst.fall) worst = Drawdown(fall, peak, point)
        }
    }
    return worst
}

/** The Monday of the ISO week that contains [date]. */
internal fun weekStart(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/** The [items] of the last [ANALYSIS_WINDOW_YEARS] years before [today], or all of them if that is too few. */
internal fun <T> recentOrAll(items: List<T>, today: LocalDate, weekStartOf: (T) -> LocalDate): List<T> {
    val windowStart = today.minus(ANALYSIS_WINDOW_YEARS, DateTimeUnit.YEAR)
    val recent = items.filter { weekStartOf(it) >= windowStart }
    return if (recent.size >= MIN_WEEKLY_RETURNS) recent else items
}

/** `to / from - 1`, or `null` when [from] is zero and the ratio is undefined. */
private fun relativeChange(from: Price, to: Price): Double? =
    if (from.scaled == 0L) null else to.scaled.toDouble() / from.scaled.toDouble() - 1.0
