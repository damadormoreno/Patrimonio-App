package com.denebapps.patrimonio.domain.time

import com.denebapps.patrimonio.domain.calc.MutableTestClock
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class MonthBoundaryTest {
    @Test
    fun `next boundary delay targets exact first instant of next month`() {
        assertEquals(
            60_000L,
            millisecondsUntilNextMonthBoundary(Instant.parse("2026-05-31T23:59:00Z"), TimeZone.UTC),
        )
        assertEquals(
            60_000L,
            millisecondsUntilNextMonthBoundary(Instant.parse("2026-12-31T23:59:00Z"), TimeZone.UTC),
        )
    }

    @Test
    fun `month flow emits again after exact boundary sleeper completes`() = runTest {
        val clock = MutableTestClock("2026-05-31T23:59:00Z")
        val requestedDelays = mutableListOf<Long>()

        val months = currentMonthFlow(clock, { TimeZone.UTC }) { delayMillis ->
            requestedDelays += delayMillis
            clock.instant = Instant.parse("2026-06-01T00:00:00Z")
        }.take(2).toList()

        assertEquals(listOf(YearMonth(2026, 5), YearMonth(2026, 6)), months)
        assertEquals(listOf(60_000L), requestedDelays)
    }

    @Test
    fun `non UTC zone controls the exact year boundary instant`() {
        val kathmandu = TimeZone.of("Asia/Kathmandu")

        assertEquals(
            60_000L,
            millisecondsUntilNextMonthBoundary(Instant.parse("2026-12-31T18:14:00Z"), kathmandu),
        )
    }

    @Test
    fun `month flow rolls year at the supplied non UTC boundary`() = runTest {
        val kathmandu = TimeZone.of("Asia/Kathmandu")
        val clock = MutableTestClock("2026-12-31T18:14:00Z")
        val requestedDelays = mutableListOf<Long>()

        val months = currentMonthFlow(clock, { kathmandu }) { delayMillis ->
            requestedDelays += delayMillis
            clock.instant = Instant.parse("2026-12-31T18:15:00Z")
        }.take(2).toList()

        assertEquals(listOf(YearMonth(2026, 12), YearMonth(2027, 1)), months)
        assertEquals(listOf(60_000L), requestedDelays)
    }
}
