package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.repository.ProfilePhoto
import com.denebapps.patrimonio.domain.repository.ProfilePhotoRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ProfilePhotoRepository]: records what was set; [failure] makes [setImage] throw. */
class FakeProfilePhotoRepository(hasPhoto: Boolean = false) : ProfilePhotoRepository {
    override val photo = MutableStateFlow(if (hasPhoto) ProfilePhoto("photo.jpg", 0) else null)
    val images = mutableListOf<ByteArray>()
    val googleUrls = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun setImage(image: ByteArray) {
        failure?.let { throw it }
        images += image
        photo.value = ProfilePhoto("photo.jpg", (photo.value?.version ?: 0) + 1)
    }

    override suspend fun setFromGoogleIfEmpty(photoUrl: String) {
        if (photo.value != null) return
        googleUrls += photoUrl
        photo.value = ProfilePhoto("photo.jpg", 1)
    }

    override suspend fun clear() {
        photo.value = null
    }
}
