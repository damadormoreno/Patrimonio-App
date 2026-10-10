package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.DatabaseInitializer
import com.denebapps.patrimonio.data.db.SeedingGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module

/** Provides [AppDatabase], DAOs, [DatabaseInitializer], and the [SeedingGate]. Platform-specific
 *  because opening the database file needs a [com.denebapps.patrimonio.data.platform.PlatformContext]
 *  (Android: `getDatabasePath`; iOS: Application Support `databases/`). */
expect val dbModule: Module

/** Shared DAO + [DatabaseInitializer] + suspend-seeding-gate bindings — called by each platform's
 *  `dbModule` after it binds the platform-specific [AppDatabase] singleton, so no repository can
 *  observe an unseeded DB. */
internal fun Module.bindDatabaseDependents() {
    single { get<AppDatabase>().assetDao() }
    single { get<AppDatabase>().liabilityDao() }
    single { get<AppDatabase>().accountGroupDao() }
    single { get<AppDatabase>().netWorthDao() }
    single { get<AppDatabase>().fxRateDao() }
    single { get<AppDatabase>().savingsGoalDao() }
    single { DatabaseInitializer(get(), get()) }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SeedingGate(get(), get()) }
}
