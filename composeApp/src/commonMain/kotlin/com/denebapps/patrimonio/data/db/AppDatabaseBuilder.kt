package com.denebapps.patrimonio.data.db

import androidx.room.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext

/** Android: `getDatabasePath` (app-private storage). iOS: Application Support `databases/`,
 *  ensuring the parent directory exists before building (not created automatically). */
expect fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase>

/** Single place to register production migrations, applied identically on Android and iOS.
 *  1 -> 2 and 2 -> 3 are `AutoMigration`s declared on [AppDatabase], which Room applies by itself;
 *  hand-written ones go here (see `AppDatabaseMigrationTest`). */
fun RoomDatabase.Builder<AppDatabase>.configureAppDatabase(): RoomDatabase.Builder<AppDatabase> =
    addMigrations(MIGRATION_3_4)
