package com.denebapps.patrimonio.data.profile

import com.denebapps.patrimonio.domain.repository.ProfilePhoto
import com.denebapps.patrimonio.domain.repository.ProfilePhotoRepository
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.compressImage
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.write
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The bytes of one file; [FileKitPhotoFile] in the app, in memory in tests. */
interface PhotoFile {
    val path: String

    fun exists(): Boolean

    suspend fun write(bytes: ByteArray)

    suspend fun delete()
}

class FileKitPhotoFile(override val path: String) : PhotoFile {
    private val file = PlatformFile(path)

    override fun exists(): Boolean = file.exists()

    override suspend fun write(bytes: ByteArray) {
        file.parent()?.createDirectories()
        file.write(bytes)
    }

    override suspend fun delete() {
        file.delete(mustExist = false)
    }
}

/** [ProfilePhotoRepository] in one JPEG file. Images are shrunk to [MAX_SIDE] px before they are stored. */
class ProfilePhotoStore(
    private val file: PhotoFile,
    private val httpClient: HttpClient,
    private val shrink: suspend (ByteArray) -> ByteArray = { image ->
        FileKit.compressImage(
            image,
            ImageFormat.JPEG,
            quality = JPEG_QUALITY,
            maxWidth = MAX_SIDE,
            maxHeight = MAX_SIDE,
        )
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
