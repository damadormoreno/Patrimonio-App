package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.date
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// XIRR with ACT/365. Amounts are EUR cents; outflows (money leaving the user's pocket) are negative.
class XirrTest {
    private fun flow(year: Int, month: Int, day: Int, cents: Long) = CashFlow(date(year, month, day), Money(cents))

    private fun assertRate(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) < 1e-6, "expected $expected but was $actual")
    }

    @Test
    fun `a thousand that becomes eleven hundred in a year yields ten percent`() {
        // 2025-01-01 -> 2026-01-01 is 365 days = exactly 1.0 year: 1100 / 1000 - 1 = 10 %.
        assertRate(0.10, xirr(listOf(flow(2025, 1, 1, -100_000), flow(2026, 1, 1, 110_000))))
    }

    @Test
    fun `two years compound`() {
        // 2025-01-01 -> 2027-01-01 is 730 days = 2.0 years: 1210 / 1000 = 1.1^2 -> 10 %.
        assertRate(0.10, xirr(listOf(flow(2025, 1, 1, -100_000), flow(2027, 1, 1, 121_000))))
    }

    @Test
    fun `a loss yields a negative rate`() {
        assertRate(-0.10, xirr(listOf(flow(2025, 1, 1, -100_000), flow(2026, 1, 1, 90_000))))
    }

    @Test
    fun `several flows match the independently solved rate`() {
        // -100 on day 0, -100 after 1 year, +231 after 2 years. With x = 1 / (1 + r) the equation
        // 100 + 100 x = 231 x^2 has the positive root x = (100 + sqrt(100^2 + 4 * 231 * 100)) / 462
        //                                              = (100 + sqrt(102_400)) / 462 = 420 / 462 = 10 / 11,
        // so r = 11 / 10 - 1 = 10 %.
        val flows = listOf(flow(2025, 1, 1, -10_000), flow(2026, 1, 1, -10_000), flow(2027, 1, 1, 23_100))

        assertRate(0.10, xirr(flows))
    }

    @Test
    fun `the order of the flows does not matter and flows on the same day add up`() {
        val ordered = listOf(flow(2025, 1, 1, -60_000), flow(2025, 1, 1, -40_000), flow(2026, 1, 1, 110_000))

        assertRate(0.10, xirr(ordered))
        assertRate(0.10, xirr(ordered.reversed()))
    }

    @Test
    fun `the rate found zeroes the net present value`() {
        // Flows with an uneven spacing: the net present value at the returned rate is checked by hand-rolled sum.
        val flows = listOf(
            flow(2024, 3, 15, -250_000),
            flow(2024, 9, 1, -100_000),
            flow(2025, 2, 10, 30_000),
            flow(2025, 11, 20, 410_000),
        )

        val rate = xirr(flows)

        assertNotNull(rate)
        val first = flows.minOf { it.date }
        val npv = flows.sumOf {
            val years = (it.date.toEpochDays() - first.toEpochDays()) / 365.0
            it.amountEur.minorUnits / 100.0 / (1 + rate).pow(years)
        }
        assertTrue(abs(npv) < 1e-4, "net present value at $rate was $npv")
    }

    @Test
    fun `without both an outflow and an inflow there is no rate`() {
        assertNull(xirr(listOf(flow(2025, 1, 1, -100), flow(2026, 1, 1, -100))))
        assertNull(xirr(listOf(flow(2025, 1, 1, 100), flow(2026, 1, 1, 100))))
        assertNull(xirr(listOf(flow(2025, 1, 1, -100))))
        assertNull(xirr(emptyList()))
    }

    @Test
    fun `zero flows are ignored`() {
        assertNull(xirr(listOf(flow(2025, 1, 1, -100), flow(2026, 1, 1, 0))))
        assertRate(0.10, xirr(listOf(flow(2025, 1, 1, -100_000), flow(2025, 6, 1, 0), flow(2026, 1, 1, 110_000))))
    }

    @Test
    fun `a return beyond the search range has no rate`() {
        // 1.00 becomes 1,000,000.00 in a year: 99,999,900 % is far above the +1000 % upper bound.
        assertNull(xirr(listOf(flow(2025, 1, 1, -100), flow(2026, 1, 1, 100_000_000))))
    }

    @Test
    fun `a nearly total loss still has a rate`() {
        // 1000 becomes 1 in a year: r = 0.001 - 1 = -99.9 %, inside the -99.99 % lower bound.
        assertRate(-0.999, xirr(listOf(flow(2025, 1, 1, -100_000), flow(2026, 1, 1, 100))))
    }

    @Test
    fun `flows on the very same day give no meaningful rate`() {
        assertEquals(null, xirr(listOf(flow(2025, 1, 1, -100), flow(2025, 1, 1, 100))))
    }
}
