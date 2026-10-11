package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.calc.InsufficientUnitsException
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.repository.HoldingHasMovementsException
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.InvalidMovementException
import com.denebapps.patrimonio.domain.repository.MissingEurEquivalentException
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.domain.repository.MovementNotFoundException
import com.denebapps.patrimonio.domain.repository.TransferNotAllowedException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The fake the movement screens are tested against must refuse what the real repository refuses. */
class FakeMovementRepositoryTest {
    private fun fund(id: String, type: InstrumentType = InstrumentType.FUND, currency: Currency = Currency.EUR) =
        PortfolioHolding(
            id = id,
            portfolioId = TEST_PORTFOLIO_ID,
            name = id,
            type = type,
            isin = null,
            ticker = null,
            valuation = HoldingValuation.Quoted(
                ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
                currency = currency,
                units = Units(UNITS_SCALE),
            ),
        )

    private fun units(whole: Long) = Units(whole * UNITS_SCALE)

    private fun buy(day: Int, whole: Long, cents: Long) = MovementDraft(
        type = MovementType.BUY,
        date = LocalDate(2026, 1, day),
        units = units(whole),
        amount = CurrencyAmount(Money(cents), Currency.EUR),
    )

    private fun unitsOf(holdings: FakePortfolioRepository, id: String) =
        (holdings.current.first { it.id == id }.valuation as HoldingValuation.Quoted).units

