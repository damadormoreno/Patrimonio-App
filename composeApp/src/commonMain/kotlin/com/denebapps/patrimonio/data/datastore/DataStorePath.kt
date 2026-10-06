package com.denebapps.patrimonio.data.datastore

import com.denebapps.patrimonio.data.platform.PlatformContext

/** Android: `context.filesDir`. iOS: Application Support `datastore/` (okio Path resolved by the DataStore
 *  factory from this plain [String]). */
expect fun dataStoreFilePath(context: PlatformContext): String
