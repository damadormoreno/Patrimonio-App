package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.calc.InsufficientUnitsException
import com.denebapps.patrimonio.domain.importing.ImportResult
import com.denebapps.patrimonio.domain.importing.MyInvestorReaderTest
import com.denebapps.patrimonio.domain.importing.planImport
import com.denebapps.patrimonio.domain.importing.readMyInvestorFile
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
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.HoldingMovementsSummary
import com.denebapps.patrimonio.domain.repository.InvalidMovementException
import com.denebapps.patrimonio.domain.repository.MissingEurEquivalentException
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import com.denebapps.patrimonio.domain.repository.QuoteSource
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
import kotlin.test.assertTrue

private val IMPORT_NOW = Instant.parse("2026-10-11T12:00:00Z")
private val IMPORT_TODAY = LocalDate(2026, 10, 9)
private const val IMPORT_FUND = "fund-a"
private const val IMPORT_OTHER = "fund-b"

private class ImportFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Answers every quote with 10 EUR. */
private class ImportConstantQuoteSource : QuoteSource {
    override suspend fun search(query: String): List<InstrumentCandidate> = error("not used")

    override suspend fun latestQuote(ref: InstrumentRef) =
        Quote(ref, Price(10_000_000, Currency.EUR), LocalDate(2026, 10, 10))
}

/** The part of a movement an import decides, without the ids and entry order it gets when stored. */
private data class MovementContent(
    val holdingId: String,
    val type: MovementType,
    val date: LocalDate,
    val units: Units?,
    val amount: CurrencyAmount?,
    val amountEur: Money?,
    val fee: Money,
    val note: String?,
)

private fun Movement.content() = MovementContent(holdingId, type, date, units, amount, amountEur, fee, note)

private fun day(month: Int, day: Int) = LocalDate(2026, month, day)

private fun units(whole: Long, millionths: Long = 0) = Units(whole * UNITS_SCALE + millionths)

private fun eur(cents: Long) = CurrencyAmount(Money(cents), Currency.EUR)

@RunWith(RobolectricTestRunner::class)
class MovementImportRepositoryTest {
    private fun fund(id: String, currency: Currency = Currency.EUR, startingUnits: Units = units(1)) = PortfolioHolding(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = id,
        type = InstrumentType.FUND,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Quoted(
            ref = InstrumentRef(QuoteProvider.MORNINGSTAR, id),
            currency = currency,
            units = startingUnits,
            lastQuote = LastQuote(Price(10_000_000, currency), IMPORT_TODAY, IMPORT_NOW.toEpochMilliseconds()),
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

    private fun buy(date: LocalDate, units: Units, cents: Long, note: String? = "MyInvestor") =
        MovementDraft(MovementType.BUY, date, units, eur(cents), note = note)

    private fun sell(date: LocalDate, units: Units, cents: Long) =
        MovementDraft(MovementType.SELL, date, units, eur(cents))

    private val lots = listOf(
        buy(day(1, 15), units(4), 40_000),
        buy(day(2, 10), units(3, 500_000), 36_000),
        buy(day(3, 20), units(2, 500_000), 25_000),
    )

    private class Env(
        val db: AppDatabase,
        val holdings: PortfolioRepositoryImpl,
        val movements: MovementRepositoryImpl,
    ) {
        suspend fun unitsOf(id: String): UnitsBalance =
            (holdings.observeAll().first().single { it.id == id }.valuation as HoldingValuation.Quoted).units

        suspend fun snapshotAssets(): Long? = db.netWorthDao().find("2026-10")?.assetsMinor

        suspend fun contentOf(id: String): List<MovementContent> =
            movements.observeMovements(id).first().map { it.content() }
    }

    private fun CoroutineScope.envFor(db: AppDatabase): Env {
        val clock = ImportFixedClock(IMPORT_NOW)
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
                ImportConstantQuoteSource(),
                upserter,
                gate,
                clock,
                FakeFundamentalsSource(),
            ),
            MovementRepositoryImpl(db, upserter, gate, clock),
        )
    }

    private fun drafts(vararg drafts: MovementDraft, holdingId: String = IMPORT_FUND) =
        drafts.map { it.toContent(holdingId) }

    private fun MovementDraft.toContent(holdingId: String) =
        MovementContent(holdingId, type, date, units, amount, amountEur ?: amount?.amount, fee, note)

