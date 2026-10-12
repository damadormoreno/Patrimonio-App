package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.FUND_B
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.dividend
import com.denebapps.patrimonio.domain.model.feeMovement
import com.denebapps.patrimonio.domain.model.opening
import com.denebapps.patrimonio.domain.model.sell
import com.denebapps.patrimonio.domain.model.transferIn
import com.denebapps.patrimonio.domain.model.transferOut
import com.denebapps.patrimonio.domain.model.units
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The cash flows of a position as the user lived them: money out of the pocket negative, money back positive.
class PositionCashFlowsTest {
    private val today = date(2026, 1, 1)

    private fun flows(
        holding: String,
        movements: List<Movement>,
        value: Long,
        transferValue: (Movement) -> Money? = { null },
    ): List<CashFlow>? {
        val ledger = computeLedgers(movements).getValue(holding)
        return positionCashFlows(
            movements = movements.filter { it.holdingId == holding },
            ledger = ledger,
            today = today,
            currentValueEur = Money(value),
            transferValueEur = transferValue,
        )
    }

    private fun flow(day: LocalDate, cents: Long, transferId: String? = null) = CashFlow(day, Money(cents), transferId)

    @Test
    fun `each movement type becomes its flow and the current value closes the position`() {
        // opening cost 1000.00 out; buy 600.00 + 5.00 fee out; dividend 20.00 in; fee 3.00 out;
        // sell 500.00 - 2.00 fee in; value today 1300.00 in.
        val movements = listOf(
            opening("o1", date(2025, 1, 1), units(10), costCents = 100_000),
            buy("b1", date(2025, 6, 1), units(5), cents = 60_000, feeCents = 500),
            dividend("d1", date(2025, 9, 1), 2_000),
            feeMovement("f1", date(2025, 10, 1), 300),
            sell("s1", date(2025, 12, 1), units(4), cents = 50_000, feeCents = 200),
        )

        assertEquals(
            listOf(
                flow(date(2025, 1, 1), -100_000),
                flow(date(2025, 6, 1), -60_500),
                flow(date(2025, 9, 1), 2_000),
                flow(date(2025, 10, 1), -300),
                flow(date(2025, 12, 1), 49_800),
                flow(today, 130_000),
            ),
            flows(FUND_A, movements, value = 130_000),
        )
    }

    @Test
    fun `a closed position has no closing flow`() {
        val movements = listOf(
            buy("b1", date(2025, 1, 1), units(1), cents = 100_000),
            sell("s1", date(2025, 7, 1), units(1), cents = 110_000),
        )

        val result = assertNotNull(flows(FUND_A, movements, value = 0))

        assertEquals(listOf(flow(date(2025, 1, 1), -100_000), flow(date(2025, 7, 1), 110_000)), result)
        // 2025-01-01 -> 2025-07-01 is 181 days: 1.1 over 181/365 of a year, annualized.
        assertTrue(abs(xirr(result)!! - (1.1.pow(365.0 / 181.0) - 1)) < 1e-6)
    }

    @Test
    fun `a position with an unknown opening cost has no flows`() {
        val movements = listOf(
            opening("o1", date(2025, 1, 1), units(10), costCents = null),
            buy("b1", date(2025, 6, 1), units(5), cents = 60_000),
        )

        assertNull(flows(FUND_A, movements, value = 130_000))
    }

    @Test
    fun `a transfer is valued at the caller's market value on its date`() {
        val movements = transferScenario()

        val a = flows(FUND_A, movements, value = 0, transferValue = { Money(110_000) })
        val b = flows(FUND_B, movements, value = 121_000, transferValue = { Money(110_000) })

        assertEquals(listOf(flow(date(2025, 1, 1), -100_000), flow(date(2025, 7, 1), 110_000, "t1")), a)
        assertEquals(listOf(flow(date(2025, 7, 1), -110_000, "t1"), flow(today, 121_000)), b)
    }

    @Test
    fun `a transfer without a market value falls back to the carried cost`() {
        val movements = transferScenario()

        val a = flows(FUND_A, movements, value = 0)
        val b = flows(FUND_B, movements, value = 121_000)

        assertEquals(flow(date(2025, 7, 1), 100_000, "t1"), a!![1])
        assertEquals(flow(date(2025, 7, 1), -100_000, "t1"), b!![0])
    }

    @Test
    fun `a transfer of units with unknown cost and no market value has no flows`() {
        val movements = listOf(
            opening("o1", date(2025, 1, 1), units(10), costCents = null),
            transferOut("out", date(2025, 7, 1), units(10), "t1"),
            transferIn("in", date(2025, 7, 1), units(10), "t1", holding = FUND_B),
        )

        assertNull(flows(FUND_B, movements, value = 1_000))
    }

    @Test
    fun `transfers between two included positions cancel in the combined flows`() {
        // A: -1000.00 on 2025-01-01, +1100.00 transfer out. B: -1100.00 transfer in, +1210.00 today.
        // Both transfer legs drop out: -1000.00 -> +1210.00 in 365 days = 21 %.
        val movements = transferScenario()
        val a = flows(FUND_A, movements, value = 0, transferValue = { Money(110_000) })!!
        val b = flows(FUND_B, movements, value = 121_000, transferValue = { Money(110_000) })!!

        val combined = combineCashFlows(listOf(a, b))

        assertEquals(listOf(flow(date(2025, 1, 1), -100_000), flow(today, 121_000)), combined.sortedBy { it.date })
        assertTrue(abs(xirr(combined)!! - 0.21) < 1e-6)
    }

    @Test
    fun `a transfer to a position left out of the combined flows stays at its market value`() {
        val movements = transferScenario()
        val a = flows(FUND_A, movements, value = 0, transferValue = { Money(110_000) })!!

        assertEquals(a, combineCashFlows(listOf(a)))
    }

    private fun transferScenario() = listOf(
        buy("b1", date(2025, 1, 1), units(10), cents = 100_000),
        transferOut("out", date(2025, 7, 1), units(10), "t1"),
        transferIn("in", date(2025, 7, 1), units(8), "t1", holding = FUND_B),
    )
}
