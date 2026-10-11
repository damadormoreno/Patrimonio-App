package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceAnalyticsTest {
    private val today = LocalDate(2026, 10, 11)

    private fun pt(date: LocalDate, price: Double) = PricePoint(date, Price.fromDouble(price, Currency.EUR))

    private fun pt(year: Int, month: Int, day: Int, price: Double) = pt(LocalDate(year, month, day), price)

    /** A weekly series on Fridays whose k-th price is the previous one times `1 + returns[k]`. */
    private fun weekly(first: LocalDate, startPrice: Double, returns: List<Double>): List<PricePoint> {
        var price = startPrice
        var date = first
        val points = mutableListOf(pt(date, price))
        for (r in returns) {
            price *= 1.0 + r
            date = date.plus(7, DateTimeUnit.DAY)
            points += pt(date, price)
        }
        return points
    }

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 1e-6) {
        assertNotNull(actual)
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ---- ranges ---------------------------------------------------------------------------------------------

    private val rangePoints = listOf(
        pt(2020, 1, 1, 10.0),
        pt(2021, 10, 10, 20.0),
        pt(2021, 10, 11, 21.0),
        pt(2025, 10, 10, 30.0),
        pt(2025, 10, 11, 31.0),
        pt(2026, 4, 10, 40.0),
        pt(2026, 4, 11, 41.0),
        pt(2026, 9, 10, 50.0),
        pt(2026, 9, 11, 51.0),
        pt(2026, 10, 9, 52.0),
    )

    @Test
    fun `one month keeps points from the same day a month ago inclusive`() {
        // today 2026-10-11 minus one month = 2026-09-11: the 09-10 point is out, 09-11 is in.
        val dates = pointsIn(rangePoints, HistoryRange.ONE_MONTH, today).map { it.date }
        assertEquals(listOf(LocalDate(2026, 9, 11), LocalDate(2026, 10, 9)), dates)
    }

    @Test
    fun `six months one year and five years cut at their inclusive start`() {
        assertEquals(
            LocalDate(2026, 4, 11),
            pointsIn(rangePoints, HistoryRange.SIX_MONTHS, today).first().date,
        )
        assertEquals(
            LocalDate(2025, 10, 11),
            pointsIn(rangePoints, HistoryRange.ONE_YEAR, today).first().date,
        )
        assertEquals(
            LocalDate(2021, 10, 11),
            pointsIn(rangePoints, HistoryRange.FIVE_YEARS, today).first().date,
        )
    }

    @Test
    fun `max keeps every point and an empty series stays empty`() {
        assertEquals(rangePoints, pointsIn(rangePoints, HistoryRange.MAX, today))
        HistoryRange.entries.forEach { assertEquals(emptyList(), pointsIn(emptyList(), it, today)) }
    }

    @Test
    fun `range change reports first and last price and the relative change`() {
        val change = rangeChange(listOf(pt(2026, 1, 1, 100.0), pt(2026, 2, 1, 90.0), pt(2026, 3, 1, 125.0)))
        assertNotNull(change)
        assertEquals(pt(2026, 1, 1, 100.0), change.first)
        assertEquals(pt(2026, 3, 1, 125.0), change.last)
        // 125 - 100 = 25 EUR = 25_000_000 scaled; 125 / 100 - 1 = 0.25.
        assertEquals(25 * PRICE_SCALE, change.absoluteScaled)
        assertClose(0.25, change.relative)
    }

    @Test
    fun `range change is null below two points or from a zero price`() {
        assertNull(rangeChange(emptyList()))
        assertNull(rangeChange(listOf(pt(2026, 1, 1, 100.0))))
        assertNull(rangeChange(listOf(pt(2026, 1, 1, 0.0), pt(2026, 2, 1, 10.0))))
    }

    // ---- calendar years ---------------------------------------------------------------------------------------

    @Test
    fun `calendar year returns chain year ends and flag the first year as partial and the current as year to date`() {
        val points = listOf(
            pt(2023, 6, 1, 80.0),
            pt(2023, 12, 29, 100.0),
            pt(2024, 3, 1, 90.0),
            pt(2024, 12, 31, 110.0),
            pt(2025, 12, 30, 99.0),
            pt(2026, 3, 2, 105.0),
            pt(2026, 10, 9, 108.9),
        )
        val years = calendarYearReturns(points, today)
        assertEquals(listOf(2026, 2025, 2024, 2023), years.map { it.year })
        // 2026 YTD: 108.9 / 99 - 1 = 0.10; 2025: 99 / 110 - 1 = -0.10; 2024: 110 / 100 - 1 = 0.10;
        // 2023 (first, from the first point 80): 100 / 80 - 1 = 0.25.
        assertClose(0.10, years[0].returnRatio)
        assertClose(-0.10, years[1].returnRatio)
        assertClose(0.10, years[2].returnRatio)
        assertClose(0.25, years[3].returnRatio)
        assertEquals(listOf(false, false, false, true), years.map { it.partial })
        assertEquals(listOf(true, false, false, false), years.map { it.yearToDate })
    }

    @Test
    fun `a series that starts in the current year is both partial and year to date`() {
        val years = calendarYearReturns(listOf(pt(2026, 2, 2, 50.0), pt(2026, 9, 1, 60.0)), today)
        assertEquals(1, years.size)
        assertClose(0.20, years.single().returnRatio)
        assertTrue(years.single().partial)
        assertTrue(years.single().yearToDate)
    }

    @Test
    fun `a stale series has no year to date and a single point first year is skipped`() {
        val points = listOf(pt(2023, 5, 5, 10.0), pt(2024, 12, 31, 12.0), pt(2025, 6, 2, 15.0))
        val years = calendarYearReturns(points, today)
        // 2023 has a single point: nothing to measure. 2024 is measured from it: 12 / 10 - 1 = 0.2; 2025: 15/12 - 1.
        assertEquals(listOf(2025, 2024), years.map { it.year })
        assertClose(0.25, years[0].returnRatio)
        assertClose(0.20, years[1].returnRatio)
        assertTrue(years.none { it.yearToDate })
        assertEquals(emptyList(), calendarYearReturns(emptyList(), today))
    }

    // ---- annualized return ------------------------------------------------------------------------------------

    private val cagrPoints = listOf(
        pt(2021, 10, 5, 66.55),
        pt(2023, 10, 4, 100.0),
        pt(2025, 10, 6, 121.0),
        pt(2026, 10, 9, 133.1),
    )

    @Test
    fun `annualized return compounds over one three and five years using the nearest previous point`() {
        // Last date 2026-10-09. 1y target 2025-10-09 -> point 10-06 (3 days back): 133.1 / 121 = 1.1 -> 10 %.
        assertClose(0.10, annualizedReturn(cagrPoints, 1))
        // 3y target 2023-10-09 -> point 10-04 (5 days back): 133.1 / 100 = 1.331 = 1.1^3 -> 10 %.
        assertClose(0.10, annualizedReturn(cagrPoints, 3))
        // 5y target 2021-10-09 -> point 10-05 (4 days back): 133.1 / 66.55 = 2 -> 2^(1/5) - 1 = 0.148698355.
        assertClose(0.148698355, annualizedReturn(cagrPoints, 5), 1e-8)
    }

    @Test
    fun `annualized return is null when the history does not reach back far enough`() {
        val short = cagrPoints.drop(1)
        assertNull(annualizedReturn(short, 5))
        assertNotNull(annualizedReturn(short, 3))
        assertNull(annualizedReturn(emptyList(), 1))
    }

    @Test
    fun `annualized return looks back at most seven days and never borrows a later point`() {
        val last = pt(2026, 10, 9, 110.0)
        // Target 2025-10-09: a point 7 days earlier (10-02) is accepted, 8 days earlier (10-01) is not.
        assertClose(0.10, annualizedReturn(listOf(pt(2025, 10, 2, 100.0), last), 1))
        assertNull(annualizedReturn(listOf(pt(2025, 10, 1, 100.0), last), 1))
        // A point after the target (10-12) is not used even though it is closer.
        assertNull(annualizedReturn(listOf(pt(2025, 10, 12, 100.0), last), 1))
    }

    // ---- weekly closes ------------------------------------------------------------------------------------------

    @Test
    fun `weekly closes take the last point of each ISO week across a year boundary`() {
        val points = listOf(
            // Fri, week starting Mon 2024-12-23
            pt(2024, 12, 27, 100.0),
            // Mon of the ISO week 2025-W01
            pt(2024, 12, 30, 105.0),
            // Fri of the same week: the close
            pt(2025, 1, 3, 110.0),
            // Fri, week starting Mon 2025-01-06
            pt(2025, 1, 10, 100.0),
            // Sun, same week: the close
            pt(2025, 1, 12, 105.0),
            // Mon, next week
            pt(2025, 1, 13, 106.0),
        )
        val closes = weeklyCloses(points)
        assertEquals(
            listOf(LocalDate(2024, 12, 23), LocalDate(2024, 12, 30), LocalDate(2025, 1, 6), LocalDate(2025, 1, 13)),
            closes.map { it.weekStart },
        )
        assertEquals(listOf(100.0, 110.0, 105.0, 106.0), closes.map { it.point.price.scaled / PRICE_SCALE.toDouble() })
    }

    @Test
    fun `weekly returns only join consecutive weeks`() {
        val closes = weeklyCloses(
            listOf(
                pt(2024, 12, 27, 100.0),
                pt(2025, 1, 3, 110.0),
                pt(2025, 1, 10, 99.0),
                // two weeks missing in between
                pt(2025, 1, 31, 99.0),
                pt(2025, 2, 7, 108.9),
            ),
        )
        val returns = weeklyReturns(closes)
        // 110 / 100 - 1 = 0.1; 99 / 110 - 1 = -0.1; (gap) ; 108.9 / 99 - 1 = 0.1.
        assertEquals(
            listOf(LocalDate(2024, 12, 30), LocalDate(2025, 1, 6), LocalDate(2025, 2, 3)),
            returns.map { it.weekStart },
        )
        assertClose(0.1, returns[0].ratio)
        assertClose(-0.1, returns[1].ratio)
        assertClose(0.1, returns[2].ratio)
    }

    // ---- volatility -----------------------------------------------------------------------------------------

    private fun alternating(count: Int) = List(count) { if (it % 2 == 0) 0.10 else -0.10 }

    @Test
    fun `volatility is the annualized sample deviation of the weekly returns`() {
        val points = weekly(LocalDate(2025, 10, 10), 1000.0, alternating(26))
        val volatility = volatility(points, today)
        assertNotNull(volatility)
        assertEquals(26, volatility.weeks)
        // 13 returns of +0.1 and 13 of -0.1 (mean ~0): sample variance = 26 * 0.01 / 25 = 0.0104;
        // annualized = sqrt(0.0104 * 52) = sqrt(0.5408) = 0.73539.
        assertClose(sqrt(0.5408), volatility.annualized, 1e-4)
    }

    @Test
    fun `volatility is null below twenty six weekly returns`() {
        assertNull(volatility(weekly(LocalDate(2025, 10, 10), 1000.0, alternating(25)), today))
        assertNull(volatility(emptyList(), today))
    }

    @Test
    fun `volatility falls back to all history when nothing recent is available`() {
        val old = weekly(LocalDate(2019, 3, 1), 1000.0, alternating(26))
        val volatility = volatility(old, today)
        assertNotNull(volatility)
        assertEquals(26, volatility.weeks)
        assertClose(sqrt(0.5408), volatility.annualized, 1e-4)
    }

    @Test
    fun `volatility only looks at the last three years when there is enough recent history`() {
        val noisyOld = weekly(LocalDate(2019, 3, 1), 1000.0, alternating(26))
        // Flat every Friday from 2023-10-13 to 2026-10-09: 156 weeks after the first, all zero returns.
        val flat = List(157) { pt(LocalDate(2023, 10, 13).plus(7 * it, DateTimeUnit.DAY), 100.0) }
        val volatility = volatility(noisyOld + flat, today)
        assertNotNull(volatility)
        assertEquals(156, volatility.weeks)
        assertClose(0.0, volatility.annualized)
    }

    // ---- maximum drawdown -------------------------------------------------------------------------------------

    @Test
    fun `max drawdown finds the deepest peak to trough fall even when the price recovers`() {
        val points = listOf(
            pt(2026, 1, 1, 100.0),
            // peak
            pt(2026, 2, 1, 120.0),
            // trough: 90 / 120 - 1 = -25 %
            pt(2026, 3, 1, 90.0),
            pt(2026, 4, 1, 110.0),
            // new peak
            pt(2026, 5, 1, 130.0),
            // 104 / 130 - 1 = -20 %
            pt(2026, 6, 1, 104.0),
            // recovered
            pt(2026, 7, 1, 135.0),
        )
        val drawdown = maxDrawdown(points)
        assertNotNull(drawdown)
        assertClose(0.25, drawdown.fall)
        assertEquals(LocalDate(2026, 2, 1), drawdown.peak.date)
        assertEquals(LocalDate(2026, 3, 1), drawdown.trough.date)
    }

    @Test
    fun `max drawdown is zero for a series that never falls and null below two points`() {
        val rising = maxDrawdown(listOf(pt(2026, 1, 1, 10.0), pt(2026, 2, 1, 10.0), pt(2026, 3, 1, 12.0)))
        assertNotNull(rising)
        assertEquals(0.0, rising.fall)
        assertNull(maxDrawdown(listOf(pt(2026, 1, 1, 10.0))))
        assertNull(maxDrawdown(emptyList()))
    }
}
