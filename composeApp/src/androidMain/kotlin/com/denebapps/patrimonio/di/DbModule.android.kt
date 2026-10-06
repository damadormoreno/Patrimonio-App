package com.denebapps.patrimonio.di

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.configureAppDatabase
import com.denebapps.patrimonio.data.db.createAppDatabaseBuilder
import com.denebapps.patrimonio.data.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

actual val dbModule: org.koin.core.module.Module = module {
    single<PlatformContext> { PlatformContext(androidContext()) }
    single<AppDatabase> {
        createAppDatabaseBuilder(get<PlatformContext>())
            .configureAppDatabase()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
    bindDatabaseDependents()
}
