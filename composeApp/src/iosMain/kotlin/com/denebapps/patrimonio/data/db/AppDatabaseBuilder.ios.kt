package com.denebapps.patrimonio.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.data.platform.appSupportSubdirectory

private const val DATABASE_NAME = "patrimonio.db"

@Suppress("UNUSED_PARAMETER")
actual fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(name = "${appSupportSubdirectory("databases")}/$DATABASE_NAME")
