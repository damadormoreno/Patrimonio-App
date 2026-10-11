package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.ANALYSIS_WINDOW_YEARS
import com.denebapps.patrimonio.domain.calc.CalendarYearReturn
import com.denebapps.patrimonio.domain.calc.Drawdown
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.RangeChange
import com.denebapps.patrimonio.domain.calc.Volatility
import com.denebapps.patrimonio.domain.calc.formatScaled6
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.PERCENT_SCALE
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.ui.components.currencySymbol
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.math.abs
import kotlin.math.roundToLong

// Pure text formatting of the position detail (es-ES, neutral Spanish). The statistics are `Double` ratios, shown
// rounded to two decimals; prices are exact fixed point and keep their own decimals.

/** What a figure shown with a sign is doing, so the screen can color it without reading the text. */
enum class PerformanceTone { GAIN, LOSS, NEUTRAL }

/** Shown instead of a figure that could not be computed (not enough history, no data). */
internal const val NO_FIGURE_TEXT = "—"

private const val MINUS = "−"
private const val PERCENT_DECIMALS = 2
private const val TWO_DIGITS = 2
private const val TEN = 10L
private const val PERCENT = 100.0
private const val THOUSANDS_GROUP = 3
private const val SCALED_PERCENT_DECIMALS = 4
private const val ARROW = "→"

/**
 * A ratio as a percentage with [decimals] decimals, a decimal comma and thousands dots: `0.1234` is "+12,34 %",
 * `-0.035` is "−3,50 %" (with a true minus sign). With [signed] a positive figure carries a plus; a figure that rounds
 * to zero never has a sign. "—" for a ratio that is not a finite number.
 */
internal fun formatPercent(ratio: Double, signed: Boolean = true, decimals: Int = PERCENT_DECIMALS): String {
    if (!ratio.isFinite()) return NO_FIGURE_TEXT
    val factor = pow10(decimals)
    val scaled = (abs(ratio) * PERCENT * factor).roundToLong()
    val sign = when {
        scaled == 0L -> ""
        ratio < 0 -> MINUS
        signed -> "+"
        else -> ""
    }
    val whole = groupThousands(scaled / factor)
    val fraction = if (decimals == 0) "" else "," + (scaled % factor).toString().padStart(decimals, '0')
    return "$sign$whole$fraction %"
}

/**
 * A percentage stored as fixed point (times `PERCENT_SCALE`, like an ongoing charge) with at least two decimals and
 * every significant one: 1800 is "0,18 %", 1825 is "0,1825 %".
 */
internal fun formatPercentScaled(scaled: Long): String {
    val whole = groupThousands(scaled / PERCENT_SCALE)
    val fraction = (scaled % PERCENT_SCALE).toString()
        .padStart(SCALED_PERCENT_DECIMALS, '0')
        .trimEnd('0')
        .padEnd(PERCENT_DECIMALS, '0')
    return "$whole,$fraction %"
}

/** A price difference ([scaledDelta] in `PRICE_SCALE` units) with its sign: "+1.234,56 €", "−15,30 €". */
internal fun formatSignedPrice(scaledDelta: Long, currency: Currency): String {
    val sign = when {
        scaledDelta > 0 -> "+"
        scaledDelta < 0 -> MINUS
        else -> ""
    }
    val number = formatScaled6(abs(scaledDelta), currency.decimals, grouped = true)
    return "$sign$number ${currencySymbol(currency)}"
}

/** "14/03": the day and the month, zero padded. */
internal fun formatDayMonthShort(date: LocalDate): String =
    "${date.dayOfMonth.toString().padStart(TWO_DIGITS, '0')}/${date.monthNumber.toString().padStart(TWO_DIGITS, '0')}"

/**
 * The row label of a calendar year: "2025", "2026 · en curso" for the running one and "2019 · desde 14/03" for the
 * first, which only covers the history from [historyStart]; both marks can apply to the same year.
 */
internal fun yearReturnLabel(year: CalendarYearReturn, historyStart: LocalDate): String = buildString {
    append(year.year)
    if (year.yearToDate) append(" · en curso")
    if (year.partial) append(" · desde ${formatDayMonthShort(historyStart)}")
}

/** "31/12/2021 → 03/10/2022": from the peak to the trough of a fall. */
internal fun drawdownDatesText(drawdown: Drawdown): String =
    "${formatShortDate(drawdown.peak.date)} $ARROW ${formatShortDate(drawdown.trough.date)}"

/**
 * The label of the volatility row: "Volatilidad (3 años)" when the history reaches back that far, otherwise the
 * weeks it actually looked at ("Volatilidad (40 semanas)"). Without a [volatility] it names the three years it would
 * need.
 */
internal fun volatilityLabel(volatility: Volatility?, historyStart: LocalDate, today: LocalDate): String {
    val threeYears = "Volatilidad ($ANALYSIS_WINDOW_YEARS años)"
    if (volatility == null) return threeYears
    val windowStart = today.minus(ANALYSIS_WINDOW_YEARS, DateTimeUnit.YEAR)
    return if (historyStart <= windowStart) threeYears else "Volatilidad (${volatility.weeks} semanas)"
}

/** The short name of a range on its chip. */
internal fun rangeLabel(range: HistoryRange): String = when (range) {
    HistoryRange.ONE_MONTH -> "1M"
    HistoryRange.SIX_MONTHS -> "6M"
    HistoryRange.ONE_YEAR -> "1A"
    HistoryRange.FIVE_YEARS -> "5A"
    HistoryRange.MAX -> "Máx"
}

/** "1 año", "3 años": the horizon of an annualized return. */
internal fun annualizedLabel(years: Int): String = if (years == 1) "1 año" else "$years años"

/** "+1.234,56 € · +12,34 %": how the price moved over a range, in price and in percent. */
internal fun rangeChangeText(change: RangeChange, currency: Currency): String =
    "${formatSignedPrice(change.absoluteScaled, currency)} · ${formatPercent(change.relative)}"

/** "04/05/2026 · 245,12 €": the point under the finger while scrubbing the chart. */
internal fun scrubText(point: PricePoint): String = "${formatShortDate(point.date)} · ${formatPrice(point.price)}"

/** [PerformanceTone.GAIN] above zero, [PerformanceTone.LOSS] below, neutral when it rounds to zero at [decimals]. */
internal fun toneOf(ratio: Double, decimals: Int = PERCENT_DECIMALS): PerformanceTone {
    val scaled = (abs(ratio) * PERCENT * pow10(decimals)).roundToLong()
    return when {
        !ratio.isFinite() || scaled == 0L -> PerformanceTone.NEUTRAL
        ratio > 0 -> PerformanceTone.GAIN
        else -> PerformanceTone.LOSS
    }
}

private fun groupThousands(whole: Long): String =
    whole.toString().reversed().chunked(THOUSANDS_GROUP).joinToString(".").reversed()

private fun pow10(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= TEN }
    return result
}
