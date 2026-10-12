package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.Lot
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.dividend
import com.denebapps.patrimonio.domain.model.feeMovement
import com.denebapps.patrimonio.domain.model.opening
import com.denebapps.patrimonio.domain.model.sell
import com.denebapps.patrimonio.domain.model.units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// All amounts are EUR cents. Every expected number below is worked out by hand in the comment above it.
class MovementLedgerTest {
    private fun ledgerOf(vararg movements: Movement): PositionLedger =
        computeLedgers(movements.toList()).getValue(FUND_A)

    @Test
    fun `a buy opens a lot whose cost is the amount plus the fee`() {
        // 10 units for 1000.00 + 5.00 fee = 1005.00
        val ledger = ledgerOf(buy("b1", date(2025, 1, 10), units(10), cents = 100_000, feeCents = 500))

        assertEquals(listOf(Lot(date(2025, 1, 10), units(10), Money(100_500))), ledger.lots)
        assertEquals(UnitsBalance(10_000_000), ledger.unitsHeld)
        assertEquals(Money(500), ledger.tradeFeesEur)
    }

    @Test
    fun `a sell consumes the oldest lot first and takes the cost pro rata from a partly used lot`() {
        // Lot 1: 10 u, cost 1000.00 + 5.00 fee = 100_500.   Lot 2: 5 u, cost 600.00 = 60_000.
        // Sell 12 u for 1500.00 - 10.00 fee = 149_000 proceeds. Consumes lot 1 (10 u) and 2 u of lot 2.
        //   cost of the 2 u of lot 2 = 60_000 * 2/5 = 24_000        -> consumed cost 100_500 + 24_000 = 124_500
        //   proceeds of the 10 u piece = 149_000 * 10/12 = 124_166.67 -> 124_167; the 2 u piece gets 24_833
        //   gains: 124_167 - 100_500 = 23_667 and 24_833 - 24_000 = 833 -> 24_500 = 149_000 - 124_500
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000, feeCents = 500),
            buy("b2", date(2025, 2, 10), units(5), cents = 60_000),
            sell("s1", date(2025, 3, 10), units(12), cents = 150_000, feeCents = 1_000),
        )

        assertEquals(listOf(Lot(date(2025, 2, 10), units(3), Money(36_000))), ledger.lots)
        assertEquals(Money(24_500), ledger.realized.knownGainEur)
        assertEquals(Money(124_500), ledger.realized.knownCostEur)
        assertEquals(Money(24_500), ledger.realized.totalGainEur)
        assertEquals(ledger.realized, ledger.realizedBySale.getValue("s1"))
        assertEquals(Money(1_500), ledger.tradeFeesEur)
    }

    @Test
    fun `sells across several lots add up and selling everything leaves zero units`() {
        // After the first sale (previous test) lot 2 keeps 3 u at 36_000.
        // Sell 3 u for 450.00: gain 45_000 - 36_000 = 9_000; total realized 24_500 + 9_000 = 33_500.
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000, feeCents = 500),
            buy("b2", date(2025, 2, 10), units(5), cents = 60_000),
            sell("s1", date(2025, 3, 10), units(12), cents = 150_000, feeCents = 1_000),
            sell("s2", date(2025, 4, 10), units(3), cents = 45_000),
        )

        assertTrue(ledger.lots.isEmpty())
        assertEquals(UnitsBalance.ZERO, ledger.unitsHeld)
        assertNull(ledger.unitsHeld.toUnits())
        assertEquals(Money(33_500), ledger.realized.totalGainEur)
        assertEquals(Money(9_000), ledger.realizedBySale.getValue("s2").totalGainEur)
        assertEquals(UnitsBalance.ZERO, ledger.unitsOn(date(2025, 4, 10)))
        assertEquals(Money.ZERO, ledger.unrealizedGain(Money.ZERO).knownGainEur)
    }

    @Test
    fun `a position that sold everything can be bought again`() {
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(2), cents = 20_000),
            sell("s1", date(2025, 2, 10), units(2), cents = 30_000),
            buy("b2", date(2025, 3, 10), units(1), cents = 11_000),
        )

        assertEquals(listOf(Lot(date(2025, 3, 10), units(1), Money(11_000))), ledger.lots)
        assertEquals(Money(10_000), ledger.realized.totalGainEur)
    }

    @Test
    fun `a partly consumed lot splits the cost without losing a cent`() {
        // Lot: 3 u at 100 cents. Sell 1 u: consumed cost 100 * 1/3 = 33.33 -> 33; the rest keeps 67 (not 66.67).
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(3), cents = 100),
            sell("s1", date(2025, 2, 10), units(1), cents = 50),
        )

        assertEquals(listOf(Lot(date(2025, 1, 10), units(2), Money(67))), ledger.lots)
        assertEquals(Money(17), ledger.realized.totalGainEur) // 50 - 33
        assertEquals(Money(33), ledger.realized.knownCostEur)
    }

    @Test
    fun `a dividend is income that leaves the cost, the units and the realized gain alone`() {
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000),
            dividend("d1", date(2025, 5, 1), 1_234),
            dividend("d2", date(2025, 6, 1), 766),
        )

        assertEquals(Money(2_000), ledger.dividendsEur)
        assertEquals(Money(100_000), ledger.costBasis.knownCostEur)
        assertEquals(UnitsBalance(10_000_000), ledger.unitsHeld)
        assertEquals(Money.ZERO, ledger.realized.knownGainEur)
    }

    @Test
    fun `a standalone fee is tracked apart from realized gains and trade fees`() {
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000, feeCents = 500),
            feeMovement("f1", date(2025, 5, 1), 300),
        )

        assertEquals(Money(300), ledger.standaloneFeesEur)
        assertEquals(Money(500), ledger.tradeFeesEur)
        assertEquals(Money.ZERO, ledger.realized.knownGainEur)
        assertEquals(Money(100_500), ledger.costBasis.knownCostEur)
    }

    @Test
    fun `an opening balance with a known cost is consumed before later buys`() {
        // Opening 8 u cost 800.00 = 80_000; buy 2 u for 250.00 = 25_000. Sell 9 u for 1100.00 = 110_000.
        // Consumes 8 u (80_000) + 1 u of the buy (25_000 * 1/2 = 12_500) -> cost 92_500, gain 17_500.
        // Remaining: 1 u, cost 12_500.
        val ledger = ledgerOf(
            opening("o1", date(2024, 12, 31), units(8), costCents = 80_000),
            buy("b1", date(2025, 1, 5), units(2), cents = 25_000),
            sell("s1", date(2025, 2, 1), units(9), cents = 110_000),
        )

        assertEquals(Money(17_500), ledger.realized.totalGainEur)
        assertEquals(listOf(Lot(date(2025, 1, 5), units(1), Money(12_500))), ledger.lots)
    }

    @Test
    fun `an unknown opening cost makes the realized gain of those units unknown but keeps the known part`() {
        // Opening 10 u, cost unknown; buy 5 u for 500.00 = 50_000. Sell 12 u for 1260.00 = 126_000:
        // 10 u from the opening (unknown cost) and 2 u of the buy (cost 50_000 * 2/5 = 20_000).
        // Proceeds split 126_000 * 10/12 = 105_000 (unknown part) and 21_000 (known part): known gain 1_000.
        val ledger = ledgerOf(
            opening("o1", date(2025, 1, 1), units(10), costCents = null),
            buy("b1", date(2025, 2, 1), units(5), cents = 50_000),
            sell("s1", date(2025, 3, 1), units(12), cents = 126_000),
        )

        val realized = ledger.realized
        assertEquals(Money(1_000), realized.knownGainEur)
        assertEquals(Money(20_000), realized.knownCostEur)
        assertEquals(Money(105_000), realized.unknownValueEur)
        assertEquals(UnitsBalance(10_000_000), realized.unknownUnits)
        assertFalse(realized.isComplete)
        assertNull(realized.totalGainEur)
        assertEquals(listOf(Lot(date(2025, 2, 1), units(3), Money(30_000))), ledger.lots)
    }

    @Test
    fun `unrealized gain is the value minus the cost of the open lots`() {
        // Lots: 10 u cost 1005.00 and 5 u cost 600.00 -> cost 1605.00 = 160_500; value now 1800.00 -> gain 19_500.
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000, feeCents = 500),
            buy("b2", date(2025, 2, 10), units(5), cents = 60_000),
        )

        val unrealized = ledger.unrealizedGain(Money(180_000))

        assertEquals(Money(19_500), unrealized.totalGainEur)
        assertEquals(Money(160_500), unrealized.knownCostEur)
        assertEquals(19_500.0 / 160_500.0, unrealized.returnRatio)
    }

    @Test
    fun `unrealized gain is unknown while an open lot has no cost, and the known subset is still reported`() {
        // Opening 10 u unknown cost + buy 5 u for 500.00 = 50_000. Value now 1800.00 = 180_000 for 15 u:
        // known part 5/15 of the value = 60_000 -> known gain 10_000; unknown part 120_000 for 10 u.
        val ledger = ledgerOf(
            opening("o1", date(2025, 1, 1), units(10), costCents = null),
            buy("b1", date(2025, 2, 1), units(5), cents = 50_000),
        )

        val unrealized = ledger.unrealizedGain(Money(180_000))

        assertNull(unrealized.totalGainEur)
        assertEquals(Money(10_000), unrealized.knownGainEur)
        assertEquals(Money(50_000), unrealized.knownCostEur)
        assertEquals(Money(120_000), unrealized.unknownValueEur)
        assertEquals(UnitsBalance(10_000_000), unrealized.unknownUnits)
        assertEquals(UnitsBalance(10_000_000), ledger.costBasis.unknownCostUnits)
        assertFalse(ledger.costBasis.isComplete)
    }

    @Test
    fun `selling more than the position holds is an error with the date and the units available`() {
        val error = assertFailsWith<InsufficientUnitsException> {
            ledgerOf(
                buy("b1", date(2025, 1, 1), units(5), cents = 5_000),
                sell("s1", date(2025, 2, 1), units(6), cents = 6_000),
            )
        }

        assertEquals(date(2025, 2, 1), error.date)
        assertEquals(UnitsBalance(5_000_000), error.available)
        assertEquals(units(6), error.requested)
    }

    @Test
    fun `selling before the first buy is an error`() {
        val error = assertFailsWith<InsufficientUnitsException> {
            ledgerOf(
                sell("s1", date(2025, 1, 1), units(1), cents = 100),
                buy("b1", date(2025, 1, 2), units(1), cents = 100),
            )
        }

        assertEquals(UnitsBalance.ZERO, error.available)
    }

    @Test
    fun `a same-day sell entered before its buy still works because buys are processed first`() {
        val ledger = ledgerOf(
            sell("s1", date(2025, 1, 1), units(1), cents = 110, sequence = 1),
            buy("b1", date(2025, 1, 1), units(1), cents = 100, sequence = 2),
        )

        assertEquals(UnitsBalance.ZERO, ledger.unitsHeld)
        assertEquals(Money(10), ledger.realized.totalGainEur)
    }

    @Test
    fun `selling again after selling everything is an error`() {
        assertFailsWith<InsufficientUnitsException> {
            ledgerOf(
                buy("b1", date(2025, 1, 1), units(1), cents = 100),
                sell("s1", date(2025, 2, 1), units(1), cents = 100),
                sell("s2", date(2025, 3, 1), units(1, 1), cents = 100),
            )
        }
    }

    @Test
    fun `a dividend or a fee before the first buy or opening balance is an error`() {
        val dividendError = assertFailsWith<MovementBeforeStartException> {
            ledgerOf(
                dividend("d1", date(2025, 1, 1), 100),
                buy("b1", date(2025, 1, 2), units(1), cents = 100),
            )
        }
        assertEquals(date(2025, 1, 1), dividendError.date)

        assertFailsWith<MovementBeforeStartException> {
            ledgerOf(feeMovement("f1", date(2025, 1, 1), 100), opening("o1", date(2025, 1, 2), units(1), null))
        }
    }

    @Test
    fun `validation errors carry a message in Spanish`() {
        val error = assertFailsWith<InsufficientUnitsException> {
            ledgerOf(buy("b1", date(2025, 1, 1), units(5), 5_000), sell("s1", date(2025, 2, 1), units(6, 500_000), 1))
        }

        assertEquals("El 01/02/2025 intentas dar salida a 6,5 unidades pero solo tienes 5.", error.message)
        assertEquals(null, movementValidationError(listOf(buy("b1", date(2025, 1, 1), units(5), 5_000))))
    }

    @Test
    fun `units held are tracked by date with one point per day`() {
        val ledger = ledgerOf(
            buy("b1", date(2025, 1, 10), units(10), cents = 100_000),
            buy("b2", date(2025, 2, 10), units(5), cents = 60_000),
            sell("s1", date(2025, 3, 10), units(12), cents = 150_000),
            sell("s2", date(2025, 3, 10), units(1), cents = 10_000, sequence = 2),
        )

        assertEquals(
            listOf(
                UnitsPoint(date(2025, 1, 10), UnitsBalance(10_000_000)),
                UnitsPoint(date(2025, 2, 10), UnitsBalance(15_000_000)),
                UnitsPoint(date(2025, 3, 10), UnitsBalance(2_000_000)),
            ),
            ledger.unitsHistory,
        )
        assertEquals(UnitsBalance.ZERO, ledger.unitsOn(date(2025, 1, 9)))
        assertEquals(UnitsBalance(10_000_000), ledger.unitsOn(date(2025, 2, 9)))
        assertEquals(UnitsBalance(2_000_000), ledger.unitsOn(date(2026, 1, 1)))
    }

    @Test
    fun `a ledger without movements holds nothing`() {
        val ledger = PositionLedger(FUND_A)

        assertEquals(UnitsBalance.ZERO, ledger.unitsHeld)
        assertFalse(ledger.started)
        assertTrue(ledger.costBasis.isComplete)
    }
}
