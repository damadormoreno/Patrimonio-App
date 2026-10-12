package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceSyncEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.testing.FakePriceHistorySource
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW = Instant.parse("2026-10-11T12:00:00Z")
private const val HOUR_MS = 3_600_000L

private class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant

    fun advanceMs(millis: Long) {
        instant = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + millis)
    }
}

@RunWith(RobolectricTestRunner::class)
class InstrumentHistoryRepositoryTest {
    private lateinit var db: AppDatabase
    private val clock = MutableClock(NOW)
    private val source = FakePriceHistorySource()
    private val fund = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6")
    private val etf = InstrumentRef(QuoteProvider.YAHOO, "IWDA.AS")

    @Before
    fun setUp() {
        db = buildInMemoryTestDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(source: FakePriceHistorySource = this.source) =
        InstrumentHistoryRepositoryImpl(db, db.instrumentPriceDao(), source, clock)

    private fun call(ref: InstrumentRef, from: LocalDate? = null): Pair<InstrumentRef, LocalDate?> = ref to from

    private fun day(dayOfMonth: Int) = LocalDate(2026, 10, dayOfMonth)

    private fun eur(dayOfMonth: Int, scaled: Long) = PricePoint(day(dayOfMonth), Price(scaled, Currency.EUR))

    private val firstDays = (1..9).map { eur(it, 100_000_000L + it) }

    private suspend fun holdQuoted(id: String, ref: InstrumentRef) {
        if (db.portfolioDao().find(TEST_PORTFOLIO_ID) == null) db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                id = id,
                portfolioId = TEST_PORTFOLIO_ID,
                name = id,
                type = "FUND",
                isin = null,
                ticker = null,
                kind = "QUOTED",
                provider = ref.provider.name,
                providerCode = ref.code,
                currency = "EUR",
                unitsScaled = 1_000_000L,
                priceScaled = null,
                priceDateEpochDay = null,
                priceFetchedAtEpochMs = null,
                valueMinor = null,
                valuedAtEpochMs = null,
            ),
        )
    }

    @Test
    fun `a series that was never synced is downloaded in full and observed in ascending order`() = runTest {
        source.series[fund] = firstDays.reversed()
        val repository = repository()
        assertEquals(emptyList(), repository.observe(fund).first())

        repository.sync(fund, force = false)

        assertEquals(listOf(call(fund)), source.calls)
        // The source answered in reverse; the cache still hands out ascending dates.
        assertEquals(firstDays, repository.observe(fund).first())
        assertEquals(
            NOW.toEpochMilliseconds(),
            db.instrumentPriceDao().findSync("MORNINGSTAR", fund.code)?.lastSyncedAtEpochMs,
        )
    }

    @Test
    fun `a later sync asks only from a week before the last stored day and upserts the overlap`() = runTest {
        source.series[fund] = firstDays
        val repository = repository()
        repository.sync(fund, force = false)
        clock.advanceMs(7 * HOUR_MS)
        source.series[fund] = firstDays.dropLast(1) + eur(9, 7L) + eur(10, 8L)

        repository.sync(fund, force = false)

        assertEquals(fund to LocalDate(2026, 10, 2), source.calls.last())
        val stored = repository.observe(fund).first()
        assertEquals((1..10).toList(), stored.map { it.date.dayOfMonth })
        assertEquals(7L, stored[8].price.scaled, "the overlapping day is replaced, not duplicated")
        assertEquals(8L, stored[9].price.scaled)
        assertEquals(100_000_001L, stored[0].price.scaled, "days before the window are untouched")
    }

    @Test
    fun `a sync less than six hours after the previous one does nothing unless forced`() = runTest {
        source.series[fund] = firstDays
        val repository = repository()
        repository.sync(fund, force = false)

        clock.advanceMs(6 * HOUR_MS - 1)
        repository.sync(fund, force = false)
        assertEquals(1, source.calls.size)

        repository.sync(fund, force = true)
        assertEquals(2, source.calls.size)
        assertEquals(fund to LocalDate(2026, 10, 2), source.calls.last())
    }

    @Test
    fun `six hours exactly count as stale`() = runTest {
        source.series[fund] = firstDays
        val repository = repository()
        repository.sync(fund, force = false)

        clock.advanceMs(6 * HOUR_MS)
        repository.sync(fund, force = false)

        assertEquals(2, source.calls.size)
    }

    @Test
    fun `a clock that went backwards makes the series stale`() = runTest {
        source.series[fund] = firstDays
        val repository = repository()
        repository.sync(fund, force = false)

        clock.advanceMs(-HOUR_MS)
        repository.sync(fund, force = false)

        assertEquals(2, source.calls.size)
    }

    @Test
    fun `a failed sync keeps the cached history and is retried right away`() = runTest {
        source.series[fund] = firstDays
        val repository = repository()
        repository.sync(fund, force = false)
        clock.advanceMs(7 * HOUR_MS)
        source.failures[fund] = QuoteUnavailableException("offline")

        val failure = assertFailsWith<HistoryUnavailableException> { repository.sync(fund, force = false) }

        assertTrue(failure.cause is QuoteUnavailableException)
        assertEquals(firstDays, repository.observe(fund).first())
        // The failure did not count as a sync: asking again goes to the network again.
        assertFailsWith<HistoryUnavailableException> { repository.sync(fund, force = false) }
        assertEquals(3, source.calls.size)
        source.failures.clear()
        repository.sync(fund, force = false)
        assertEquals(4, source.calls.size)
    }

    @Test
    fun `a failed first download stores nothing and the next sync is a full download again`() = runTest {
        source.failures[fund] = QuoteUnavailableException("offline")
        val repository = repository()

        assertFailsWith<HistoryUnavailableException> { repository.sync(fund, force = false) }
        assertEquals(emptyList(), repository.observe(fund).first())
        assertNull(db.instrumentPriceDao().findSync("MORNINGSTAR", fund.code))

        source.failures.clear()
        source.series[fund] = firstDays
        repository.sync(fund, force = false)
        assertEquals(listOf(call(fund), call(fund)), source.calls)
        assertEquals(firstDays, repository.observe(fund).first())
    }

    @Test
    fun `an empty first download counts as a sync and is retried in full after six hours`() = runTest {
        source.series[fund] = emptyList()
        val repository = repository()

        repository.sync(fund, force = false)
        repository.sync(fund, force = false)
        assertEquals(1, source.calls.size)
        assertEquals(emptyList(), repository.observe(fund).first())

        clock.advanceMs(6 * HOUR_MS)
        repository.sync(fund, force = false)
        assertEquals(listOf(call(fund), call(fund)), source.calls)
    }

    @Test
    fun `the same code under two providers is two different series`() = runTest {
        val yahooTwin = InstrumentRef(QuoteProvider.YAHOO, fund.code)
        source.series[fund] = listOf(eur(1, 1L))
        source.series[yahooTwin] = listOf(PricePoint(day(1), Price(2L, Currency.USD)))
        val repository = repository()

        repository.sync(fund, force = false)
        repository.sync(yahooTwin, force = false)

        assertEquals(listOf(eur(1, 1L)), repository.observe(fund).first())
        assertEquals(listOf(PricePoint(day(1), Price(2L, Currency.USD))), repository.observe(yahooTwin).first())
    }

    @Test
    fun `prices keep the currency the source reported`() = runTest {
        source.series[etf] = listOf(PricePoint(day(1), Price(131_670_000L, Currency.GBP)))
        val repository = repository()

        repository.sync(etf, force = false)

        assertEquals(Currency.GBP, repository.observe(etf).first().single().price.currency)
    }

    @Test
    fun `syncAll downloads every series with at most four requests in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = FakePriceHistorySource(gate)
        val refs = (1..10).map { InstrumentRef(QuoteProvider.MORNINGSTAR, "SEC$it") }
        refs.forEachIndexed { index, ref ->
            gated.series[ref] = listOf(eur(1, 1L))
            holdQuoted("h$index", ref)
        }
        val repository = repository(gated)

        val sync = async { repository.syncAll(refs, force = false) }
        // Each download reads Room (on its own threads) before asking the source, so the requests reach the gate in
        // real time, not in the test's virtual time: wait for the limiter to fill up, then give a fifth one the chance
        // to slip through before opening the gate.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (gated.inFlight < 4) delay(10) }
            delay(200)
        }
        gate.complete(Unit)
        val result = sync.await()

        assertEquals(10, result.synced)
        assertEquals(0, result.upToDate)
        assertEquals(emptyList(), result.failures)
        assertEquals(4, gated.maxInFlight)
    }

    @Test
    fun `syncAll skips fresh series reports failures and goes on with the others`() = runTest {
        val broken = InstrumentRef(QuoteProvider.MORNINGSTAR, "BROKEN")
        source.series[fund] = firstDays
        source.series[etf] = listOf(eur(1, 1L))
        source.failures[broken] = QuoteUnavailableException("HTTP 500")
        listOf(fund, etf, broken).forEachIndexed { index, ref -> holdQuoted("h$index", ref) }
        val repository = repository()
        repository.sync(fund, force = false)
        source.calls.clear()

        val result = repository.syncAll(listOf(fund, etf, broken), force = false)

        assertEquals(1, result.synced)
        assertEquals(1, result.upToDate)
        assertEquals(listOf(broken), result.failures.map { it.ref })
        assertEquals(setOf(etf, broken), source.calls.map { it.first }.toSet())
        assertEquals(listOf(eur(1, 1L)), repository.observe(etf).first())
    }

    @Test
    fun `a forced syncAll goes to the network for fresh series too`() = runTest {
        source.series[fund] = firstDays
        holdQuoted("h", fund)
        val repository = repository()
        repository.syncAll(listOf(fund), force = false)

        val result = repository.syncAll(listOf(fund), force = true)

        assertEquals(1, result.synced)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `syncAll prunes the series that no holding references any more`() = runTest {
        val gone = InstrumentRef(QuoteProvider.MORNINGSTAR, "GONE")
        source.series[fund] = firstDays
        holdQuoted("h", fund)
        db.instrumentPriceDao().upsertAll(listOf(InstrumentPriceEntity("MORNINGSTAR", "GONE", 20_000L, 5L, "EUR")))
        db.instrumentPriceDao().upsertSync(InstrumentPriceSyncEntity("MORNINGSTAR", "GONE", 1L))
        val repository = repository()

        repository.syncAll(listOf(fund), force = false)

        assertEquals(emptyList(), repository.observe(gone).first())
        assertNull(db.instrumentPriceDao().findSync("MORNINGSTAR", "GONE"))
        assertNotNull(db.instrumentPriceDao().findSync("MORNINGSTAR", fund.code))
        assertEquals(firstDays, repository.observe(fund).first())
    }

    @Test
    fun `a manual holding does not keep a series alive`() = runTest {
        db.instrumentPriceDao().upsertAll(listOf(InstrumentPriceEntity("MORNINGSTAR", "ORPHAN", 20_000L, 5L, "EUR")))
        db.instrumentPriceDao().upsertSync(InstrumentPriceSyncEntity("MORNINGSTAR", "ORPHAN", 1L))

        repository().syncAll(emptyList(), force = false)

        assertEquals(0, db.instrumentPriceDao().count())
    }
}
