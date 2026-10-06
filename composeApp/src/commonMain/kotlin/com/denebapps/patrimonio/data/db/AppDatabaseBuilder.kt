package com.denebapps.patrimonio.data.db

import androidx.room.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext

/** Android: `getDatabasePath` (app-private storage). iOS: Application Support `databases/`,
 *  ensuring the parent directory exists before building (not created automatically). */
expect fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase>

/** Single place to register production migrations, applied identically on Android and iOS.
 *  Nothing to add for 1 -> 2 or 2 -> 3: both are declared as `AutoMigration`s on [AppDatabase], which
 *  Room applies by itself (see `AppDatabaseMigrationTest`). Register hand-written migrations here. */
fun RoomDatabase.Builder<AppDatabase>.configureAppDatabase(): RoomDatabase.Builder<AppDatabase> = this
