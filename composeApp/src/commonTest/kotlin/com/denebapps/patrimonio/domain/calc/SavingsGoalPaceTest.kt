package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.calc.SavingsGoalPace.Pace
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SavingsGoalPaceTest {
    private val today = LocalDate(2026, 10, 9)

    @Test
    fun `without a target date only the remaining amount is reported`() {
        val pace = pace(target = 500_000, progress = 120_000, targetDate = null)

        assertEquals(SavingsGoalPace.Remaining(Money(380_000), pace = null), pace)
    }

    @Test
    fun `a target date in an earlier month is reported as passed`() {
        val pace = pace(target = 500_000, progress = 120_000, targetDate = LocalDate(2026, 9, 30))

        assertEquals(SavingsGoalPace.Remaining(Money(380_000), Pace.DatePassed), pace)
    }

    @Test
    fun `an earlier day of the current month is already passed`() {
        val pace = pace(target = 500_000, progress = 120_000, targetDate = LocalDate(2026, 10, 8))

        assertEquals(SavingsGoalPace.Remaining(Money(380_000), Pace.DatePassed), pace)
    }

    @Test
    fun `an exceeded goal reports the surplus`() {
        val pace = pace(target = 500_000, progress = 525_000, targetDate = LocalDate(2027, 3, 15))

        assertEquals(SavingsGoalPace.Reached(surplus = Money(25_000)), pace)
    }

    @Test
    fun `an exactly reached goal reports a zero surplus`() {
        val pace = pace(target = 500_000, progress = 500_000, targetDate = LocalDate(2020, 1, 1))

        assertEquals(SavingsGoalPace.Reached(surplus = Money.ZERO), pace)
    }

    @Test
    fun `months left count the current month through the target month inclusive`() {
        // 2026-10 .. 2027-03 = 6 months; 380.000,00 € / 6 = 63.333,33… → 63.334 €.
        val pace = pace(target = 50_000_000, progress = 12_000_000, targetDate = LocalDate(2027, 3, 15))

        assertEquals(
            SavingsGoalPace.Remaining(
                Money(38_000_000),
                Pace.Monthly(amount = Money(6_333_400), until = YearMonth(2027, 3)),
            ),
            pace,
        )
    }

    @Test
    fun `in the target month the whole remaining is due, rounded up to a whole unit`() {
        val pace = pace(target = 100_000, progress = 20_050, targetDate = LocalDate(2026, 10, 9))

        assertEquals(
            SavingsGoalPace.Remaining(
                Money(79_950),
                Pace.Monthly(amount = Money(80_000), until = YearMonth(2026, 10)),
            ),
            pace,
        )
    }

    @Test
    fun `a whole remaining split evenly is not rounded up`() {
        val pace = pace(target = 60_000, progress = 0, targetDate = LocalDate(2027, 3, 1))

        assertEquals(
            SavingsGoalPace.Remaining(
                Money(60_000),
                Pace.Monthly(amount = Money(10_000), until = YearMonth(2027, 3)),
            ),
            pace,
        )
    }

    @Test
    fun `USD monthly pace rounds up to whole dollars`() {
        // 500,01 $ over 6 months = 83,33… $ → 84 $.
        val pace =
            pace(
                target = 50_001,
                progress = 0,
                targetDate = LocalDate(2027, 3, 31),
                currency = Currency.USD,
            )

        assertEquals(
            SavingsGoalPace.Remaining(
                Money(50_001),
                Pace.Monthly(amount = Money(8_400), until = YearMonth(2027, 3)),
            ),
            pace,
        )
    }

    @Test
    fun `JPY monthly pace rounds up to whole yen without minor units`() {
        // 100.001 ¥ over 6 months = 16.666,83… ¥ → 16.667 ¥.
        val pace =
            pace(
                target = 100_001,
                progress = 0,
                targetDate = LocalDate(2027, 3, 31),
                currency = Currency.JPY,
            )

        assertEquals(
            SavingsGoalPace.Remaining(
                Money(100_001),
                Pace.Monthly(amount = Money(16_667), until = YearMonth(2027, 3)),
            ),
            pace,
        )
    }

    @Test
    fun `month of year label is lowercase with the year`() {
        assertEquals("marzo de 2027", monthOfYearLabelEs(YearMonth(2027, 3)))
        assertEquals("enero de 2027", monthOfYearLabelEs(YearMonth(2027, 1)))
    }

    private fun pace(
        target: Long,
        progress: Long,
        targetDate: LocalDate?,
        currency: Currency = Currency.EUR,
    ): SavingsGoalPace = savingsGoalPace(
        target = Money(target),
        progress = Money(progress),
        currency = currency,
        targetDate = targetDate,
        today = today,
    )
}
