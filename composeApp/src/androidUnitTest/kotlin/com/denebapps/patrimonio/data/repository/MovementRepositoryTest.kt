package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.calc.InsufficientUnitsException
import com.denebapps.patrimonio.domain.calc.InvalidTransferException
import com.denebapps.patrimonio.domain.calc.MovementBeforeStartException
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
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
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.domain.repository.TransferNotAllowedException
import com.denebapps.patrimonio.testing.FakeFundamentalsSource
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW = Instant.parse("2026-10-11T12:00:00Z")
private val TODAY = LocalDate(2026, 10, 9)
private const val FUND_A = "fund-a"
private const val FUND_B = "fund-b"

private class MovementFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Answers every quote with the price [scaled] in EUR. */
private class ConstantQuoteSource(private val scaled: Long) : QuoteSource {
    override suspend fun search(query: String): List<InstrumentCandidate> = error("not used")

    override suspend fun latestQuote(ref: InstrumentRef) =
        Quote(ref, Price(scaled, Currency.EUR), LocalDate(2026, 10, 10))
}

private fun day(month: Int, day: Int) = LocalDate(2026, month, day)

private fun units(whole: Long, millionths: Long = 0) = Units(whole * UNITS_SCALE + millionths)

private fun eur(cents: Long) = CurrencyAmount(Money(cents), Currency.EUR)

