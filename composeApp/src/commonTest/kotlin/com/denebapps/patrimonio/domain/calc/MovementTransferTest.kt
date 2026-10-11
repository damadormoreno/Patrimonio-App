package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.FUND_B
import com.denebapps.patrimonio.domain.model.FUND_C
import com.denebapps.patrimonio.domain.model.Lot
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.opening
import com.denebapps.patrimonio.domain.model.sell
import com.denebapps.patrimonio.domain.model.transferIn
import com.denebapps.patrimonio.domain.model.transferOut
import com.denebapps.patrimonio.domain.model.units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A fund transfer moves lots, with their acquisition date and cost, from one position to another. Amounts: EUR cents.
class MovementTransferTest {
    // Fund A: lot 1 = 10 u bought 2025-01-10 for 1000.00 (100_000), lot 2 = 10 u bought 2025-02-10 for 1200.00.
    private val fundABuys = listOf(
        buy("a1", date(2025, 1, 10), units(10), cents = 100_000),
        buy("a2", date(2025, 2, 10), units(10), cents = 120_000),
    )

    @Test
    fun `a transfer carries the lots with their dates and cost, rescales the units and realizes nothing`() {
        // Out of A: 15 u -> all of lot 1 (cost 100_000) and 5 u of lot 2 (cost 120_000 * 5/10 = 60_000).
        // Into B at another NAV: 30 u. Rescale by 30/15 = 2: lot 1 -> 20 u, lot 2 -> 10 u, costs unchanged.
        val ledgers = computeLedgers(
            fundABuys + transferOut("out1", date(2025, 3, 1), units(15), "t1") +
                transferIn("in1", date(2025, 3, 1), units(30), "t1"),
        )

        val a = ledgers.getValue(FUND_A)
        val b = ledgers.getValue(FUND_B)
        assertEquals(listOf(Lot(date(2025, 2, 10), units(5), Money(60_000))), a.lots)
        assertEquals(
            listOf(
                Lot(date(2025, 1, 10), units(20), Money(100_000)),
                Lot(date(2025, 2, 10), units(10), Money(60_000)),
            ),
            b.lots,
        )
        assertEquals(Money.ZERO, a.realized.knownGainEur)
        assertEquals(Money.ZERO, b.realized.knownGainEur)
        assertEquals(Money(160_000), a.transferLegs.getValue("out1").costEur)
        assertEquals(Money(160_000), b.transferLegs.getValue("in1").costEur)
        assertEquals(units(15), a.transferLegs.getValue("out1").units)
        assertEquals(UnitsBalance(30_000_000), b.unitsHeld)
        assertEquals(Money(220_000), a.costBasis.knownCostEur + b.costBasis.knownCostEur)
    }

    @Test
    fun `selling at the destination consumes the carried lots oldest first at their carried cost`() {
        // B after the transfer: 20 u at 100_000 (Jan) and 10 u at 60_000 (Feb). Sell 25 u for 3000.00 = 300_000:
        // consumed cost 100_000 + 60_000 * 5/10 = 130_000, gain 170_000; B keeps 5 u at 30_000 dated Feb.
        val ledgers = computeLedgers(
            fundABuys + transferOut("out1", date(2025, 3, 1), units(15), "t1") +
                transferIn("in1", date(2025, 3, 1), units(30), "t1") +
                sell("s1", date(2025, 4, 1), units(25), cents = 300_000, holding = FUND_B),
        )

        val b = ledgers.getValue(FUND_B)
        assertEquals(Money(170_000), b.realized.totalGainEur)
        assertEquals(listOf(Lot(date(2025, 2, 10), units(5), Money(30_000))), b.lots)
    }

