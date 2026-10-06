package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate

/**
 * A recurring charge. [firstChargeDate] anchors the schedule: every later charge is computed from it
 * (never from the previous charge), so month-end dates don't drift (see `nextChargeDate`).
 * [paidFromAssetId] is informational only — the app tracks balances, not transactions, so a charge
 * never moves money out of the asset. Inactive (paused) subscriptions keep their data but count
 * towards no total.
 */
data class Subscription(
    val id: String,
    val name: String,
    val amount: CurrencyAmount,
    val cycle: BillingCycle,
    val firstChargeDate: LocalDate,
    val paidFromAssetId: String?,
    val active: Boolean,
)

/**
 * [months] is the period length for calendar-month cycles; WEEKLY is the only day-based one.
 * [chargesPerYear] normalises costs (52 weeks per year for WEEKLY, the usual convention).
 */
enum class BillingCycle(val months: Int?, val chargesPerYear: Int) {
    WEEKLY(months = null, chargesPerYear = 52),
    MONTHLY(months = 1, chargesPerYear = 12),
    QUARTERLY(months = 3, chargesPerYear = 4),
    SEMIANNUAL(months = 6, chargesPerYear = 2),
    YEARLY(months = 12, chargesPerYear = 1),
}
