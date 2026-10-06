package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.Currency
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private const val SUCCESS_BODY =
    """{"amount":1.0,"base":"EUR","date":"2026-07-09","rates":{"USD":1.0870,"GBP":1.17,"JPY":163.9}}"""

/** Builds a [FrankfurterApi] whose [HttpClient] runs over a Ktor `MockEngine`; [requestCount] is
 *  incremented on every intercepted request, letting tests assert whether a network call happened
 *  at all without depending on real I/O (spec: rolling-24h-refresh). */
private fun frankfurterApi(
    requestCount: IntArray,
    responseBody: String = SUCCESS_BODY,
    status: HttpStatusCode = HttpStatusCode.OK,
): FrankfurterApi {
    val engine = MockEngine { _ ->
        requestCount[0]++
        respond(
            content = responseBody,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return FrankfurterApi(client)
}

/** An [FrankfurterApi] whose underlying engine throws (offline/connection failure), for the
 *  fetch-failure-non-fatal scenario. */
private fun offlineFrankfurterApi(): FrankfurterApi {
    val engine = MockEngine { throw IOException("offline") }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return FrankfurterApi(client)
}

@RunWith(RobolectricTestRunner::class)
class FxRefreshPolicyTest {
    private val now = Instant.parse("2026-07-09T12:00:00Z")
    private val clock = FixedClock(now)

    @Test
    fun `observeRates returns the static seed rates before any fetch has completed`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        val repo = FxRepositoryImpl(db.fxRateDao(), frankfurterApi(intArrayOf(0)), clock, seedingGate)

        val rates = repo.observeRates().first()

        assertEquals(920_000L, rates.rateToEurScaled(Currency.USD))
        assertEquals(1_170_000L, rates.rateToEurScaled(Currency.GBP))
        assertEquals(6_100L, rates.rateToEurScaled(Currency.JPY))
        assertEquals(1_000_000L, rates.rateToEurScaled(Currency.EUR))
        db.close()
    }

    @Test
    fun `refreshIfStale performs a network fetch when the non-EUR rows are at least 24h stale`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val requestCount = intArrayOf(0)
        val repo = FxRepositoryImpl(db.fxRateDao(), frankfurterApi(requestCount), clock, seedingGate)

        repo.refreshIfStale()

        assertEquals(1, requestCount[0])
        val usd = db.fxRateDao().list().first { it.currency == "USD" }
        assertEquals(919_963L, usd.rateToEurScaled)
        assertEquals(now.toEpochMilliseconds(), usd.fetchedAtEpochMs)
        db.close()
    }

    @Test
    fun `refreshIfStale skips the network fetch when non-EUR rows are fresh`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val freshMs = now.minus(1.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, freshMs),
                FxRateEntity("GBP", 1_100_000, freshMs),
                FxRateEntity("JPY", 6_000, freshMs),
            ),
        )
        val requestCount = intArrayOf(0)
        val repo = FxRepositoryImpl(db.fxRateDao(), frankfurterApi(requestCount), clock, seedingGate)

        repo.refreshIfStale()

        assertEquals(0, requestCount[0])
        assertEquals(900_000L, db.fxRateDao().list().first { it.currency == "USD" }.rateToEurScaled)
        db.close()
    }

    @Test
    fun `refreshIfStale keeps cached rates and does not throw when the network call fails`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val repo = FxRepositoryImpl(db.fxRateDao(), offlineFrankfurterApi(), clock, seedingGate)

        repo.refreshIfStale() // must not throw

        assertEquals(900_000L, db.fxRateDao().list().first { it.currency == "USD" }.rateToEurScaled)
        db.close()
    }

    @Test
    fun `staleness is computed only from non-EUR rows, ignoring a fresh EUR timestamp`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, now.toEpochMilliseconds()),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val requestCount = intArrayOf(0)
        val repo = FxRepositoryImpl(db.fxRateDao(), frankfurterApi(requestCount), clock, seedingGate)

        repo.refreshIfStale()

        assertEquals(1, requestCount[0])
        db.close()
    }

    @Test
    fun `a successful fetch updates every non-EUR row's timestamp, leaving EUR untouched`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val repo = FxRepositoryImpl(db.fxRateDao(), frankfurterApi(intArrayOf(0)), clock, seedingGate)

        repo.refreshIfStale()

        val rows = db.fxRateDao().list().associateBy { it.currency }
        assertEquals(now.toEpochMilliseconds(), rows.getValue("USD").fetchedAtEpochMs)
        assertEquals(now.toEpochMilliseconds(), rows.getValue("GBP").fetchedAtEpochMs)
        assertEquals(now.toEpochMilliseconds(), rows.getValue("JPY").fetchedAtEpochMs)
        assertEquals(0L, rows.getValue("EUR").fetchedAtEpochMs)
        db.close()
    }

    @Test
    fun `an invalid rate in the response is rejected end-to-end, keeping cached rates`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val invalidBody =
            """{"amount":1.0,"base":"EUR","date":"2026-07-09","rates":{"USD":1.0870,"GBP":0.0,"JPY":163.9}}"""
        val repo = FxRepositoryImpl(
            db.fxRateDao(),
            frankfurterApi(intArrayOf(0), responseBody = invalidBody),
            clock,
            seedingGate,
        )

        repo.refreshIfStale()

        assertEquals(900_000L, db.fxRateDao().list().first { it.currency == "USD" }.rateToEurScaled)
        assertEquals(1_100_000L, db.fxRateDao().list().first { it.currency == "GBP" }.rateToEurScaled)
        db.close()
    }

    @Test
    fun `a response missing a requested symbol is rejected end-to-end, keeping cached rates`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val missingSymbolBody = """{"amount":1.0,"base":"EUR","date":"2026-07-09","rates":{"USD":1.0870,"GBP":1.17}}"""
        val repo = FxRepositoryImpl(
            db.fxRateDao(),
            frankfurterApi(intArrayOf(0), responseBody = missingSymbolBody),
            clock,
            seedingGate,
        )

        repo.refreshIfStale()

        assertEquals(6_000L, db.fxRateDao().list().first { it.currency == "JPY" }.rateToEurScaled)
        db.close()
    }

    @Test
    fun `observeRates reflects the newly persisted rate immediately after a successful refresh`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        seedingGate.await()
        val staleMs = now.minus(25.hours).toEpochMilliseconds()
        db.fxRateDao().upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 900_000, staleMs),
                FxRateEntity("GBP", 1_100_000, staleMs),
                FxRateEntity("JPY", 6_000, staleMs),
            ),
        )
        val repo = FxRepositoryImpl(db.fxRateDao(), frankfurterApi(intArrayOf(0)), clock, seedingGate)

        repo.refreshIfStale()
        val rates = repo.observeRates().first()

        assertEquals(919_963L, rates.rateToEurScaled(Currency.USD))
        db.close()
    }
}
