package com.denebapps.patrimonio.data.datastore

import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.data.platform.appSupportSubdirectory

@Suppress("UNUSED_PARAMETER")
actual fun dataStoreFilePath(context: PlatformContext): String =
    "${appSupportSubdirectory("datastore")}/patrimonio.preferences_pb"