    @Test
    fun `the receiving leg may be dated after the sending leg`() {
        val ledgers = computeLedgers(
            fundABuys + transferOut("out1", date(2025, 3, 1), units(10), "t1") +
                transferIn("in1", date(2025, 3, 4), units(7), "t1"),
        )

        val b = ledgers.getValue(FUND_B)
        assertEquals(listOf(Lot(date(2025, 1, 10), units(7), Money(100_000))), b.lots)
        assertEquals(UnitsBalance.ZERO, b.unitsOn(date(2025, 3, 3)))
        assertEquals(UnitsBalance(7_000_000), b.unitsOn(date(2025, 3, 4)))
        assertEquals(UnitsBalance(10_000_000), ledgers.getValue(FUND_A).unitsHeld)
    }

    @Test
    fun `same-day transfers can be chained from one fund to the next`() {
        // A: 10 u (100_000) -> B: 5 u -> C: 2 u, all on the same day, in entry order.
        val ledgers = computeLedgers(
            listOf(
                buy("a1", date(2025, 1, 10), units(10), cents = 100_000),
                transferOut("o1", date(2025, 3, 1), units(10), "t1", FUND_A, sequence = 1),
                transferIn("i1", date(2025, 3, 1), units(5), "t1", FUND_B, sequence = 2),
                transferOut("o2", date(2025, 3, 1), units(5), "t2", FUND_B, sequence = 3),
                transferIn("i2", date(2025, 3, 1), units(2), "t2", FUND_C, sequence = 4),
            ),
        )

        assertTrue(ledgers.getValue(FUND_A).lots.isEmpty())
        assertTrue(ledgers.getValue(FUND_B).lots.isEmpty())
        assertEquals(listOf(Lot(date(2025, 1, 10), units(2), Money(100_000))), ledgers.getValue(FUND_C).lots)
    }

    @Test
    fun `rescaling distributes the units exactly even when they do not divide`() {
        // Three 1 u lots (costs 10_001, 10_001, 10_002) moved into 1 u at the destination:
        // cumulative 1/3, 2/3, 3/3 of 1_000_000 = 333_333, 666_667 (666_666.67), 1_000_000
        // -> lots of 333_333 + 333_334 + 333_333.
        val ledgers = computeLedgers(
            listOf(
                buy("a1", date(2025, 1, 1), units(1), cents = 10_001),
                buy("a2", date(2025, 1, 2), units(1), cents = 10_001),
                buy("a3", date(2025, 1, 3), units(1), cents = 10_002),
                transferOut("o1", date(2025, 2, 1), units(3), "t1"),
                transferIn("i1", date(2025, 2, 1), units(1), "t1"),
            ),
        )

        val b = ledgers.getValue(FUND_B)
        assertEquals(listOf(333_333L, 333_334L, 333_333L), b.lots.map { it.units.scaled })
        assertEquals(listOf(10_001L, 10_001L, 10_002L), b.lots.map { it.costEur?.minorUnits })
        assertEquals(UnitsBalance(1_000_000), b.unitsHeld)
    }

    @Test
    fun `splitting a lot in a transfer keeps the total cost to the cent`() {
        // Lot of 3 u at 100 cents. Move 1 u: cost 100 * 1/3 = 33.33 -> 33 leaves, 67 stays. 33 + 67 = 100.
        val ledgers = computeLedgers(
            listOf(
                buy("a1", date(2025, 1, 1), units(3), cents = 100),
                transferOut("o1", date(2025, 2, 1), units(1), "t1"),
                transferIn("i1", date(2025, 2, 1), units(1), "t1"),
            ),
        )

        assertEquals(Money(67), ledgers.getValue(FUND_A).costBasis.knownCostEur)
        assertEquals(Money(33), ledgers.getValue(FUND_B).costBasis.knownCostEur)
    }