    @Test
    fun `movements decide the units, block manual edits and refuse an impossible history`() = runTest {
        val holdings = FakePortfolioRepository(listOf(fund("a")))
        val movements = FakeMovementRepository(holdings)

        val stored = movements.addMovement("a", buy(1, 10, 100_000))

        assertEquals(Money(100_000), stored.amountEur)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), unitsOf(holdings, "a"))
        assertEquals(setOf("a"), movements.observeHoldingIdsWithMovements().first())
        assertFailsWith<HoldingHasMovementsException> { holdings.updateUnits("a", units(3)) }

        assertFailsWith<InsufficientUnitsException> {
            movements.addMovement("a", buy(2, 1, 1).copy(type = MovementType.SELL, units = units(11)))
        }
        assertEquals(listOf(stored), movements.current)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), unitsOf(holdings, "a"))
    }

    @Test
    fun `selling everything closes the position and deleting the last movement frees the units`() = runTest {
        val holdings = FakePortfolioRepository(listOf(fund("a")))
        val movements = FakeMovementRepository(holdings)
        val first = movements.addMovement("a", buy(1, 10, 100_000))

        movements.addMovement("a", buy(2, 10, 1).copy(type = MovementType.SELL))
        assertEquals(UnitsBalance.ZERO, unitsOf(holdings, "a"))
        movements.deleteMovement(movements.current.single { it.type == MovementType.SELL }.id)
        movements.deleteMovement(first.id)

        assertTrue(movements.observeHoldingIdsWithMovements().first().isEmpty())
        holdings.updateUnits("a", units(3))
        assertEquals(UnitsBalance(3 * UNITS_SCALE), unitsOf(holdings, "a"))
    }

    @Test
    fun `a holding in another currency needs the euro equivalent`() = runTest {
        val holdings = FakePortfolioRepository(listOf(fund("usd", currency = Currency.USD)))
        val movements = FakeMovementRepository(holdings)
        val draft = MovementDraft(
            type = MovementType.BUY,
            date = LocalDate(2026, 1, 1),
            units = units(1),
            amount = CurrencyAmount(Money(1_000), Currency.USD),
        )

        assertFailsWith<MissingEurEquivalentException> { movements.addMovement("usd", draft) }

        assertEquals(Money(920), movements.addMovement("usd", draft.copy(amountEur = Money(920))).amountEur)
    }

    @Test
    fun `transfers are between two funds, move the units and are deleted as a pair`() = runTest {
        val holdings = FakePortfolioRepository(listOf(fund("a"), fund("b"), fund("etf", InstrumentType.ETF)))
        val movements = FakeMovementRepository(holdings)
        movements.addMovement("a", buy(1, 10, 100_000))
        val day = LocalDate(2026, 2, 1)

        assertFailsWith<TransferNotAllowedException> {
            movements.addTransfer("a", "etf", day, day, units(1), units(1))
        }
        val transferId = movements.addTransfer("a", "b", day, day, units(4), units(4))

        assertEquals(UnitsBalance(6 * UNITS_SCALE), unitsOf(holdings, "a"))
        // The 1 unit "b" was typed in is replaced by what its movements say: the 4 received.
        assertEquals(UnitsBalance(4 * UNITS_SCALE), unitsOf(holdings, "b"))
        assertFailsWith<HoldingHasTransfersException> { holdings.delete("b") }

        movements.deleteMovement(movements.current.first { it.transferId == transferId }.id)

        assertEquals(1, movements.current.size)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), unitsOf(holdings, "a"))
        holdings.delete("b")
        assertEquals(listOf("a", "etf"), holdings.current.map { it.id })
    }

    @Test
    fun `a position can be created with its first purchase and it takes them away when deleted`() = runTest {
        val holdings = FakePortfolioRepository()
        val movements = FakeMovementRepository(holdings)

        movements.addHoldingWithPurchase(fund("x"), buy(1, 4, 40_000))

        assertEquals(UnitsBalance(4 * UNITS_SCALE), unitsOf(holdings, "x"))
        assertEquals(1, movements.current.size)
        holdings.delete("x")
        assertTrue(movements.current.isEmpty())
    }

    @Test
    fun `a purchase that is refused leaves no holding behind`() = runTest {
        val holdings = FakePortfolioRepository()
        val movements = FakeMovementRepository(holdings)

        assertFailsWith<InvalidMovementException> {
            movements.addHoldingWithPurchase(fund("x"), buy(1, 4, 40_000).copy(type = MovementType.SELL))
        }

        assertTrue(holdings.current.isEmpty())
        assertTrue(movements.current.isEmpty())
    }

    @Test
    fun `a transfer is edited on both legs or not at all`() = runTest {
        val holdings = FakePortfolioRepository(listOf(fund("a"), fund("b")))
        val movements = FakeMovementRepository(holdings)
        movements.addMovement("a", buy(1, 10, 100_000))
        val day = LocalDate(2026, 2, 1)
        val transferId = movements.addTransfer("a", "b", day, day, units(4), units(4))

        movements.updateTransfer(transferId, day, LocalDate(2026, 2, 3), units(6), units(60), "Nota")

        val legs = movements.current.filter { it.transferId == transferId }
        assertEquals(units(6), legs.single { it.type == MovementType.TRANSFER_OUT }.units)
        assertEquals(LocalDate(2026, 2, 3), legs.single { it.type == MovementType.TRANSFER_IN }.date)
        assertEquals(setOf<String?>("Nota"), legs.map { it.note }.toSet())
        assertEquals(UnitsBalance(4 * UNITS_SCALE), unitsOf(holdings, "a"))
        assertEquals(UnitsBalance(60 * UNITS_SCALE), unitsOf(holdings, "b"))

        val before = movements.current
        assertFailsWith<InsufficientUnitsException> {
            movements.updateTransfer(transferId, day, day, units(11), units(11), null)
        }
        assertEquals(before, movements.current)
        assertFailsWith<MovementNotFoundException> {
            movements.updateTransfer("ghost", day, day, units(1), units(1), null)
        }
    }

    @Test
    fun `a transfer to a new fund creates it only when the transfer is accepted`() = runTest {
        val holdings = FakePortfolioRepository(listOf(fund("a")))
        val movements = FakeMovementRepository(holdings)
        movements.addMovement("a", buy(1, 10, 100_000))
        val day = LocalDate(2026, 2, 1)

        assertFailsWith<InsufficientUnitsException> {
            movements.addTransferToNewHolding("a", fund("b"), day, day, units(11), units(11))
        }
        assertFailsWith<TransferNotAllowedException> {
            movements.addTransferToNewHolding("a", fund("etf", InstrumentType.ETF), day, day, units(1), units(1))
        }
        assertEquals(listOf("a"), holdings.current.map { it.id })

        val transferId = movements.addTransferToNewHolding("a", fund("b"), day, day, units(4), units(40))

        assertEquals(UnitsBalance(40 * UNITS_SCALE), unitsOf(holdings, "b"))
        assertEquals(UnitsBalance(6 * UNITS_SCALE), unitsOf(holdings, "a"))
        assertEquals(2, movements.current.count { it.transferId == transferId })
    }
}
