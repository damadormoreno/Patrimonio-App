package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.domain.model.Subscription
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionCalcTest {
    private fun date(iso: String) = LocalDate.parse(iso)

    private fun next(first: String, cycle: BillingCycle, today: String) =
        nextChargeDate(date(first), cycle, date(today))

    @Test
    fun `a first charge still ahead is the next charge`() {
        assertEquals(date("2026-11-01"), next("2026-11-01", BillingCycle.MONTHLY, "2026-10-06"))
    }

    @Test
    fun `a charge falling today is today`() {
        assertEquals(date("2026-10-06"), next("2026-10-06", BillingCycle.MONTHLY, "2026-10-06"))
        assertEquals(date("2026-10-06"), next("2026-08-06", BillingCycle.MONTHLY, "2026-10-06"))
    }

    @Test
    fun `monthly charges roll to the next month once the day has passed`() {
        assertEquals(date("2026-11-05"), next("2026-01-05", BillingCycle.MONTHLY, "2026-10-06"))
        assertEquals(date("2026-10-20"), next("2026-01-20", BillingCycle.MONTHLY, "2026-10-06"))
    }

    @Test
    fun `month-end anchors clamp to short months without drifting`() {
        assertEquals(date("2026-02-28"), next("2026-01-31", BillingCycle.MONTHLY, "2026-02-01"))
        assertEquals(date("2026-03-31"), next("2026-01-31", BillingCycle.MONTHLY, "2026-03-01"))
        assertEquals(date("2026-04-30"), next("2026-01-31", BillingCycle.MONTHLY, "2026-04-01"))
    }

    @Test
    fun `quarterly and semiannual step by their number of months`() {
        assertEquals(date("2027-01-15"), next("2026-01-15", BillingCycle.QUARTERLY, "2026-10-16"))
        assertEquals(date("2026-10-15"), next("2026-01-15", BillingCycle.QUARTERLY, "2026-10-15"))
        assertEquals(date("2027-01-15"), next("2026-01-15", BillingCycle.SEMIANNUAL, "2026-07-16"))
    }

    @Test
    fun `yearly leap-day anchors fall on the 28th in common years`() {
        assertEquals(date("2025-02-28"), next("2024-02-29", BillingCycle.YEARLY, "2025-01-10"))
        assertEquals(date("2028-02-29"), next("2024-02-29", BillingCycle.YEARLY, "2027-03-01"))
    }

    @Test
    fun `weekly charges keep the weekday`() {
        // 2026-10-05 is a Monday.
        assertEquals(date("2026-10-12"), next("2026-10-05", BillingCycle.WEEKLY, "2026-10-06"))
        assertEquals(date("2026-10-12"), next("2026-09-07", BillingCycle.WEEKLY, "2026-10-12"))
    }

    @Test
    fun `costs are normalised per month and per year`() {
        assertEquals(Money(1_299), monthlyCost(Money(1_299), BillingCycle.MONTHLY))
        assertEquals(Money(15_588), yearlyCost(Money(1_299), BillingCycle.MONTHLY))
        assertEquals(Money(1_000), monthlyCost(Money(12_000), BillingCycle.YEARLY))
        assertEquals(Money(2_000), monthlyCost(Money(6_000), BillingCycle.QUARTERLY))
        assertEquals(Money(1_000), monthlyCost(Money(6_000), BillingCycle.SEMIANNUAL))
        // 5,00 € a week: 260 € a year, 21,666… € a month.
        assertEquals(Money(26_000), yearlyCost(Money(500), BillingCycle.WEEKLY))
        assertEquals(Money(2_167), monthlyCost(Money(500), BillingCycle.WEEKLY))
    }

    @Test
    fun `totals convert to EUR and skip paused subscriptions`() {
        // 1 USD = 0.90 EUR.
        val rates = FxRates(mapOf(Currency.USD to RATE_SCALE * 9 / 10))
        val subs = listOf(
            subscription("netflix", Money(1_299), Currency.EUR, BillingCycle.MONTHLY),
            subscription("icloud", Money(12_000), Currency.USD, BillingCycle.YEARLY),
            subscription("gym", Money(4_000), Currency.EUR, BillingCycle.MONTHLY, active = false),
        )

        val totals = subscriptionTotals(subs, rates)

        // 155,88 € + 108,00 € a year.
        assertEquals(Money(26_388), totals.yearlyEur)
        assertEquals(Money(2_199), totals.monthlyEur)
        assertEquals(2, totals.activeCount)
    }

    @Test
    fun `no active subscriptions total zero`() {
        val totals = subscriptionTotals(emptyList(), FxRates(emptyMap()))

        assertEquals(SubscriptionTotals(Money.ZERO, Money.ZERO, 0), totals)
    }

    private fun subscription(
        id: String,
        amount: Money,
        currency: Currency,
        cycle: BillingCycle,
        active: Boolean = true,
    ) = Subscription(
        id = id,
        name = id,
        amount = CurrencyAmount(amount, currency),
        cycle = cycle,
        firstChargeDate = LocalDate.parse("2026-01-01"),
        paidFromAssetId = null,
        active = active,
    )
}