    @Test
    fun `a lot that rescales to no units folds its cost into the next lot`() {
        // Lot 1: 0.000001 u costing 5; lot 2: 1 u costing 1000. All moved into 0.000001 u: lot 1 rescales to
        // 1 * 1 / 1_000_001 = 0 units, so its cost joins lot 2 (dated Feb) -> one lot of 0.000001 u costing 1005.
        val ledgers = computeLedgers(
            listOf(
                buy("a1", date(2025, 1, 1), Units(1), cents = 5),
                buy("a2", date(2025, 2, 1), units(1), cents = 1_000),
                transferOut("o1", date(2025, 3, 1), Units(1_000_001), "t1"),
                transferIn("i1", date(2025, 3, 1), Units(1), "t1"),
            ),
        )

        assertEquals(listOf(Lot(date(2025, 2, 1), Units(1), Money(1_005))), ledgers.getValue(FUND_B).lots)
    }

    @Test
    fun `an unknown cost travels with the transferred units`() {
        val ledgers = computeLedgers(
            listOf(
                opening("o0", date(2025, 1, 1), units(10), costCents = null),
                transferOut("o1", date(2025, 2, 1), units(4), "t1"),
                transferIn("i1", date(2025, 2, 1), units(4), "t1"),
            ),
        )

        assertEquals(listOf(Lot(date(2025, 1, 1), units(4), null)), ledgers.getValue(FUND_B).lots)
        assertEquals(listOf(Lot(date(2025, 1, 1), units(6), null)), ledgers.getValue(FUND_A).lots)
        assertNull(ledgers.getValue(FUND_A).transferLegs.getValue("o1").costEur)
        assertEquals(UnitsBalance(4_000_000), ledgers.getValue(FUND_B).costBasis.unknownCostUnits)
    }

    @Test
    fun `a transfer applied across two ledgers returns both updated`() {
        val source = fundABuys.fold(PositionLedger(FUND_A)) { ledger, movement -> ledger.record(movement) }
        val destination = PositionLedger(FUND_B)

        val (newSource, newDestination) = applyTransfer(
            source,
            destination,
            out = transferOut("o1", date(2025, 3, 1), units(10), "t1"),
            inn = transferIn("i1", date(2025, 3, 1), units(10), "t1"),
        )

        assertEquals(units(10), newSource.lots.single().units)
        assertEquals(listOf(Lot(date(2025, 1, 10), units(10), Money(100_000))), newDestination.lots)
        assertEquals(UnitsBalance.ZERO, destination.unitsHeld)
    }

    @Test
    fun `transferring more units than the source holds is an error`() {
        val error = assertFailsWith<InsufficientUnitsException> {
            computeLedgers(
                fundABuys + transferOut("o1", date(2025, 3, 1), units(21), "t1") +
                    transferIn("i1", date(2025, 3, 1), units(21), "t1"),
            )
        }

        assertEquals(date(2025, 3, 1), error.date)
        assertEquals(UnitsBalance(20_000_000), error.available)
    }

    @Test
    fun `a transfer needs both legs, on different positions, in order`() {
        val onlyIn = listOf(transferIn("i1", date(2025, 3, 1), units(1), "t1"))
        val onlyOut = fundABuys + transferOut("o1", date(2025, 3, 1), units(1), "t1")
        val samePosition = fundABuys + transferOut("o1", date(2025, 3, 1), units(1), "t1") +
            transferIn("i1", date(2025, 3, 1), units(1), "t1", holding = FUND_A)
        val inBeforeOut = fundABuys + transferOut("o1", date(2025, 3, 5), units(1), "t1") +
            transferIn("i1", date(2025, 3, 1), units(1), "t1")
        val twoOuts = fundABuys + transferOut("o1", date(2025, 3, 5), units(1), "t1") +
            transferOut("o2", date(2025, 3, 5), units(1), "t1") + transferIn("i1", date(2025, 3, 6), units(1), "t1")

        listOf(onlyIn, onlyOut, samePosition, inBeforeOut, twoOuts).forEach { movements: List<Movement> ->
            assertFailsWith<InvalidTransferException> { computeLedgers(movements) }
        }
    }
}
