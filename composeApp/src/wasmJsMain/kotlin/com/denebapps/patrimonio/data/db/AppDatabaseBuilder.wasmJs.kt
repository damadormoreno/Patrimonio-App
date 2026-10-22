package com.denebapps.patrimonio.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.denebapps.patrimonio.data.platform.PlatformContext

private const val DATABASE_NAME = "patrimonio.db"

actual fun createAppDatabaseBuilder(context: PlatformContext): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(name = DATABASE_NAME)
