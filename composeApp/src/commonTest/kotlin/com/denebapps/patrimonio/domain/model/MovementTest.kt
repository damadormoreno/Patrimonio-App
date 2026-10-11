package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MovementTest {
    private val day = date(2025, 3, 10)

    @Test
    fun `same-day movements are processed buy, opening, transfer in, fee, dividend, transfer out, sell`() {
        val shuffled = listOf(
            sell("sell", day, units(1), 100, sequence = 1),
            transferOut("out", day, units(1), "t", sequence = 2),
            dividend("div", day, 100, sequence = 3),
            feeMovement("fee", day, 100, sequence = 4),
            transferIn("in", day, units(1), "t2", sequence = 5),
            opening("open", day, units(1), 100, sequence = 6),
            buy("buy", day, units(1), 100, sequence = 7),
        )

        assertEquals(
            listOf("buy", "open", "in", "fee", "div", "out", "sell"),
            shuffled.inProcessingOrder().map { it.id },
        )
    }

    @Test
    fun `movements are ordered by date first, then type, then entry sequence`() {
        val movements = listOf(
            buy("late-buy", date(2025, 3, 11), units(1), 100, sequence = 1),
            sell("early-sell", date(2025, 3, 9), units(1), 100, sequence = 2),
            buy("second-buy", day, units(1), 100, sequence = 9),
            buy("first-buy", day, units(1), 100, sequence = 3),
        )

        assertEquals(
            listOf("early-sell", "first-buy", "second-buy", "late-buy"),
            movements.inProcessingOrder().map { it.id },
        )
    }

    @Test
    fun `an opening balance may have an unknown cost`() {
        val movement = opening("o", day, units(10), costCents = null)

        assertNull(movement.amount)
        assertNull(movement.amountEur)
    }

    @Test
    fun `an opening balance cost and its EUR cost are both known or both unknown`() {
        assertFailsWith<IllegalArgumentException> {
            Movement(
                id = "o",
                holdingId = FUND_A,
                type = MovementType.OPENING_BALANCE,
                date = day,
                units = units(1),
                amount = eurAmount(100),
                amountEur = null,
            )
        }
    }

    @Test
    fun `a buy needs units, an amount and a EUR amount`() {
        val template = buy("b", day, units(1), 100)

        assertFailsWith<IllegalArgumentException> { template.copy(units = null) }
        assertFailsWith<IllegalArgumentException> { template.copy(amount = null) }
        assertFailsWith<IllegalArgumentException> { template.copy(amountEur = null) }
    }

    @Test
    fun `a dividend or a fee has no units`() {
        assertFailsWith<IllegalArgumentException> { dividend("d", day, 100).copy(units = units(1)) }
        assertFailsWith<IllegalArgumentException> { feeMovement("f", day, 100).copy(units = units(1)) }
    }

    @Test
    fun `amounts and fees must not be negative`() {
        assertFailsWith<IllegalArgumentException> { buy("b", day, units(1), -1) }
        assertFailsWith<IllegalArgumentException> { buy("b", day, units(1), 100, feeCents = -1) }
        assertFailsWith<IllegalArgumentException> { dividend("d", day, -5) }
    }

    @Test
    fun `a transfer leg needs a transfer id and carries no amount or fee`() {
        val out = transferOut("o", day, units(1), "t")

        assertFailsWith<IllegalArgumentException> { out.copy(transferId = null) }
        assertFailsWith<IllegalArgumentException> { out.copy(transferId = " ") }
        assertFailsWith<IllegalArgumentException> { out.copy(amount = eurAmount(1), amountEur = Money(1)) }
        assertFailsWith<IllegalArgumentException> { out.copy(fee = Money(1), feeEur = Money(1)) }
    }

    @Test
    fun `only transfer legs may carry a transfer id`() {
        assertFailsWith<IllegalArgumentException> { buy("b", day, units(1), 100).copy(transferId = "t") }
    }

    @Test
    fun `a dividend or an opening balance has no separate fee`() {
        assertFailsWith<IllegalArgumentException> { dividend("d", day, 100).copy(fee = Money(1)) }
        assertFailsWith<IllegalArgumentException> { opening("o", day, units(1), 100).copy(feeEur = Money(1)) }
    }

    @Test
    fun `ids must not be blank`() {
        assertFailsWith<IllegalArgumentException> { buy(" ", day, units(1), 100) }
        assertFailsWith<IllegalArgumentException> { buy("b", day, units(1), 100, holding = "") }
    }
}
