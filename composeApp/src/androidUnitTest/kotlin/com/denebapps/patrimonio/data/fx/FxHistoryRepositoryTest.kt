package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val HOUR_MS = 3_600_000L

private class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant

    fun advanceMs(millis: Long) {
        instant = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + millis)
    }
}

/** One request seen by the fake Frankfurter: the path range and the `symbols` parameter. */
private data class SeriesRequest(val start: LocalDate, val end: LocalDate, val symbols: String)

@RunWith(RobolectricTestRunner::class)
class FxHistoryRepositoryTest {
    private lateinit var db: AppDatabase
    private val clock = MutableClock(Instant.parse("2026-10-14T12:00:00Z"))
    private val requests = mutableListOf<SeriesRequest>()
    private var failWith: HttpStatusCode? = null
    private var badDate = false

    @Before
    fun setUp() {
        db = buildInMemoryTestDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** USD is 2.0 per EUR, GBP 1.25 and JPY 100, on every weekday of the requested range. */
    private fun api(): FrankfurterApi {
        val engine = MockEngine { request ->
            val range = request.url.encodedPath.substringAfterLast('/')
            val start = LocalDate.parse(range.substringBefore(".."))
            val end = LocalDate.parse(range.substringAfter(".."))
            val symbols = request.url.parameters["symbols"].orEmpty()
            requests += SeriesRequest(start, end, symbols)
            failWith?.let { return@MockEngine respond("{}", it) }
            val perDay = mapOf("USD" to 2.0, "GBP" to 1.25, "JPY" to 100.0).filterKeys { it in symbols.split(',') }
            val days = generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= end }
                .filter { it.dayOfWeek.isoDayNumber <= 5 }
            val rates = days.joinToString(",") { date ->
                val key = if (badDate) "bad-date" else date.toString()
                "\"$key\":{" + perDay.entries.joinToString(",") { (code, rate) -> "\"$code\":$rate" } + "}"
            }
            respond(
                content = """{"base":"EUR","start_date":"$start","end_date":"$end","rates":{$rates}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return FrankfurterApi(client)
    }

    private fun repository() = FxHistoryRepositoryImpl(db.fxHistoryDao(), api(), clock)

    private val start = LocalDate(2026, 10, 1) // a Thursday

    @Test
    fun `a first sync downloads each currency from the start date to today and stores EUR based rates`() = runTest {
        val repository = repository()

        repository.sync(setOf(Currency.USD, Currency.GBP), from = start, force = false)

        assertEquals(
            setOf(
                SeriesRequest(start, LocalDate(2026, 10, 14), "USD"),
                SeriesRequest(start, LocalDate(2026, 10, 14), "GBP"),
            ),
            requests.toSet(),
        )
        val history = repository.observe().first()
        // 2.0 USD per EUR is 0.5 EUR per USD; 1.25 GBP per EUR is 0.8.
        assertEquals(500_000L, history.rateOn(Currency.USD, LocalDate(2026, 10, 1)))
        assertEquals(800_000L, history.rateOn(Currency.GBP, LocalDate(2026, 10, 14)))
        assertNull(history.rateOn(Currency.JPY, LocalDate(2026, 10, 14)))
    }

    @Test
    fun `EUR needs no download`() = runTest {
        val repository = repository()

        repository.sync(setOf(Currency.EUR), from = start, force = false)

        assertEquals(emptyList(), requests)
        assertEquals(RATE_SCALE, repository.observe().first().rateOn(Currency.EUR, start))
    }

    @Test
    fun `a sync inside six hours does nothing unless forced and a later one is incremental`() = runTest {
        val repository = repository()
        repository.sync(setOf(Currency.USD), from = start, force = false)

        clock.advanceMs(6 * HOUR_MS - 1)
        repository.sync(setOf(Currency.USD), from = start, force = false)
        assertEquals(1, requests.size)

        repository.sync(setOf(Currency.USD), from = start, force = true)
        // The last stored weekday is Wednesday the 14th: the window restarts a week before it.
        assertEquals(SeriesRequest(LocalDate(2026, 10, 7), LocalDate(2026, 10, 14), "USD"), requests.last())

        clock.advanceMs(24 * HOUR_MS)
        repository.sync(setOf(Currency.USD), from = start, force = false)
        assertEquals(SeriesRequest(LocalDate(2026, 10, 7), LocalDate(2026, 10, 15), "USD"), requests.last())
        assertEquals(3, requests.size)
    }

    @Test
    fun `asking for an older start than the stored one downloads the missing years`() = runTest {
        val repository = repository()
        repository.sync(setOf(Currency.USD), from = start, force = false)
        val older = LocalDate(2026, 9, 1)

        repository.sync(setOf(Currency.USD), from = older, force = false)

        assertEquals(SeriesRequest(older, LocalDate(2026, 10, 14), "USD"), requests.last())
        val history = repository.observe().first()
        assertEquals(500_000L, history.rateOn(Currency.USD, LocalDate(2026, 9, 1)))
        assertEquals(500_000L, history.rateOn(Currency.USD, LocalDate(2026, 10, 14)))

        // The wider coverage is remembered: the same request is now fresh.
        repository.sync(setOf(Currency.USD), from = older, force = false)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a currency added later is downloaded alone`() = runTest {
        val repository = repository()
        repository.sync(setOf(Currency.USD), from = start, force = false)

        repository.sync(setOf(Currency.USD, Currency.JPY), from = start, force = false)

        assertEquals(listOf("USD", "JPY"), requests.map { it.symbols })
        assertEquals(10_000L, repository.observe().first().rateOn(Currency.JPY, LocalDate(2026, 10, 2)))
    }

    @Test
    fun `lookups fall back to the previous weekday up to a week and not beyond`() = runTest {
        val repository = repository()
        repository.sync(setOf(Currency.USD), from = start, force = false)
        val history = repository.observe().first()

        // Saturday 3rd and Sunday 4th take Friday's rate.
        assertEquals(500_000L, history.rateOn(Currency.USD, LocalDate(2026, 10, 3)))
        assertEquals(500_000L, history.rateOn(Currency.USD, LocalDate(2026, 10, 4)))
        // The series ends on Wednesday the 14th: a week later is still covered, eight days is not.
        assertEquals(500_000L, history.rateOn(Currency.USD, LocalDate(2026, 10, 21)))
        assertNull(history.rateOn(Currency.USD, LocalDate(2026, 10, 22)))
        assertNull(history.rateOn(Currency.USD, LocalDate(2026, 9, 30)))
    }

    @Test
    fun `a failed download keeps the stored rates is reported and retried right away`() = runTest {
        val repository = repository()
        repository.sync(setOf(Currency.USD), from = start, force = false)
        val before = repository.observe().first()
        clock.advanceMs(7 * HOUR_MS)
        failWith = HttpStatusCode.InternalServerError

        assertFailsWith<HistoryUnavailableException> {
            repository.sync(setOf(Currency.USD), from = start, force = false)
        }
        assertEquals(before, repository.observe().first())

        failWith = null
        repository.sync(setOf(Currency.USD), from = start, force = false)
        assertEquals(3, requests.size)
    }

    @Test
    fun `one currency failing does not stop the others from being stored`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.parameters["symbols"] == "USD") {
                respond("{}", HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = """{"base":"EUR","start_date":"2026-10-01","end_date":"2026-10-14",""" +
                        """"rates":{"2026-10-02":{"GBP":1.25}}}""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val repository = FxHistoryRepositoryImpl(db.fxHistoryDao(), FrankfurterApi(client), clock)

        assertFailsWith<HistoryUnavailableException> {
            repository.sync(setOf(Currency.USD, Currency.GBP), from = start, force = false)
        }

        val history = repository.observe().first()
        assertEquals(800_000L, history.rateOn(Currency.GBP, LocalDate(2026, 10, 2)))
        assertNull(history.rateOn(Currency.USD, LocalDate(2026, 10, 2)))
    }

    @Test
    fun `a malformed answer is a failure that stores nothing`() = runTest {
        badDate = true
        val repository = repository()

        assertFailsWith<HistoryUnavailableException> {
            repository.sync(setOf(Currency.USD), from = start, force = false)
        }

        assertEquals(0, db.fxHistoryDao().list().size)
        assertNull(db.fxHistoryDao().findSync(Currency.USD.code))
    }
}
