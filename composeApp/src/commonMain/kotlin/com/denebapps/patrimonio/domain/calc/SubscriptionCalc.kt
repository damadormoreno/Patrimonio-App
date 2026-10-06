package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.plus

private const val DAYS_PER_WEEK = 7
private const val MONTHS_PER_YEAR = 12L

/**
 * The first charge on or after [today]: [firstChargeDate] itself while it is still ahead, otherwise
 * `firstChargeDate + n periods`. Always computed from the anchor, so a charge on the 31st falls on
 * the last day of shorter months and goes back to the 31st afterwards (31 Jan → 28 Feb → 31 Mar),
 * and 29 Feb yearly charges fall on 28 Feb in non-leap years.
 */
fun nextChargeDate(firstChargeDate: LocalDate, cycle: BillingCycle, today: LocalDate): LocalDate {
    if (firstChargeDate >= today) return firstChargeDate
    val months = cycle.months
    if (months == null) {
        val periods = (firstChargeDate.daysUntil(today) + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
        return firstChargeDate.plus(periods * DAYS_PER_WEEK, DateTimeUnit.DAY)
    }
    // monthsUntil floors, so this lands on or just before today; step forward from there.
    var periods = firstChargeDate.monthsUntil(today) / months
    var candidate = firstChargeDate.plus(periods * months, DateTimeUnit.MONTH)
    while (candidate < today) {
        periods++
        candidate = firstChargeDate.plus(periods * months, DateTimeUnit.MONTH)
    }
    return candidate
}

/** Cost per year of one charge of [amount] (same currency as [amount]). */
fun yearlyCost(amount: Money, cycle: BillingCycle): Money = amount * cycle.chargesPerYear.toLong()

/** Cost per month of one charge of [amount], rounded half-even to minor units. */
fun monthlyCost(amount: Money, cycle: BillingCycle): Money =
    Money(roundHalfEven(yearlyCost(amount, cycle).minorUnits, MONTHS_PER_YEAR))

/** EUR totals over the ACTIVE subscriptions. Each amount is converted to EUR first, then normalised. */
data class SubscriptionTotals(val monthlyEur: Money, val yearlyEur: Money, val activeCount: Int)

fun subscriptionTotals(subscriptions: List<Subscription>, rates: FxRates): SubscriptionTotals {
    val active = subscriptions.filter { it.active }
    val yearly = active.fold(Money.ZERO) { total, sub -> total + yearlyCost(sub.amount.toEur(rates), sub.cycle) }
    return SubscriptionTotals(
        monthlyEur = Money(roundHalfEven(yearly.minorUnits, MONTHS_PER_YEAR)),
        yearlyEur = yearly,
        activeCount = active.size,
    )
}
