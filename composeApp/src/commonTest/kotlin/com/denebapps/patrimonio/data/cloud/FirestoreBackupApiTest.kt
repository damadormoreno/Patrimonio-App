package com.denebapps.patrimonio.data.cloud

import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirestoreBackupApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FirestoreBackupApi {
        val engine = MockEngine { request ->
            requests += request
            this.handler(request)
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return FirestoreBackupApi(client, projectId = "test-project")
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private val documentUrl = "https://firestore.googleapis.com/v1/projects/test-project/databases/(default)" +
        "/documents/users/uid-1/backup/latest"

    @Test
    fun `upload patches the document with the revision, the date and the JSON in chunks`() = runTest {
        val api = api { respondJson("{}") }
        val json = "{\"name\":\"" + "á".repeat(1_000) + "\"}"

        api.upload("uid-1", "id-1", CloudBackupDocument("rev-1", SAVED_AT, json))

        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        val url = request.url.toString().replace("%28", "(").replace("%29", ")")
        assertEquals(documentUrl, url)
        assertEquals("Bearer id-1", request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        val fields = body.getValue("fields").jsonObject
        assertEquals("rev-1", fields.getValue("revision").jsonObject.getValue("stringValue").jsonPrimitive.content)
        assertEquals(
            "2026-10-08T09:00:00Z",
            fields.getValue("savedAt").jsonObject.getValue("timestampValue").jsonPrimitive.content,
        )
        val chunks = fields.getValue("data").jsonObject.getValue("arrayValue").jsonObject.getValue("values").jsonArray
            .map { it.jsonObject.getValue("stringValue").jsonPrimitive.content }
        assertEquals(2, chunks.size)
        assertEquals(json, chunks.joinToString(""))
    }

    @Test
    fun `fetch without data asks only for the metadata`() = runTest {
        val api = api {
            respondJson(
                """{"name":"x","fields":{"revision":{"stringValue":"rev-1"},""" +
                    """"savedAt":{"timestampValue":"2026-10-08T09:00:00.123456Z"}}}""",
            )
        }

        val document = api.fetch("uid-1", "id-1", withData = false)

        assertEquals(CloudBackupDocument("rev-1", Instant.parse("2026-10-08T09:00:00.123456Z"), null), document)
        assertEquals(listOf("revision", "savedAt"), requests.single().url.parameters.getAll("mask.fieldPaths"))
        assertEquals("Bearer id-1", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `fetch with data joins the chunks back`() = runTest {
        val api = api {
            respondJson(
                """{"fields":{"revision":{"stringValue":"rev-1"},""" +
                    """"savedAt":{"timestampValue":"2026-10-08T09:00:00Z"},""" +
                    """"data":{"arrayValue":{"values":[{"stringValue":"{\"a\":"},{"stringValue":"1}"}]}}}}""",
            )
        }

        val document = api.fetch("uid-1", "id-1", withData = true)

        assertEquals("{\"a\":1}", document?.json)
        assertTrue(requests.single().url.parameters.getAll("mask.fieldPaths").isNullOrEmpty())
    }

    @Test
    fun `a missing document is no copy`() = runTest {
        val api = api { respondJson("""{"error":{"code":404,"status":"NOT_FOUND"}}""", HttpStatusCode.NotFound) }

        assertNull(api.fetch("uid-1", "id-1", withData = true))
    }

    @Test
    fun `delete sends a DELETE with the token`() = runTest {
        val api = api { respondJson("{}") }

        api.delete("uid-1", "id-1")

        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("Bearer id-1", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `refusals and outages map to cloud errors`() = runTest {
        var status = HttpStatusCode.Forbidden
        val api = api { respondJson("""{"error":{"status":"PERMISSION_DENIED"}}""", status) }

        suspend fun errorOf(block: suspend () -> Unit) = assertFailsWith<CloudBackupException> { block() }.error

        assertEquals(CloudBackupError.NOT_AVAILABLE, errorOf { api.fetch("uid-1", "id-1", withData = false) })
        status = HttpStatusCode.Unauthorized
        assertEquals(CloudBackupError.SESSION_EXPIRED, errorOf { api.delete("uid-1", "id-1") })
        status = HttpStatusCode.ServiceUnavailable
        assertEquals(CloudBackupError.NETWORK, errorOf { api.delete("uid-1", "id-1") })
        status = HttpStatusCode.NotFound
        assertEquals(CloudBackupError.NOT_AVAILABLE, errorOf { api.delete("uid-1", "id-1") })
    }

    @Test
    fun `no connection is a network error and an unreadable document an unknown one`() = runTest {
        val offline = api { throw IllegalStateException("offline") }
        assertEquals(
            CloudBackupError.NETWORK,
            assertFailsWith<CloudBackupException> { offline.fetch("uid-1", "id-1", withData = false) }.error,
        )

        val garbled = api { respondJson("""{"fields":{"revision":{"stringValue":"r"}}}""") }
        assertEquals(
            CloudBackupError.UNKNOWN,
            assertFailsWith<CloudBackupException> { garbled.fetch("uid-1", "id-1", withData = false) }.error,
        )
    }

    @Test
    fun `a data set too big for one document is refused before uploading`() = runTest {
        val api = api { respondJson("{}") }

        val error = assertFailsWith<CloudBackupException> {
            api.upload("uid-1", "id-1", CloudBackupDocument("r", SAVED_AT, "x".repeat(950_000)))
        }

        assertEquals(CloudBackupError.TOO_LARGE, error.error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `chunks respect the byte limit and never split an emoji`() {
        val text = "a".repeat(5) + "😀" + "é".repeat(3)

        val chunks = utf8Chunks(text, maxBytes = 6)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.encodeToByteArray().size <= 6 })
        assertEquals(listOf("aaaaa", "😀é", "éé"), chunks)
        assertEquals(emptyList(), utf8Chunks(""))
    }

    private companion object {
        val SAVED_AT: Instant = Instant.parse("2026-10-08T09:00:00Z")
    }
}
