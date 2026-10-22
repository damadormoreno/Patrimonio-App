package com.denebapps.patrimonio.data.profile

actual fun profilePhotoFile(path: String): PhotoFile = NoPhotoFile(path)

actual suspend fun shrinkProfileImage(image: ByteArray, maxSide: Int, quality: Int): ByteArray = image

/** Keeps nothing: the profile photo stays on the phone. */
private class NoPhotoFile(override val path: String) : PhotoFile {
    override fun exists(): Boolean = false

    override suspend fun write(bytes: ByteArray) = Unit

    override suspend fun delete() = Unit
}