@RunWith(RobolectricTestRunner::class)
class MovementRepositoryTest {
    private fun fund(
        id: String,
        currency: Currency = Currency.EUR,
        type: InstrumentType = InstrumentType.FUND,
        portfolioId: String = TEST_PORTFOLIO_ID,
        units: Units = units(1),
    ) = PortfolioHolding(
        id = id,
        portfolioId = portfolioId,
        name = id,
        type = type,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = currency,
            units = units,
            lastQuote = LastQuote(Price(10_000_000, currency), TODAY, NOW.toEpochMilliseconds()),
        ),
    )

    private fun painting(id: String) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = id,
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(eur(250_000), valuedAtEpochMs = 7L),
    )

    private fun buy(date: LocalDate, units: Units, cents: Long, feeCents: Long = 0) = MovementDraft(
        type = MovementType.BUY,
        date = date,
        units = units,
        amount = eur(cents),
        fee = Money(feeCents),
    )

    private fun sell(date: LocalDate, units: Units, cents: Long) =
        MovementDraft(type = MovementType.SELL, date = date, units = units, amount = eur(cents))

    private class Env(
        val db: AppDatabase,
        val holdings: PortfolioRepositoryImpl,
        val movements: MovementRepositoryImpl,
    ) {
        suspend fun unitsOf(id: String): UnitsBalance =
            (holdings.observeAll().first().single { it.id == id }.valuation as HoldingValuation.Quoted).units

        suspend fun snapshotAssets(): Long? = db.netWorthDao().find("2026-10")?.assetsMinor
    }

    private fun CoroutineScope.envFor(db: AppDatabase, quotes: QuoteSource = ConstantQuoteSource(10_000_000)): Env {
        val clock = MovementFixedClock(NOW)
        val upserter = NetWorthSnapshotUpserter(
            db.assetDao(),
            db.liabilityDao(),
            db.portfolioHoldingDao(),
            db.fxRateDao(),
            db.netWorthDao(),
            clock,
        ) { TimeZone.UTC }
        val gate = testSeedingGate(db)
        return Env(
            db,
            PortfolioRepositoryImpl(
                db,
                db.portfolioHoldingDao(),
                quotes,
                upserter,
                gate,
                clock,
                FakeFundamentalsSource(),
            ),
            MovementRepositoryImpl(db, upserter, gate, clock),
        )
    }

    @Test
    fun `a first buy is stored with its units materialized and the net worth refreshed`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))

        val stored = env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000, feeCents = 500))

        assertEquals(MovementType.BUY, stored.type)
        assertEquals(listOf(stored), env.movements.observeMovements(FUND_A).first())
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        // 10 units at 10 EUR = 100 EUR, in the current month's snapshot.
        assertEquals(10_000L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `a sell takes units off and a sell of everything closes the position at zero value`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        env.movements.addMovement(FUND_A, sell(day(2, 1), units(4), 50_000))
        assertEquals(UnitsBalance(6 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(6_000L, env.snapshotAssets())

        env.movements.addMovement(FUND_A, sell(day(3, 1), units(6), 90_000))
        val closed = env.holdings.observeAll().first().single { it.id == FUND_A }
        assertEquals(UnitsBalance.ZERO, (closed.valuation as HoldingValuation.Quoted).units)
        assertEquals(eur(0), closed.value())
        assertEquals(0L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `selling more than is held is refused and nothing is written`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        val snapshotBefore = env.snapshotAssets()

        assertFailsWith<InsufficientUnitsException> {
            env.movements.addMovement(FUND_A, sell(day(2, 1), units(11), 120_000))
        }

        assertEquals(1, env.db.movementDao().list().size)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(snapshotBefore, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `a sell dated before the buy it needs is refused`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.movements.addMovement(FUND_A, buy(day(3, 1), units(10), 100_000))

        assertFailsWith<InsufficientUnitsException> {
            env.movements.addMovement(FUND_A, sell(day(2, 1), units(1), 10_000))
        }
        assertEquals(1, env.db.movementDao().list().size)
        env.db.close()
    }

    @Test
    fun `a dividend before the position started is refused`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))

        val dividend = MovementDraft(type = MovementType.DIVIDEND, date = day(1, 1), amount = eur(500))

        assertFailsWith<MovementBeforeStartException> { env.movements.addMovement(FUND_A, dividend) }
        assertTrue(env.db.movementDao().list().isEmpty())
        env.db.close()
    }

    @Test
    fun `an opening balance keeps its cost or its unknown cost`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A, units = units(5)))
        env.holdings.add(fund(FUND_B, units = units(3)))

        val withCost = env.movements.addOpeningBalance(FUND_A, day(1, 1), units(5), eur(40_000))
        val withoutCost = env.movements.addOpeningBalance(FUND_B, day(1, 1), units(3), cost = null)

        assertEquals(MovementType.OPENING_BALANCE, withCost.type)
        assertEquals(Money(40_000), withCost.amountEur)
        assertEquals(eur(40_000), withCost.amount)
        assertNull(withoutCost.amount)
        assertNull(withoutCost.amountEur)
        assertEquals(UnitsBalance(5 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(UnitsBalance(3 * UNITS_SCALE), env.unitsOf(FUND_B))
        env.db.close()
    }

    @Test
    fun `updating a movement recomputes the units and an invalid edit changes nothing`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        val first = env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        env.movements.addMovement(FUND_A, sell(day(2, 1), units(8), 90_000))

        val edited = env.movements.updateMovement(first.id, buy(day(1, 15), units(12), 120_000))
        assertEquals(first.id, edited.id)
        assertEquals(first.sequence, edited.sequence)
        assertEquals(UnitsBalance(4 * UNITS_SCALE), env.unitsOf(FUND_A))

        assertFailsWith<InsufficientUnitsException> {
            env.movements.updateMovement(first.id, buy(day(1, 15), units(5), 50_000))
        }
        assertEquals(Units(12 * UNITS_SCALE), env.db.movementDao().find(first.id)?.unitsScaled?.let(::Units))
        assertEquals(UnitsBalance(4 * UNITS_SCALE), env.unitsOf(FUND_A))
        env.db.close()
    }

    @Test
    fun `updating a movement that does not exist fails`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())

        assertFailsWith<MovementNotFoundException> {
            env.movements.updateMovement("ghost", buy(day(1, 1), units(1), 100))
        }
        env.db.close()
    }

    @Test
    fun `deleting a movement recomputes the units and one the rest depends on is refused`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        val first = env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        val second = env.movements.addMovement(FUND_A, buy(day(1, 20), units(5), 55_000))
        val sale = env.movements.addMovement(FUND_A, sell(day(2, 1), units(12), 140_000))

        assertFailsWith<InsufficientUnitsException> { env.movements.deleteMovement(first.id) }
        assertEquals(3, env.db.movementDao().list().size)

        env.movements.deleteMovement(sale.id)
        assertEquals(UnitsBalance(15 * UNITS_SCALE), env.unitsOf(FUND_A))
        env.movements.deleteMovement(second.id)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        env.db.close()
    }

    @Test
    fun `deleting the last movement leaves a holding with no movements and no units`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        val only = env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        env.movements.deleteMovement(only.id)

        assertEquals(UnitsBalance.ZERO, env.unitsOf(FUND_A))
        assertEquals(emptySet(), env.movements.observeHoldingIdsWithMovements().first())
        env.db.close()
    }

    @Test
    fun `a transfer moves the units from one fund to the other in one step`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        val transferId = env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 3), units(4), units(40), "Cambio")

        assertEquals(UnitsBalance(6 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(UnitsBalance(40 * UNITS_SCALE), env.unitsOf(FUND_B))
        val legs = env.movements.observeAllMovements().first().filter { it.transferId == transferId }
        assertEquals(setOf(MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN), legs.map { it.type }.toSet())
        assertEquals(setOf(FUND_A, FUND_B), legs.map { it.holdingId }.toSet())
        assertEquals(setOf("Cambio"), legs.map { it.note }.toSet())
        assertEquals(setOf(FUND_A, FUND_B), env.movements.observeHoldingIdsWithMovements().first())
        env.db.close()
    }

    @Test
    fun `a transfer of more than is held is refused and neither fund changes`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        assertFailsWith<InsufficientUnitsException> {
            env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 1), units(11), units(11), null)
        }

        assertEquals(1, env.db.movementDao().list().size)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(UnitsBalance(1 * UNITS_SCALE), env.unitsOf(FUND_B))
        env.db.close()
    }

    @Test
    fun `a transfer that arrives before it leaves is refused`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        assertFailsWith<InvalidTransferException> {
            env.movements.addTransfer(FUND_A, FUND_B, day(2, 5), day(2, 1), units(4), units(4), null)
        }
        assertEquals(1, env.db.movementDao().list().size)
        env.db.close()
    }

    @Test
    fun `a transfer needs two different funds`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.holdings.add(fund("etf", type = InstrumentType.ETF))
        env.holdings.add(fund("stock", type = InstrumentType.STOCK))
        env.holdings.add(painting("art"))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        env.movements.addMovement("etf", buy(day(1, 15), units(10), 100_000))

        val attempts = listOf(
            FUND_A to FUND_A,
            FUND_A to "etf",
            "etf" to FUND_B,
            FUND_A to "stock",
            FUND_A to "art",
            "art" to FUND_A,
        )
        attempts.forEach { (from, to) ->
            assertFailsWith<TransferNotAllowedException>("$from -> $to") {
                env.movements.addTransfer(from, to, day(2, 1), day(2, 1), units(1), units(1), null)
            }
        }

        assertEquals(2, env.db.movementDao().list().size)
        env.db.close()
    }

    @Test
    fun `a transfer to a holding that does not exist fails`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        assertFailsWith<PortfolioHoldingNotFoundException> {
            env.movements.addTransfer(FUND_A, "ghost", day(2, 1), day(2, 1), units(1), units(1), null)
        }
        env.db.close()
    }

    @Test
    fun `a transfer may cross portfolios`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.observeAll().first() // waits for the seeding, which creates the default portfolio
        env.db.insertTestPortfolio(id = "hijos", name = "Hijos", sortOrder = 1)
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B, portfolioId = "hijos"))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 1), units(10), units(10), null)

        assertEquals(UnitsBalance.ZERO, env.unitsOf(FUND_A))
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_B))
        env.db.close()
    }

    @Test
    fun `deleting one leg of a transfer deletes both and gives the units back`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        val transferId = env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 1), units(4), units(4), null)
        val incoming = env.movements.observeMovements(FUND_B).first().single { it.transferId == transferId }

        env.movements.deleteMovement(incoming.id)

        assertEquals(1, env.db.movementDao().list().size)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(UnitsBalance.ZERO, env.unitsOf(FUND_B))
        env.db.close()
    }

    @Test
    fun `a transfer leg that the destination already moved on cannot be deleted`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.holdings.add(fund("fund-c"))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        val first = env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 1), units(10), units(10), null)
        env.movements.addTransfer(FUND_B, "fund-c", day(3, 1), day(3, 1), units(10), units(10), null)
        val leg = env.movements.observeMovements(FUND_A).first().single { it.transferId == first }

        assertFailsWith<InsufficientUnitsException> { env.movements.deleteMovement(leg.id) }

        assertEquals(5, env.db.movementDao().list().size)
        env.db.close()
    }

    @Test
    fun `a transfer leg can be edited as long as the history stays valid`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        val transferId = env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 1), units(4), units(4), null)
        val leg = env.movements.observeMovements(FUND_A).first().single { it.transferId == transferId }

        val edited = env.movements.updateMovement(
            leg.id,
            MovementDraft(type = MovementType.TRANSFER_OUT, date = day(2, 1), units = units(5)),
        )

        assertEquals(transferId, edited.transferId)
        assertEquals(UnitsBalance(5 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertFailsWith<InvalidMovementException> {
            env.movements.updateMovement(leg.id, buy(day(2, 1), units(5), 100))
        }
        env.db.close()
    }

    @Test
    fun `an euro holding fills the euro amounts in itself`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))

        val stored = env.movements.addMovement(
            FUND_A,
            // A wrong euro amount from the caller never wins over the amount itself.
            buy(day(1, 15), units(10), 100_000, feeCents = 500).copy(amountEur = Money(1), feeEur = Money(1)),
        )

        assertEquals(Money(100_000), stored.amountEur)
        assertEquals(Money(500), stored.feeEur)
        assertEquals(stored, env.db.movementDao().list().single().let(::movementToDomain))
        env.db.close()
    }

    @Test
    fun `a holding in another currency needs the euro equivalent from the caller`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund("usd", currency = Currency.USD))
        val usd = CurrencyAmount(Money(100_000), Currency.USD)
        val draft = MovementDraft(MovementType.BUY, day(1, 15), units(10), usd, fee = Money(500))

        assertFailsWith<MissingEurEquivalentException> { env.movements.addMovement("usd", draft) }
        assertFailsWith<MissingEurEquivalentException> {
            env.movements.addMovement("usd", draft.copy(amountEur = Money(92_000)))
        }
        assertTrue(env.db.movementDao().list().isEmpty())

        val stored = env.movements.addMovement("usd", draft.copy(amountEur = Money(92_000), feeEur = Money(460)))

        assertEquals(usd, stored.amount)
        assertEquals(Money(92_000), stored.amountEur)
        assertEquals(Money(460), stored.feeEur)
        env.db.close()
    }

    @Test
    fun `a fee free movement in another currency needs only the amount in euros`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund("usd", currency = Currency.USD))

        val stored = env.movements.addMovement(
            "usd",
            MovementDraft(
                MovementType.BUY,
                day(1, 15),
                units(10),
                CurrencyAmount(Money(100_000), Currency.USD),
                amountEur = Money(92_000),
            ),
        )

        assertEquals(Money.ZERO, stored.feeEur)
        env.db.close()
    }

    @Test
    fun `an amount in a different currency than the holding is refused`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund("usd", currency = Currency.USD))

        assertFailsWith<InvalidMovementException> {
            env.movements.addMovement("usd", buy(day(1, 15), units(10), 100_000))
        }
        env.db.close()
    }

    @Test
    fun `an unfinished draft is refused with a typed error`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))

        assertFailsWith<InvalidMovementException> {
            env.movements.addMovement(FUND_A, MovementDraft(MovementType.BUY, day(1, 15)))
        }
        assertFailsWith<InvalidMovementException> {
            env.movements.addMovement(FUND_A, buy(day(1, 15), units(1), 100).copy(note = "n".repeat(201)))
        }
        env.db.close()
    }

    @Test
    fun `a note is trimmed and a blank one is dropped`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))

        val withNote = env.movements.addMovement(FUND_A, buy(day(1, 15), units(1), 100).copy(note = "  Plan  "))
        val blank = env.movements.addMovement(FUND_A, buy(day(1, 16), units(1), 100).copy(note = "   "))

        assertEquals("Plan", withNote.note)
        assertNull(blank.note)
        env.db.close()
    }

    @Test
    fun `movements are only for quoted holdings that exist`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(painting("art"))

        assertFailsWith<InvalidMovementException> { env.movements.addMovement("art", buy(day(1, 15), units(1), 100)) }
        assertFailsWith<PortfolioHoldingNotFoundException> {
            env.movements.addMovement("ghost", buy(day(1, 15), units(1), 100))
        }
        env.db.close()
    }

    @Test
    fun `sequences grow across the whole app`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))

        val a = env.movements.addMovement(FUND_A, buy(day(1, 1), units(1), 100))
        val b = env.movements.addMovement(FUND_B, buy(day(1, 1), units(1), 100))
        val c = env.movements.addMovement(FUND_A, buy(day(1, 2), units(1), 100))

        assertTrue(a.sequence < b.sequence && b.sequence < c.sequence, "${a.sequence} ${b.sequence} ${c.sequence}")
        env.db.close()
    }

    @Test
    fun `a position can be created with its first purchase atomically`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())

        val stored = env.movements.addHoldingWithPurchase(fund(FUND_A), buy(day(1, 15), units(10), 100_000))

        assertEquals(FUND_A, stored.holdingId)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(setOf(FUND_A), env.movements.observeHoldingIdsWithMovements().first())
        assertEquals(10_000L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `a purchase that cannot be recorded leaves no holding behind`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())

        assertFailsWith<InvalidMovementException> {
            env.movements.addHoldingWithPurchase(fund(FUND_A), sell(day(1, 15), units(10), 100_000))
        }
        assertFailsWith<MissingEurEquivalentException> {
            env.movements.addHoldingWithPurchase(
                fund("usd", currency = Currency.USD),
                MovementDraft(MovementType.BUY, day(1, 15), units(1), CurrencyAmount(Money(100), Currency.USD)),
            )
        }
        assertFailsWith<InvalidMovementException> {
            env.movements.addHoldingWithPurchase(painting("art"), buy(day(1, 15), units(1), 100))
        }

        assertTrue(env.holdings.observeAll().first().isEmpty())
        assertTrue(env.db.movementDao().list().isEmpty())
        env.db.close()
    }

    @Test
    fun `units cannot be edited by hand once a position has movements`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        val only = env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        assertFailsWith<HoldingHasMovementsException> { env.holdings.updateUnits(FUND_A, units(3)) }
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        env.holdings.updateUnits(FUND_B, units(3)) // a position without movements still can
        assertEquals(UnitsBalance(3 * UNITS_SCALE), env.unitsOf(FUND_B))

        env.movements.deleteMovement(only.id)
        env.holdings.updateUnits(FUND_A, units(3))
        assertEquals(UnitsBalance(3 * UNITS_SCALE), env.unitsOf(FUND_A))
        env.db.close()
    }

    @Test
    fun `a holding with movements of its own can be deleted and they go with it`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        env.movements.addMovement(FUND_B, buy(day(1, 15), units(2), 20_000))

        env.holdings.delete(FUND_A)

        assertEquals(listOf(FUND_B), env.db.movementDao().list().map { it.holdingId })
        assertEquals(2_000L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `a holding with a transfer to another one cannot be deleted until the transfer is`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))
        val transferId = env.movements.addTransfer(FUND_A, FUND_B, day(2, 1), day(2, 1), units(4), units(4), null)

        val error = assertFailsWith<HoldingHasTransfersException> { env.holdings.delete(FUND_B) }
        assertEquals("Este producto tiene traspasos con otros productos; elimínalos antes.", error.message)
        assertFailsWith<HoldingHasTransfersException> { env.holdings.delete(FUND_A) }
        assertEquals(3, env.db.movementDao().list().size)

        val leg = env.movements.observeMovements(FUND_B).first().single { it.transferId == transferId }
        env.movements.deleteMovement(leg.id)
        env.holdings.delete(FUND_B)
        assertEquals(listOf(FUND_A), env.holdings.observeAll().first().map { it.id })
        env.db.close()
    }

    @Test
    fun `refreshing prices never changes the units of a position with movements`() = runTest {
        val env = envFor(buildInMemoryTestDatabase(), ConstantQuoteSource(20_000_000))
        env.holdings.add(fund(FUND_A))
        env.movements.addMovement(FUND_A, buy(day(1, 15), units(10), 100_000))

        env.holdings.refreshPrices(force = true)

        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(FUND_A))
        assertEquals(20_000L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `observing movements gives them in the order they happened`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))
        env.holdings.add(fund(FUND_B))
        val late = env.movements.addMovement(FUND_A, buy(day(3, 1), units(1), 100))
        val early = env.movements.addMovement(FUND_A, buy(day(1, 1), units(1), 100))
        val other = env.movements.addMovement(FUND_B, buy(day(2, 1), units(1), 100))

        assertEquals(listOf(early, late), env.movements.observeMovements(FUND_A).first())
        assertEquals(listOf(early, other, late), env.movements.observeAllMovements().first())
        assertFalse(env.movements.observeHoldingIdsWithMovements().first().isEmpty())
        env.db.close()
    }

    @Test
    fun `movements read back as the domain model`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(FUND_A))

        val opening: Movement =
            env.movements.addOpeningBalance(FUND_A, day(1, 1), units(2), cost = null, note = "Antes")

        assertEquals(opening, env.movements.observeAllMovements().first().single())
        env.db.close()
    }
}
