package com.denebapps.patrimonio.data.db

import androidx.room3.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext

/** Android: `getDatabasePath` (app-private storage). iOS: Application Support `databases/`,
 *  ensuring the parent directory exists before building (not created automatically). Web: the Origin Private
 *  File System, through the SQLite web worker. */
expect fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase>