    @Test
    fun `replacing the movements of a holding with typed units makes the units derived from the drafts`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND, startingUnits = units(7)))

        val stored = env.movements.replaceHoldingMovements(IMPORT_FUND, lots)

        assertEquals(drafts(*lots.toTypedArray()), stored.map { it.content() })
        assertEquals(drafts(*lots.toTypedArray()), env.contentOf(IMPORT_FUND))
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        assertEquals(setOf(IMPORT_FUND), env.movements.observeHoldingIdsWithMovements().first())
        env.db.close()
    }

    @Test
    fun `replacing swaps buys, sells and dividends for the drafts and leaves other holdings alone`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND))
        env.holdings.add(fund(IMPORT_OTHER))
        env.movements.addMovement(IMPORT_FUND, buy(day(1, 5), units(20), 200_000))
        env.movements.addMovement(IMPORT_FUND, sell(day(1, 25), units(5), 60_000))
        env.movements.addMovement(IMPORT_FUND, MovementDraft(MovementType.DIVIDEND, day(2, 1), amount = eur(900)))
        val other = env.movements.addMovement(IMPORT_OTHER, buy(day(1, 5), units(2), 20_000))

        env.movements.replaceHoldingMovements(IMPORT_FUND, lots)

        assertEquals(drafts(*lots.toTypedArray()), env.contentOf(IMPORT_FUND))
        assertEquals(listOf(other), env.movements.observeMovements(IMPORT_OTHER).first())
        assertEquals(4, env.db.movementDao().list().size)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        assertEquals(UnitsBalance(2 * UNITS_SCALE), env.unitsOf(IMPORT_OTHER))
        env.db.close()
    }

    @Test
    fun `importing the same drafts again leaves the same movements and no duplicates`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND))

        env.movements.replaceHoldingMovements(IMPORT_FUND, lots)
        val first = env.contentOf(IMPORT_FUND)
        env.movements.replaceHoldingMovements(IMPORT_FUND, lots)

        assertEquals(first, env.contentOf(IMPORT_FUND))
        assertEquals(3, env.db.movementDao().list().size)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        env.db.close()
    }

    @Test
    fun `replacing refreshes the net worth of the current month`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND, startingUnits = units(1)))
        env.movements.replaceHoldingMovements(IMPORT_FUND, lots)
        // 10 units at 10 EUR.
        assertEquals(10_000L, env.snapshotAssets())

        env.movements.replaceHoldingMovements(IMPORT_FUND, listOf(buy(day(1, 15), units(3), 30_000)))

        assertEquals(3_000L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `a holding with a transfer is refused and both legs and the units stay as they were`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND))
        env.holdings.add(fund(IMPORT_OTHER))
        env.movements.addMovement(IMPORT_FUND, buy(day(1, 15), units(10), 100_000))
        env.movements.addTransfer(IMPORT_FUND, IMPORT_OTHER, day(2, 1), day(2, 1), units(4), units(4), null)
        val before = env.db.movementDao().list()
        val snapshotBefore = env.snapshotAssets()

        assertFailsWith<HoldingHasTransfersException> { env.movements.replaceHoldingMovements(IMPORT_FUND, lots) }
        assertFailsWith<HoldingHasTransfersException> { env.movements.replaceHoldingMovements(IMPORT_OTHER, lots) }

        assertEquals(before, env.db.movementDao().list())
        assertEquals(UnitsBalance(6 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        assertEquals(UnitsBalance(4 * UNITS_SCALE), env.unitsOf(IMPORT_OTHER))
        assertEquals(snapshotBefore, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `an empty list is refused and nothing changes`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND))
        env.movements.addMovement(IMPORT_FUND, buy(day(1, 15), units(10), 100_000))
        val before = env.db.movementDao().list()

        assertFailsWith<InvalidMovementException> { env.movements.replaceHoldingMovements(IMPORT_FUND, emptyList()) }

        assertEquals(before, env.db.movementDao().list())
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        env.db.close()
    }

    @Test
    fun `invalid drafts are refused with a typed error and nothing changes`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND))
        env.movements.addMovement(IMPORT_FUND, buy(day(1, 15), units(10), 100_000))
        val before = env.db.movementDao().list()
        val snapshotBefore = env.snapshotAssets()
        val inDollars = MovementDraft(MovementType.BUY, day(2, 1), units(1), CurrencyAmount(Money(100), Currency.USD))
        val transferLeg = MovementDraft(MovementType.TRANSFER_IN, day(2, 1), units(1))

        // The first draft is fine: a refused one later in the list must undo the whole replacement.
        assertFailsWith<InvalidMovementException> {
            env.movements.replaceHoldingMovements(
                IMPORT_FUND,
                lots + inDollars,
            )
        }
        assertFailsWith<InvalidMovementException> {
            env.movements.replaceHoldingMovements(IMPORT_FUND, lots + transferLeg)
        }
        assertFailsWith<InvalidMovementException> {
            env.movements.replaceHoldingMovements(IMPORT_FUND, listOf(MovementDraft(MovementType.BUY, day(2, 1))))
        }
        assertFailsWith<InsufficientUnitsException> {
            env.movements.replaceHoldingMovements(IMPORT_FUND, lots + sell(day(4, 1), units(11), 120_000))
        }

        assertEquals(before, env.db.movementDao().list())
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        assertEquals(snapshotBefore, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `replacing needs an existing quoted holding`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(painting("art"))

        assertFailsWith<PortfolioHoldingNotFoundException> { env.movements.replaceHoldingMovements("ghost", lots) }
        assertFailsWith<InvalidMovementException> { env.movements.replaceHoldingMovements("art", lots) }
        assertTrue(env.db.movementDao().list().isEmpty())
        env.db.close()
    }

    @Test
    fun `a holding in another currency needs the euro amounts of every draft`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund("usd", currency = Currency.USD))
        val usdBuy = MovementDraft(MovementType.BUY, day(1, 15), units(1), CurrencyAmount(Money(100), Currency.USD))

        assertFailsWith<MissingEurEquivalentException> { env.movements.replaceHoldingMovements("usd", listOf(usdBuy)) }

        assertTrue(env.db.movementDao().list().isEmpty())
        env.db.close()
    }

    @Test
    fun `the summary tells how many movements would be replaced and whether there are transfers`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND))
        env.holdings.add(fund(IMPORT_OTHER))
        assertEquals(HoldingMovementsSummary(0, hasTransfers = false), env.movements.movementsSummary(IMPORT_FUND))

        env.movements.addMovement(IMPORT_FUND, buy(day(1, 15), units(10), 100_000))
        env.movements.addMovement(IMPORT_FUND, buy(day(1, 20), units(5), 50_000))
        assertEquals(HoldingMovementsSummary(2, hasTransfers = false), env.movements.movementsSummary(IMPORT_FUND))

        env.movements.addTransfer(IMPORT_FUND, IMPORT_OTHER, day(2, 1), day(2, 1), units(4), units(4), null)
        assertEquals(HoldingMovementsSummary(3, hasTransfers = true), env.movements.movementsSummary(IMPORT_FUND))
        assertEquals(HoldingMovementsSummary(1, hasTransfers = true), env.movements.movementsSummary(IMPORT_OTHER))
        assertFailsWith<PortfolioHoldingNotFoundException> { env.movements.movementsSummary("ghost") }
        env.db.close()
    }

    @Test
    fun `a holding is created together with its purchases and its units come from them`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())

        val stored = env.movements.addHoldingWithPurchases(fund(IMPORT_FUND, startingUnits = units(99)), lots)

        assertEquals(drafts(*lots.toTypedArray()), stored.map { it.content() })
        assertEquals(drafts(*lots.toTypedArray()), env.contentOf(IMPORT_FUND))
        assertEquals(listOf(IMPORT_FUND), env.holdings.observeAll().first().map { it.id })
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        assertEquals(10_000L, env.snapshotAssets())
        env.db.close()
    }

    @Test
    fun `purchases that cannot be recorded leave no holding behind`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        val inDollars = MovementDraft(MovementType.BUY, day(2, 1), units(1), CurrencyAmount(Money(100), Currency.USD))

        assertFailsWith<InvalidMovementException> {
            env.movements.addHoldingWithPurchases(
                fund(IMPORT_FUND),
                emptyList(),
            )
        }
        assertFailsWith<InvalidMovementException> {
            env.movements.addHoldingWithPurchases(fund(IMPORT_FUND), lots + sell(day(4, 1), units(1), 100))
        }
        assertFailsWith<InvalidMovementException> {
            env.movements.addHoldingWithPurchases(fund(IMPORT_FUND), lots + inDollars)
        }
        assertFailsWith<InvalidMovementException> { env.movements.addHoldingWithPurchases(painting("art"), lots) }
        assertFailsWith<PortfolioNotFoundException> {
            env.movements.addHoldingWithPurchases(fund(IMPORT_FUND).copy(portfolioId = "ghost"), lots)
        }

        assertTrue(env.holdings.observeAll().first().isEmpty())
        assertTrue(env.db.movementDao().list().isEmpty())
        env.db.close()
    }

    @Test
    fun `a single purchase still creates its holding as before`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())

        val stored = env.movements.addHoldingWithPurchase(fund(IMPORT_FUND), buy(day(1, 15), units(10), 100_000))

        assertEquals(IMPORT_FUND, stored.holdingId)
        assertEquals(UnitsBalance(10 * UNITS_SCALE), env.unitsOf(IMPORT_FUND))
        env.db.close()
    }

    @Test
    fun `a plan made from a file replaces the movements with exact units and costs`() = runTest {
        val env = envFor(buildInMemoryTestDatabase())
        env.holdings.add(fund(IMPORT_FUND, startingUnits = units(7)))
        val file = (
            readMyInvestorFile(MyInvestorReaderTest.SAMPLE_CRLF.encodeToByteArray(), MyInvestorReaderTest.FILE_NAME)
                as ImportResult.Ok
            ).value
        val total = Units(123_456_789L)
        val plan = (planImport(file, total, IMPORT_FUND) as ImportResult.Ok).value

        env.movements.replaceHoldingMovements(IMPORT_FUND, plan.buys)
        env.movements.replaceHoldingMovements(IMPORT_FUND, plan.buys) // re-import

        val stored = env.movements.observeMovements(IMPORT_FUND).first()
        assertEquals(5, stored.size)
        assertEquals(total.scaled, stored.sumOf { it.units!!.scaled })
        assertEquals(UnitsBalance(total.scaled), env.unitsOf(IMPORT_FUND))
        assertEquals(plan.summary.totalCost, Money(stored.sumOf { it.amountEur!!.minorUnits }))
        assertEquals(plan.buys.map { it.amountEur }, stored.map { it.amountEur })
        assertEquals(plan.buys.map { it.date }, stored.map { it.date })
        env.db.close()
    }
}
