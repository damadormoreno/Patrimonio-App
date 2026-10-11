package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PriceRefreshFailure
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

private val NOW = Instant.parse("2026-10-11T12:00:00Z")
private const val HOUR_MS = 3_600_000L
private val TODAY = LocalDate(2026, 10, 9)

private class PortfolioFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Answers [latestQuote] from [answers] (a quote, or the failure to throw) and records every call. */
private class FakeQuoteSource(
    private val answers: MutableMap<InstrumentRef, () -> Quote> = mutableMapOf(),
    private val latencyMs: Long = 0,
) : QuoteSource {
    val calls = mutableListOf<InstrumentRef>()
    var inFlight = 0
    var maxInFlight = 0

    fun answer(ref: InstrumentRef, quote: () -> Quote) {
        answers[ref] = quote
    }

    override suspend fun search(query: String): List<InstrumentCandidate> = error("search is not used by the portfolio")

    override suspend fun latestQuote(ref: InstrumentRef): Quote {
        calls += ref
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            if (latencyMs > 0) delay(latencyMs)
            return (answers[ref] ?: throw QuoteUnavailableException("no answer for ${ref.code}"))()
        } finally {
            inFlight--
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class PortfolioRepositoryTest {
    private fun ref(code: String) = InstrumentRef(QuoteProvider.MORNINGSTAR, code)

    private fun quoted(
        id: String,
        name: String = id,
        code: String = id,
        currency: Currency = Currency.EUR,
        units: Long = 2_500_000,
        lastQuote: LastQuote? = null,
    ) = PortfolioHolding(
        id = id,
        name = name,
        type = InstrumentType.FUND,
        isin = "IE00B03HCZ61",
        ticker = null,
        valuation = HoldingValuation.Quoted(ref(code), currency, Units(units), lastQuote),
    )

    private fun manual(id: String, name: String = id) = PortfolioHolding(
        id = id,
        name = name,
        type = InstrumentType.OTHER,
        isin = null,
        ticker = null,
        valuation = HoldingValuation.Manual(CurrencyAmount(Money(250_000), Currency.EUR), valuedAtEpochMs = 7L),
    )

    private fun lastQuote(scaled: Long, fetchedAgoMs: Long, currency: Currency = Currency.EUR) =
        LastQuote(Price(scaled, currency), TODAY, NOW.toEpochMilliseconds() - fetchedAgoMs)

    private fun quote(code: String, scaled: Long, currency: Currency = Currency.EUR) =
        Quote(ref(code), Price(scaled, currency), LocalDate(2026, 10, 10))

    private fun CoroutineScope.repositoryFor(
        db: AppDatabase,
        source: QuoteSource = FakeQuoteSource(),
        clock: Clock = PortfolioFixedClock(NOW),
    ) = PortfolioRepositoryImpl(
        db,
        db.portfolioHoldingDao(),
        source,
        NetWorthSnapshotUpserter(
            db.assetDao(),
            db.liabilityDao(),
            db.portfolioHoldingDao(),
            db.fxRateDao(),
            db.netWorthDao(),
            clock,
        ) { TimeZone.UTC },
        testSeedingGate(db),
        clock,
    )

    private suspend fun AppDatabase.snapshotAssets(): Long? = netWorthDao().find("2026-10")?.assetsMinor

    private fun PortfolioHolding.quotedValuation() = valuation as HoldingValuation.Quoted

    @Test
    fun `holdings are stored and observed ordered by name with both variants intact`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val priced = quoted("p2", name = "vanguard", lastQuote = lastQuote(12_345_678, HOUR_MS))
        val unpriced = quoted("p1", name = "Amundi")
        val painting = manual("p3", name = "Cuadro")

        repository.add(priced)
        repository.add(unpriced)
        repository.add(painting)

        assertEquals(listOf(unpriced, painting, priced), repository.observeAll().first())
        db.close()
    }

    @Test
    fun `adding an id that already exists fails and keeps the stored holding`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(quoted("p1", name = "Original"))

        assertTrue(runCatching { repository.add(quoted("p1", name = "Duplicate")) }.isFailure)

        assertEquals(listOf("Original"), repository.observeAll().first().map { it.name })
        db.close()
    }

    @Test
    fun `updating the units changes only the units of a quoted holding`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val original = quoted("p1", lastQuote = lastQuote(12_345_678, HOUR_MS))
        repository.add(original)

        repository.updateUnits("p1", Units(8_100_001))

        val stored = repository.observeAll().first().single()
        assertEquals(Units(8_100_001), stored.quotedValuation().units)
        assertEquals(original.quotedValuation().lastQuote, stored.quotedValuation().lastQuote)
        db.close()
    }

    @Test
    fun `updating units rejects unknown and manual holdings`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(manual("m1"))

        assertFailsWith<PortfolioHoldingNotFoundException> { repository.updateUnits("nope", Units(1)) }
        assertFailsWith<IllegalStateException> { repository.updateUnits("m1", Units(1)) }
        db.close()
    }

    @Test
    fun `updating a manual value stores it stamped with the current time`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(manual("m1"))

        repository.updateManualValue("m1", CurrencyAmount(Money(9_900), Currency.USD))

        assertEquals(
            HoldingValuation.Manual(CurrencyAmount(Money(9_900), Currency.USD), NOW.toEpochMilliseconds()),
            repository.observeAll().first().single().valuation,
        )
        db.close()
    }

    @Test
    fun `updating a manual value rejects unknown and quoted holdings`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(quoted("p1"))
        val value = CurrencyAmount(Money(1), Currency.EUR)

        assertFailsWith<PortfolioHoldingNotFoundException> { repository.updateManualValue("nope", value) }
        assertFailsWith<IllegalStateException> { repository.updateManualValue("p1", value) }
        db.close()
    }

    @Test
    fun `deleting removes the holding and an unknown id is reported`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(quoted("p1"))
        repository.add(manual("m1"))

        repository.delete("p1")

        assertEquals(listOf("m1"), repository.observeAll().first().map { it.id })
        assertFailsWith<PortfolioHoldingNotFoundException> { repository.delete("p1") }
        db.close()
    }

    @Test
    fun `a refresh fetches only stale or never priced holdings and stores price date and fetch time`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("never")) { quote("never", 10_000_000) }
        source.answer(ref("old")) { quote("old", 20_000_000) }
        source.answer(ref("fresh")) { quote("fresh", 30_000_000) }
        val repository = repositoryFor(db, source)
        repository.add(quoted("never"))
        repository.add(quoted("old", lastQuote = lastQuote(19_000_000, 7 * HOUR_MS)))
        val fresh = quoted("fresh", lastQuote = lastQuote(29_000_000, HOUR_MS))
        repository.add(fresh)
        repository.add(manual("manual"))

        val result = repository.refreshPrices(force = false)

        assertEquals(2, result.refreshed)
        assertEquals(1, result.upToDate)
        assertEquals(emptyList(), result.failures)
        assertEquals(setOf(ref("never"), ref("old")), source.calls.toSet())
        val byId = repository.observeAll().first().associateBy { it.id }
        val expectedQuote = { scaled: Long ->
            LastQuote(Price(scaled, Currency.EUR), LocalDate(2026, 10, 10), NOW.toEpochMilliseconds())
        }
        assertEquals(expectedQuote(10_000_000), byId.getValue("never").quotedValuation().lastQuote)
        assertEquals(expectedQuote(20_000_000), byId.getValue("old").quotedValuation().lastQuote)
        assertEquals(fresh, byId.getValue("fresh"))
        db.close()
    }

    @Test
    fun `a price is stale only once it is older than six hours`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("edge")) { quote("edge", 1_000_000) }
        source.answer(ref("past")) { quote("past", 1_000_000) }
        val repository = repositoryFor(db, source)
        repository.add(quoted("edge", lastQuote = lastQuote(500_000, 6 * HOUR_MS)))
        repository.add(quoted("past", lastQuote = lastQuote(500_000, 6 * HOUR_MS + 1)))

        val result = repository.refreshPrices(force = false)

        assertEquals(listOf(ref("past")), source.calls)
        assertEquals(1, result.refreshed)
        assertEquals(1, result.upToDate)
        db.close()
    }

    @Test
    fun `a price fetched in the future because the clock went back is refreshed`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("future")) { quote("future", 1_000_000) }
        val repository = repositoryFor(db, source)
        repository.add(quoted("future", lastQuote = lastQuote(500_000, -HOUR_MS)))

        val result = repository.refreshPrices(force = false)

        assertEquals(listOf(ref("future")), source.calls)
        assertEquals(1, result.refreshed)
        db.close()
    }

    @Test
    fun `force refreshes every quoted holding and never a manual one`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("a")) { quote("a", 10_000_000) }
        source.answer(ref("b")) { quote("b", 20_000_000) }
        val repository = repositoryFor(db, source)
        repository.add(quoted("a", lastQuote = lastQuote(1, 1)))
        repository.add(quoted("b", lastQuote = lastQuote(1, 2)))
        repository.add(manual("m"))

        val result = repository.refreshPrices(force = true)

        assertEquals(2, result.refreshed)
        assertEquals(0, result.upToDate)
        assertEquals(setOf(ref("a"), ref("b")), source.calls.toSet())
        db.close()
    }

    @Test
    fun `a failed fetch keeps the last price is reported and does not stop the others`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("ok")) { quote("ok", 10_000_000) }
        source.answer(ref("broken")) { throw QuoteUnavailableException("provider down") }
        val repository = repositoryFor(db, source)
        val previous = lastQuote(19_000_000, 24 * HOUR_MS)
        repository.add(quoted("ok", name = "Works"))
        repository.add(quoted("broken", name = "Breaks", lastQuote = previous))

        val result = repository.refreshPrices(force = false)

        assertEquals(1, result.refreshed)
        assertEquals(listOf(PriceRefreshFailure("broken", "Breaks", "provider down")), result.failures)
        val byId = repository.observeAll().first().associateBy { it.id }
        assertEquals(previous, byId.getValue("broken").quotedValuation().lastQuote)
        assertEquals(10_000_000L, byId.getValue("ok").quotedValuation().lastQuote?.price?.scaled)
        db.close()
    }

    @Test
    fun `an unexpected exception from the source is a failure of that holding too`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("odd")) { error("boom") }
        val repository = repositoryFor(db, source)
        repository.add(quoted("odd", name = "Odd"))

        val result = repository.refreshPrices(force = true)

        assertEquals(listOf("odd"), result.failures.map { it.holdingId })
        assertEquals(null, repository.observeAll().first().single().quotedValuation().lastQuote)
        db.close()
    }

    @Test
    fun `a quote in another currency is a failure and the holding keeps its currency and price`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("fx")) { quote("fx", 30_000_000, Currency.USD) }
        val repository = repositoryFor(db, source)
        val previous = lastQuote(19_000_000, 24 * HOUR_MS)
        repository.add(quoted("fx", name = "Euro fund", lastQuote = previous))

        val result = repository.refreshPrices(force = false)

        assertEquals(0, result.refreshed)
        assertEquals(listOf("fx"), result.failures.map { it.holdingId })
        assertTrue("USD" in result.failures.single().reason, result.failures.single().reason)
        val stored = repository.observeAll().first().single().quotedValuation()
        assertEquals(Currency.EUR, stored.currency)
        assertEquals(previous, stored.lastQuote)
        db.close()
    }

    @Test
    fun `requests run concurrently but never more than four at a time`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource(latencyMs = 100)
        val repository = repositoryFor(db, source)
        repeat(10) { index ->
            source.answer(ref("h$index")) { quote("h$index", 1_000_000L * (index + 1)) }
            repository.add(quoted("h$index"))
        }

        val result = repository.refreshPrices(force = true)

        assertEquals(10, result.refreshed)
        assertEquals(4, source.maxInFlight)
        db.close()
    }

    @Test
    fun `a refresh with nothing to fetch does nothing`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        val repository = repositoryFor(db, source)
        repository.add(manual("m"))

        val result = repository.refreshPrices(force = true)

        assertEquals(0, result.refreshed)
        assertEquals(0, result.upToDate)
        assertEquals(emptyList(), source.calls)
        db.close()
    }

    @Test
    fun `adding a holding refreshes the current month snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        // 2.5 units at 10 EUR = 25.00 EUR; the manual one is 2,500.00 EUR.
        repository.add(quoted("p1", lastQuote = lastQuote(10_000_000, HOUR_MS)))
        assertEquals(25_00L, db.snapshotAssets())
        repository.add(manual("m1"))

        assertEquals(25_00L + 250_000L, db.snapshotAssets())
        db.close()
    }

    @Test
    fun `changing the units or the manual value refreshes the current month snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(quoted("p1", lastQuote = lastQuote(10_000_000, HOUR_MS)))
        repository.add(manual("m1"))

        repository.updateUnits("p1", Units(4_000_000))
        assertEquals(40_00L + 250_000L, db.snapshotAssets())
        repository.updateManualValue("m1", CurrencyAmount(Money(100_000), Currency.EUR))

        assertEquals(40_00L + 100_000L, db.snapshotAssets())
        db.close()
    }

    @Test
    fun `deleting a holding refreshes the current month snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(manual("m1"))
        repository.add(manual("m2"))

        repository.delete("m1")

        assertEquals(250_000L, db.snapshotAssets())
        db.close()
    }

    @Test
    fun `a failed delete leaves the snapshot alone`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.add(manual("m1"))

        assertFailsWith<PortfolioHoldingNotFoundException> { repository.delete("missing") }

        assertEquals(250_000L, db.snapshotAssets())
        db.close()
    }

    @Test
    fun `a refresh that stores prices updates the snapshot once with the new values`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("a")) { quote("a", 10_000_000) }
        source.answer(ref("b")) { quote("b", 20_000_000) }
        val repository = repositoryFor(db, source)
        repository.add(quoted("a"))
        repository.add(quoted("b"))
        assertEquals(0L, db.snapshotAssets())

        repository.refreshPrices(force = false)

        // 2.5 units at 10 EUR plus 2.5 units at 20 EUR.
        assertEquals(25_00L + 50_00L, db.snapshotAssets())
        db.close()
    }

    @Test
    fun `a refresh where every fetch fails does not write the snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        val source = FakeQuoteSource()
        source.answer(ref("broken")) { throw QuoteUnavailableException("provider down") }
        val repository = repositoryFor(db, source)
        repository.add(manual("m1"))
        repository.add(quoted("broken"))
        // Something else stored a different value: only a refresh that stores a price may recompute it.
        db.netWorthDao().upsert(NetWorthSnapshotEntity("2026-10", 1L, 0L))

        val result = repository.refreshPrices(force = true)

        assertEquals(1, result.failures.size)
        assertEquals(1L, db.snapshotAssets())
        db.close()
    }
}
