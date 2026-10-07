package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlin.math.absoluteValue

/** A month-over-month change smaller than this share of the previous net worth, in per mille, counts
 *  as [MonthTrend.STABLE]: 5 ‰ = 0.5 %, so exchange-rate noise does not read as gaining or losing. */
const val STABLE_CHANGE_PERMILLE = 5L

enum class MonthTrend { UP, DOWN, STABLE }

/** Net worth (EUR) at the end of [month] and how it moved against the month before. */
data class MonthlyNetWorth(
    val month: YearMonth,
    val netWorth: Money,
    /** Null for the first month of the history, which has nothing to compare with. */
    val change: Money?,
    val trend: MonthTrend?,
    /** False when nothing was saved that month: the value is carried over from the previous one. */
    val recorded: Boolean,
)

/**
 * One entry per calendar month, oldest first, from the earliest snapshot up to [currentMonth]. Snapshots
 * are only written when the user edits something, so a month without one keeps the previous value (no
 * change known). [currentMonth] uses [currentNetWorth], the live figure the Patrimonio hero shows,
 * rather than its possibly older snapshot. Snapshots after [currentMonth] are ignored.
 */
fun monthlyNetWorth(
    snapshots: List<NetWorthSnapshot>,
    currentMonth: YearMonth,
    currentNetWorth: Money,
): List<MonthlyNetWorth> {
    val byMonth = snapshots.filter { it.yearMonth <= currentMonth }.associateBy { it.yearMonth }
    var month = byMonth.keys.minOrNull() ?: currentMonth
    var previous: Money? = null
    return buildList {
        while (month <= currentMonth) {
            val snapshot = byMonth[month]
            val netWorth = when {
                month == currentMonth -> currentNetWorth
                snapshot != null -> snapshot.netWorth
                else -> previous ?: Money.ZERO
            }
            val change = previous?.let { netWorth - it }
            add(
                MonthlyNetWorth(
                    month = month,
                    netWorth = netWorth,
                    change = change,
                    trend = previous?.let { monthTrend(netWorth - it, it) },
                    recorded = month == currentMonth || snapshot != null,
                ),
            )
            previous = netWorth
            month = month.next()
        }
    }
}

private fun monthTrend(change: Money, previous: Money): MonthTrend {
    val base = previous.minorUnits.absoluteValue
    return when {
        change.minorUnits.absoluteValue * 1_000 < base * STABLE_CHANGE_PERMILLE -> MonthTrend.STABLE
        change.minorUnits > 0 -> MonthTrend.UP
        change.minorUnits < 0 -> MonthTrend.DOWN
        else -> MonthTrend.STABLE
    }
}

private fun YearMonth.next(): YearMonth = if (month == 12) YearMonth(year + 1, 1) else YearMonth(year, month + 1)
