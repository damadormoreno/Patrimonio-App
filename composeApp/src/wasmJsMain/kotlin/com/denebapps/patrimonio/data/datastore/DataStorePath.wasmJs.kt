package com.denebapps.patrimonio.data.datastore

import com.denebapps.patrimonio.data.platform.PlatformContext

/** On the web DataStore keys the browser storage by the file's name; there is no file system. */
actual fun dataStoreFilePath(context: PlatformContext, fileName: String): String = fileName
