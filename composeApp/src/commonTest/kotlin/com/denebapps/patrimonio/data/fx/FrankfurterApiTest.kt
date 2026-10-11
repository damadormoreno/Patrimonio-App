package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.fx.dto.FrankfurterResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrankfurterApiTest {
    @Test
    fun `latest requests the confirmed endpoint with base and symbols params and parses the response`() = runTest {
        var requestedUrl: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = """{"amount":1.0,"base":"EUR","date":"2026-07-09",""" +
                    """"rates":{"USD":1.0870,"GBP":1.17,"JPY":163.9}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val api = FrankfurterApi(client)

        val response = api.latest(base = "EUR", symbols = listOf("USD", "GBP", "JPY"))

        assertEquals(
            FrankfurterResponse("EUR", "2026-07-09", mapOf("USD" to 1.0870, "GBP" to 1.17, "JPY" to 163.9)),
            response,
        )
        assertTrue(requestedUrl.orEmpty().startsWith(FrankfurterApi.ENDPOINT))
        assertTrue(requestedUrl.orEmpty().contains("base=EUR"))
        assertTrue(requestedUrl.orEmpty().contains("USD") && requestedUrl.orEmpty().contains("GBP"))
    }

    @Test
    fun `timeSeries asks for the date range of the v1 endpoint and parses the dated rates`() = runTest {
        var requested: io.ktor.http.Url? = null
        val engine = MockEngine { request ->
            requested = request.url
            respond(
                content = """{"amount":1.0,"base":"EUR","start_date":"2024-12-20","end_date":"2025-01-10",""" +
                    """"rates":{"2024-12-20":{"GBP":0.82965,"JPY":162.89,"USD":1.039},""" +
                    """"2024-12-23":{"GBP":0.82995,"JPY":163.36,"USD":1.0393}}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val response = FrankfurterApi(client).timeSeries(
            base = "EUR",
            symbols = listOf("USD", "GBP", "JPY"),
            from = LocalDate(2024, 12, 20),
            to = LocalDate(2025, 1, 10),
        )

        assertEquals("/v1/2024-12-20..2025-01-10", requested?.encodedPath)
        assertEquals("EUR", requested?.parameters?.get("base"))
        assertEquals("USD,GBP,JPY", requested?.parameters?.get("symbols"))
        assertEquals("EUR", response.base)
        assertEquals(
            mapOf(
                "2024-12-20" to mapOf("GBP" to 0.82965, "JPY" to 162.89, "USD" to 1.039),
                "2024-12-23" to mapOf("GBP" to 0.82995, "JPY" to 163.36, "USD" to 1.0393),
            ),
            response.rates,
        )
    }
}
