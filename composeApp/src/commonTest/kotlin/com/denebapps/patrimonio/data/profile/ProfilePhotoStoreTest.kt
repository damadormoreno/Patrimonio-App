package com.denebapps.patrimonio.data.profile

import com.denebapps.patrimonio.domain.repository.ProfilePhoto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class InMemoryPhotoFile(var bytes: ByteArray? = null) : PhotoFile {
    override val path = "profile-photo.jpg"

    override fun exists() = bytes != null

    override suspend fun write(bytes: ByteArray) {
        this.bytes = bytes
    }

    override suspend fun delete() {
        bytes = null
    }
}

class ProfilePhotoStoreTest {
    private val requested = mutableListOf<String>()
    private var status = HttpStatusCode.OK

    private fun store(file: PhotoFile) = ProfilePhotoStore(
        file = file,
        httpClient = HttpClient(
            MockEngine { request ->
                requested += request.url.toString()
                respond(byteArrayOf(7, 7), status)
            },
        ),
        shrink = { it + 0.toByte() },
    )

    @Test
    fun `a picked image is shrunk and stored, and each change is a new version`() = runTest {
        val file = InMemoryPhotoFile()
        val store = store(file)
        assertNull(store.photo.value)

        store.setImage(byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2, 0), file.bytes)
        assertEquals(ProfilePhoto("profile-photo.jpg", 1), store.photo.value)

        store.setImage(byteArrayOf(3))
        assertEquals(2, store.photo.value?.version)

        store.clear()
        assertNull(store.photo.value)
        assertNull(file.bytes)
    }

    @Test
    fun `the Google photo is downloaded big, only while there is no photo`() = runTest {
        val file = InMemoryPhotoFile()
        val store = store(file)

        store.setFromGoogleIfEmpty("https://lh3.googleusercontent.com/a/abc=s96-c")
        store.setFromGoogleIfEmpty("https://lh3.googleusercontent.com/a/other=s96-c")

        assertEquals(listOf("https://lh3.googleusercontent.com/a/abc=s512-c"), requested)
        assertContentEquals(byteArrayOf(7, 7, 0), file.bytes)
    }

    @Test
    fun `a photo already on disk is kept and a failed download stores nothing`() = runTest {
        assertEquals(ProfilePhoto("profile-photo.jpg", 0), store(InMemoryPhotoFile(byteArrayOf(1))).photo.value)

        status = HttpStatusCode.NotFound
        val file = InMemoryPhotoFile()
        val store = store(file)
        store.setFromGoogleIfEmpty("https://photo")
        assertNull(store.photo.value)
        assertNull(file.bytes)
    }

    @Test
    fun `Google photo URLs ask for 512 px`() {
        assertEquals("https://x/a=s512-c", largeGooglePhotoUrl("https://x/a=s96-c"))
        assertEquals("https://x/a=s512-c", largeGooglePhotoUrl("https://x/a=s100"))
        assertEquals("https://x/a", largeGooglePhotoUrl("https://x/a"))
    }
}
