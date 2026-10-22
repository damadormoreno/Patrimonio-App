package com.denebapps.patrimonio.data.profile

import com.denebapps.patrimonio.domain.repository.ProfilePhoto
import com.denebapps.patrimonio.domain.repository.ProfilePhotoRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The bytes of one file: a file on the phone ([profilePhotoFile]), in memory in tests. */
interface PhotoFile {
    val path: String

    fun exists(): Boolean

    suspend fun write(bytes: ByteArray)

    suspend fun delete()
}

/** Where the profile photo is kept on this platform. The web keeps none: the photo is per device and is not in the
 *  backup, so a browser has nowhere to keep it beyond the tab. */
expect fun profilePhotoFile(path: String): PhotoFile

/** [image] as a JPEG of at most [maxSide] px a side. */
expect suspend fun shrinkProfileImage(image: ByteArray, maxSide: Int, quality: Int): ByteArray

/** [ProfilePhotoRepository] in one JPEG file. Images are shrunk to [MAX_SIDE] px before they are stored. */
class ProfilePhotoStore(
    private val file: PhotoFile,
    private val httpClient: HttpClient,
    private val shrink: suspend (ByteArray) -> ByteArray = { image ->
        shrinkProfileImage(image, maxSide = MAX_SIDE, quality = JPEG_QUALITY)
    },
) : ProfilePhotoRepository {
    private val mutex = Mutex()
    private val _photo = MutableStateFlow(if (file.exists()) ProfilePhoto(file.path, 0) else null)
    override val photo: StateFlow<ProfilePhoto?> = _photo.asStateFlow()

    override suspend fun setImage(image: ByteArray) = mutex.withLock { store(image) }

    override suspend fun setFromGoogleIfEmpty(photoUrl: String) = mutex.withLock {
        if (_photo.value != null) return@withLock
        val response = httpClient.get(largeGooglePhotoUrl(photoUrl))
        if (response.status.isSuccess()) store(response.readRawBytes())
    }

    override suspend fun clear() = mutex.withLock {
        file.delete()
        _photo.value = null
    }

    private suspend fun store(image: ByteArray) {
        file.write(shrink(image))
        _photo.value = ProfilePhoto(file.path, (_photo.value?.version ?: 0) + 1)
    }

    private companion object {
        const val MAX_SIDE = 512
        const val JPEG_QUALITY = 85
    }
}

/** Google photo URLs end in a size (`=s96-c`); ask for one big enough for [ProfilePhotoStore]. */
internal fun largeGooglePhotoUrl(url: String): String = url.replace(Regex("=s\\d+(-c)?$"), "=s512-c")
