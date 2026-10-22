package com.denebapps.patrimonio.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext

internal const val PRODUCTION_DATABASE_NAME = "patrimonio.db"

actual fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase> =
    createAppDatabaseBuilder(context, PRODUCTION_DATABASE_NAME)

internal fun createAppDatabaseBuilder(
    context: PlatformContext,
    databaseName: String,
): RoomDatabase.Builder<AppDatabase> {
    val androidContext = context.value as Context
    val dbFile = androidContext.getDatabasePath(databaseName)
    return Room.databaseBuilder<AppDatabase>(context = androidContext, name = dbFile.absolutePath)
}
