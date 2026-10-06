package com.denebapps.patrimonio.data.db

import androidx.room.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext

/** Android: `getDatabasePath` (app-private storage). iOS: Application Support `databases/`,
 *  ensuring the parent directory exists before building (not created automatically). */
expect fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase>

/** Single place to register production migrations, applied identically on Android and iOS.
 *  Schema is still at v1, so there is nothing to add yet. */
fun RoomDatabase.Builder<AppDatabase>.configureAppDatabase(): RoomDatabase.Builder<AppDatabase> = this
