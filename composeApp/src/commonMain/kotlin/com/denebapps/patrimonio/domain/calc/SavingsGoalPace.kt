package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlinx.datetime.LocalDate

/** Where a savings goal stands against its target, in the goal currency. */
sealed interface SavingsGoalPace {
    /** Progress is at or above the target; [surplus] is the overfunded amount (zero when exact). */
    data class Reached(val surplus: Money) : SavingsGoalPace

    /** [remaining] is still missing; [pace] is null when the goal has no target date. */
    data class Remaining(val remaining: Money, val pace: Pace?) : SavingsGoalPace

    sealed interface Pace {
        /** The target date is strictly before today. */
        data object DatePassed : Pace

        /** Saving [amount] every month from the current month through [until] reaches the target. */
        data class Monthly(val amount: Money, val until: YearMonth) : Pace
    }
}

/**
 * Computes the gap between [progress] and [target] and, when there is a [targetDate], the monthly
 * amount needed to close it. Months left count the current month through the target month inclusive
 * (same month → 1). The monthly amount is rounded up to a whole unit of [currency], so it never
 * falls short.
 */
fun savingsGoalPace(
    target: Money,
    progress: Money,
    currency: Currency,
    targetDate: LocalDate?,
    today: LocalDate,
): SavingsGoalPace {
    if (progress >= target) return SavingsGoalPace.Reached(surplus = progress - target)

    val remaining = target - progress
    val pace =
        when {
            targetDate == null -> null
            targetDate < today -> SavingsGoalPace.Pace.DatePassed
            else -> {
                val months = monthsInclusive(from = today, to = targetDate)
                SavingsGoalPace.Pace.Monthly(
                    amount = monthlyAmount(remaining, months, currency),
                    until = YearMonth(targetDate.year, targetDate.monthNumber),
                )
            }
        }
    return SavingsGoalPace.Remaining(remaining, pace)
}

private fun monthsInclusive(from: LocalDate, to: LocalDate): Long =
    (to.year * 12L + to.monthNumber) - (from.year * 12L + from.monthNumber) + 1

private fun monthlyAmount(remaining: Money, months: Long, currency: Currency): Money {
    val unit = (1..currency.decimals).fold(1L) { acc, _ -> acc * 10 }
    val divisor = months * unit
    val wholeUnits = remaining.minorUnits / divisor + if (remaining.minorUnits % divisor == 0L) 0 else 1
    return Money(wholeUnits * unit)
}
