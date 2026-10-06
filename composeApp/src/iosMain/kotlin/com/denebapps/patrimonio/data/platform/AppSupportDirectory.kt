package com.denebapps.patrimonio.data.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Returns `<Application Support>/<name>`, creating it if needed. Application Support is Apple's
 * recommended location for app-generated data and is included in iCloud device backups — unlike
 * the container root (`NSHomeDirectory()`), which is not writable on a physical device.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun appSupportSubdirectory(name: String): String {
    val appSupportDir = NSSearchPathForDirectoriesInDomains(
        directory = NSApplicationSupportDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String
        ?: error("Unable to resolve NSApplicationSupportDirectory")

    val dir = "$appSupportDir/$name"
    memScoped {
        val directoryError = alloc<ObjCObjectVar<NSError?>>()
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = directoryError.ptr,
        )
        if (!created && !NSFileManager.defaultManager.fileExistsAtPath(dir)) {
            val detail = directoryError.value?.localizedDescription ?: "Foundation returned no error detail"
            error("Unable to create directory at $dir: $detail")
        }
    }
    return dir
}
