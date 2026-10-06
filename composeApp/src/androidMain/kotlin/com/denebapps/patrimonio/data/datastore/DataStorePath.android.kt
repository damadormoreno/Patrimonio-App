package com.denebapps.patrimonio.data.datastore

import android.content.Context
import com.denebapps.patrimonio.data.platform.PlatformContext

actual fun dataStoreFilePath(context: PlatformContext): String =
    (context.value as Context).filesDir.resolve("patrimonio.preferences_pb").absolutePath
