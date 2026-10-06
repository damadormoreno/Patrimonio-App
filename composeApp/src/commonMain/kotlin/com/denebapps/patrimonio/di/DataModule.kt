package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.datastore.PreferencesRepositoryImpl
import com.denebapps.patrimonio.data.datastore.dataStoreFilePath
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDao
import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.data.repository.AccountGroupRepositoryImpl
import com.denebapps.patrimonio.data.repository.AssetRepositoryImpl
import com.denebapps.patrimonio.data.repository.DataMaintenanceRepositoryImpl
import com.denebapps.patrimonio.data.repository.LiabilityRepositoryImpl
import com.denebapps.patrimonio.data.repository.NetWorthRepositoryImpl
import com.denebapps.patrimonio.data.repository.NetWorthSnapshotUpserter
import com.denebapps.patrimonio.data.repository.SavingsGoalRepositoryImpl
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.koin.dsl.module

/**
 * Platform-independent: all `*RepositoryImpl` bindings, [NetWorthSnapshotUpserter], and the
 * `() -> TimeZone` provider (calls [TimeZone.currentSystemDefault] per invocation, never a pinned
 * singleton, so a device timezone change is reflected on the next call — calc functions stay pure
 * and keep taking `TimeZone` as a parameter). Depends on [dbModule] for `AppDatabase`/DAOs and on
 * a [PlatformContext] binding (also provided by `dbModule`) for the DataStore file path.
 */
val dataModule = module {
    single<Clock> { Clock.System }
    single<() -> TimeZone> { { TimeZone.currentSystemDefault() } }

    single { NetWorthSnapshotUpserter(get(), get(), get(), get(), get(), get()) }

    single<AssetRepository> {
        AssetRepositoryImpl(
            appDatabase = get(),
            assetDao = get(),
            snapshotUpserter = get(),
            seedingGate = get(),
            savingsGoalDataSource = get<SavingsGoalDao>(),
            clock = get(),
        )
    }
    single<LiabilityRepository> { LiabilityRepositoryImpl(get(), get(), get(), get()) }
    single<AccountGroupRepository> { AccountGroupRepositoryImpl(get(), get()) }
    single<NetWorthRepository> { NetWorthRepositoryImpl(get(), get()) }
    single<SavingsGoalRepository> {
        SavingsGoalRepositoryImpl(
            database = get(),
            dataSource = get<SavingsGoalDao>(),
            assetDao = get(),
            seedingGate = get(),
            clock = get(),
        )
    }
    single<PreferencesRepository> { PreferencesRepositoryImpl(dataStoreFilePath(get<PlatformContext>())) }
    single<DataMaintenanceRepository> { DataMaintenanceRepositoryImpl(get()) }
}
