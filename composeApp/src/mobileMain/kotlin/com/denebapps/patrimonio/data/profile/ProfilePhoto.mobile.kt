package com.denebapps.patrimonio.data.profile

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.compressImage
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.write

actual fun profilePhotoFile(path: String): PhotoFile = FileKitPhotoFile(path)

actual suspend fun shrinkProfileImage(image: ByteArray, maxSide: Int, quality: Int): ByteArray =
    FileKit.compressImage(image, ImageFormat.JPEG, quality = quality, maxWidth = maxSide, maxHeight = maxSide)

private class FileKitPhotoFile(override val path: String) : PhotoFile {
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
