package com.denebapps.patrimonio.data.cloud

import com.denebapps.patrimonio.data.auth.FirebaseConfig
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The cloud copy. [revision] identifies the upload that wrote it; [records] counts its accounts, debts, groups,
 * goals and subscriptions (null in copies written before it existed). [key] is the wrapped data key that
 * encrypted [data] (null in copies from before encryption, whose [data] is the plain backup JSON). [data] is
 * null when only the metadata was read.
 */
data class CloudBackupDocument(
    val revision: String,
    val savedAt: Instant,
    val records: Int?,
    val key: WrappedDataKey?,
    val data: String?,
)

/** One backup document per account in the cloud. Failures are [CloudBackupException]s. */
interface CloudBackupRemote {
    /** Null when the account has no copy yet. With [withData] false only the metadata is downloaded. */
    suspend fun fetch(uid: String, idToken: String, withData: Boolean): CloudBackupDocument?

    /** Replaces the copy; [CloudBackupDocument.data] must be set. */
    suspend fun upload(uid: String, idToken: String, document: CloudBackupDocument)

    /** Deletes the copy; deleting a copy that does not exist succeeds. */
    suspend fun delete(uid: String, idToken: String)
}

/**
 * Cloud Firestore over its REST API: the copy lives at `users/{uid}/backup/latest`, which the security rules
 * open only to that user (docs/cloud-account.md). The data is stored as an array of strings of at most
 * [MAX_CHUNK_BYTES], because Firestore indexes every field and limits indexed strings to 1,500 bytes; the
 * wrapped key sits next to it, so a copy and the key that opens it are always written together.
 */
class FirestoreBackupApi(
    private val httpClient: HttpClient,
    private val projectId: String = FirebaseConfig.PROJECT_ID,
) : CloudBackupRemote {
    override suspend fun fetch(uid: String, idToken: String, withData: Boolean): CloudBackupDocument? {
        val response = send {
            httpClient.get(documentUrl(uid)) {
                bearerAuth(idToken)
                if (!withData) METADATA_FIELDS.forEach { parameter(MASK, it) }
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        return parseDocument(successBody(response), withData)
    }

    override suspend fun upload(uid: String, idToken: String, document: CloudBackupDocument) {
        val data = requireNotNull(document.data) { "Uploading a backup needs its data" }
        if (data.encodeToByteArray().size > MAX_DATA_BYTES) throw CloudBackupException(CloudBackupError.TOO_LARGE)
        val body = buildJsonObject {
            put(
                "fields",
                buildJsonObject {
                    put(REVISION, stringValue(document.revision))
                    put(SAVED_AT, buildJsonObject { put("timestampValue", document.savedAt.toString()) })
                    document.records?.let { records ->
                        put(RECORDS, buildJsonObject { put("integerValue", records.toString()) })
                    }
                    document.key?.let { key ->
                        put(KEY_ID, stringValue(key.keyId))
                        put(KDF_SALT, stringValue(key.salt))
                        put(KDF_ITERATIONS, buildJsonObject { put("integerValue", key.iterations.toString()) })
                        put(WRAPPED_KEY, stringValue(key.wrapped))
                    }
                    val chunks = JsonArray(utf8Chunks(data).map(::stringValue))
                    put(DATA, buildJsonObject { put("arrayValue", buildJsonObject { put("values", chunks) }) })
                },
            )
        }
        successBody(
            send {
                httpClient.patch(documentUrl(uid)) {
                    bearerAuth(idToken)
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            },
        )
    }

    override suspend fun delete(uid: String, idToken: String) {
        successBody(send { httpClient.delete(documentUrl(uid)) { bearerAuth(idToken) } })
    }

    private fun documentUrl(uid: String) =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/users/$uid/backup/latest"

    private suspend fun send(request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CloudBackupException(CloudBackupError.NETWORK, e)
    }

    private suspend fun successBody(response: HttpResponse): String {
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CloudBackupException(CloudBackupError.NETWORK, e)
        }
        if (response.status.isSuccess()) return text
        throw CloudBackupException(cloudErrorFor(response.status))
    }

    private fun parseDocument(text: String, withData: Boolean): CloudBackupDocument = try {
        val fields = Json.parseToJsonElement(text).jsonObject.getValue("fields").jsonObject
        CloudBackupDocument(
            revision = fields.value(REVISION, "stringValue"),
            savedAt = Instant.parse(fields.value(SAVED_AT, "timestampValue")),
            records = fields[RECORDS]?.let { fields.value(RECORDS, "integerValue").toInt() },
            key = fields[KEY_ID]?.let {
                WrappedDataKey(
                    keyId = fields.value(KEY_ID, "stringValue"),
                    salt = fields.value(KDF_SALT, "stringValue"),
                    iterations = fields.value(KDF_ITERATIONS, "integerValue").toInt(),
                    wrapped = fields.value(WRAPPED_KEY, "stringValue"),
                )
            },
            data = if (withData) {
                fields.getValue(DATA).jsonObject.getValue("arrayValue").jsonObject["values"]
                    ?.jsonArray
                    .orEmpty()
                    .joinToString("") { it.jsonObject.getValue("stringValue").jsonPrimitive.content }
            } else {
                null
            },
        )
    } catch (e: IllegalArgumentException) {
        // A body that is not JSON, a field of the wrong type or an unparsable timestamp.
        throw CloudBackupException(CloudBackupError.UNKNOWN, e)
    } catch (e: NoSuchElementException) {
        throw CloudBackupException(CloudBackupError.UNKNOWN, e)
    }

    private fun JsonObject.value(field: String, type: String): String =
        getValue(field).jsonObject.getValue(type).jsonPrimitive.content

    private fun stringValue(value: String) = buildJsonObject { put("stringValue", JsonPrimitive(value)) }

    private companion object {
        const val MASK = "mask.fieldPaths"
        const val REVISION = "revision"
        const val SAVED_AT = "savedAt"
        const val RECORDS = "records"
        const val DATA = "data"
        const val KEY_ID = "keyId"
        const val KDF_SALT = "kdfSalt"
        const val KDF_ITERATIONS = "kdfIterations"
        const val WRAPPED_KEY = "wrappedKey"
        val METADATA_FIELDS = listOf(REVISION, SAVED_AT, RECORDS, KEY_ID, KDF_SALT, KDF_ITERATIONS, WRAPPED_KEY)

        /** Firestore documents hold up to 1 MiB; the rest is headroom for the field names and array overhead. */
        const val MAX_DATA_BYTES = 900_000
    }
}

internal fun cloudErrorFor(status: HttpStatusCode): CloudBackupError = when (status.value) {
    401 -> CloudBackupError.SESSION_EXPIRED
    // 403: the security rules deny it; 404 on a write or delete: the database does not exist.
    403, 404 -> CloudBackupError.NOT_AVAILABLE
    408, 429, in 500..599 -> CloudBackupError.NETWORK
    else -> CloudBackupError.UNKNOWN
}

/** Each chunk's UTF-8 encoding fits in Firestore's indexed-string limit (1,500 bytes) with room to spare. */
internal const val MAX_CHUNK_BYTES = 1_400

/** Splits [text] into pieces whose UTF-8 encoding is at most [maxBytes], never inside a surrogate pair. */
internal fun utf8Chunks(text: String, maxBytes: Int = MAX_CHUNK_BYTES): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val width = if (pair) 2 else 1
        val size = when {
            pair -> 4
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
        if (bytes + size > maxBytes) {
            chunks += text.substring(start, i)
            start = i
            bytes = 0
        }
        bytes += size
        i += width
    }
    if (start < text.length) chunks += text.substring(start)
    return chunks
}
