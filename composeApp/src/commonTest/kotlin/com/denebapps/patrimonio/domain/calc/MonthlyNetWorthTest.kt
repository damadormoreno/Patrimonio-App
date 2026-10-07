package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonthlyNetWorthTest {
    private fun snapshot(month: YearMonth, netWorthMinor: Long) =
        NetWorthSnapshot(month, assets = Money(netWorthMinor), liabilities = Money.ZERO)

    @Test
    fun `months run continuously from the first snapshot and carry the value over gaps`() {
        val history = monthlyNetWorth(
            snapshots = listOf(snapshot(YearMonth(2026, 11), 1_000_00), snapshot(YearMonth(2027, 2), 1_500_00)),
            currentMonth = YearMonth(2027, 3),
            currentNetWorth = Money(1_400_00),
        )

        assertEquals(
            listOf(
                YearMonth(2026, 11),
                YearMonth(2026, 12),
                YearMonth(2027, 1),
                YearMonth(2027, 2),
                YearMonth(2027, 3),
            ),
            history.map { it.month },
        )
        assertEquals(
            listOf(1_000_00L, 1_000_00L, 1_000_00L, 1_500_00L, 1_400_00L),
            history.map { it.netWorth.minorUnits },
        )
        assertEquals(listOf(true, false, false, true, true), history.map { it.recorded })
        assertEquals(listOf(null, 0L, 0L, 500_00L, -100_00L), history.map { it.change?.minorUnits })
        assertEquals(
            listOf(null, MonthTrend.STABLE, MonthTrend.STABLE, MonthTrend.UP, MonthTrend.DOWN),
            history.map { it.trend },
        )
    }

    @Test
    fun `the current month uses the live net worth instead of its snapshot`() {
        val history = monthlyNetWorth(
            snapshots = listOf(snapshot(YearMonth(2026, 9), 100_00), snapshot(YearMonth(2026, 10), 150_00)),
            currentMonth = YearMonth(2026, 10),
            currentNetWorth = Money(180_00),
        )

        assertEquals(Money(180_00), history.last().netWorth)
        assertEquals(Money(80_00), history.last().change)
    }

    @Test
    fun `changes under half a percent of the previous month are stable`() {
        // July is +0.4999 % (stable), August +0.5 % of 10 049,99 (up), September -0.4999 % (stable).
        val history = monthlyNetWorth(
            snapshots = listOf(
                snapshot(YearMonth(2026, 6), 10_000_00),
                snapshot(YearMonth(2026, 7), 10_049_99),
                snapshot(YearMonth(2026, 8), 10_100_49),
            ),
            currentMonth = YearMonth(2026, 9),
            currentNetWorth = Money(10_050_00),
        )

        assertEquals(
            listOf(null, MonthTrend.STABLE, MonthTrend.UP, MonthTrend.STABLE),
            history.map { it.trend },
        )
    }

    @Test
    fun `from a zero net worth any change counts and no change is stable`() {
        val history = monthlyNetWorth(
            snapshots = listOf(snapshot(YearMonth(2026, 1), 0), snapshot(YearMonth(2026, 2), 0)),
            currentMonth = YearMonth(2026, 3),
            currentNetWorth = Money(-1),
        )

        assertEquals(listOf(null, MonthTrend.STABLE, MonthTrend.DOWN), history.map { it.trend })
    }

    @Test
    fun `without snapshots there is just the current month`() {
        val history = monthlyNetWorth(emptyList(), YearMonth(2026, 10), Money(500_00))

        assertEquals(1, history.size)
        assertEquals(Money(500_00), history.single().netWorth)
        assertNull(history.single().change)
    }

    @Test
    fun `snapshots after the current month are ignored`() {
        val history = monthlyNetWorth(
            snapshots = listOf(snapshot(YearMonth(2026, 10), 100_00), snapshot(YearMonth(2026, 12), 900_00)),
            currentMonth = YearMonth(2026, 11),
            currentNetWorth = Money(120_00),
        )

        assertEquals(listOf(YearMonth(2026, 10), YearMonth(2026, 11)), history.map { it.month })
    }
}
