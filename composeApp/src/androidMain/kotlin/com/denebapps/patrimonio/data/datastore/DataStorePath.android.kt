package com.denebapps.patrimonio.data.datastore

import android.content.Context
import com.denebapps.patrimonio.data.platform.PlatformContext

actual fun dataStoreFilePath(context: PlatformContext, fileName: String): String =
    (context.value as Context).filesDir.resolve(fileName).absolutePath
