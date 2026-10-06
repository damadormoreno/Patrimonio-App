package com.denebapps.patrimonio.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.denebapps.patrimonio.AppViewModel
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.configureAppDatabase
import com.denebapps.patrimonio.data.db.createAppDatabaseBuilder
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDao
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.platform.PlatformContext
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.BackupRepository
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.ui.screens.patrimonio.AddPatrimonioSheetViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.GruposViewModel
import com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioViewModel
import com.denebapps.patrimonio.ui.screens.perfil.ProfileViewModel
import com.denebapps.patrimonio.ui.screens.savings.SavingsGoalsViewModel
import com.denebapps.patrimonio.ui.screens.settings.BackupViewModel
import com.denebapps.patrimonio.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** Test-only `dbModule` override providing an in-memory [AppDatabase]. */
private fun testDbModule(context: Context) = module {
    single<PlatformContext> { PlatformContext(context) }
    single<AppDatabase> {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
    }
    bindDatabaseDependents()
}

private fun stopGlobalKoinIfStarted() {
    if (GlobalContext.getOrNull() != null) stopKoin()
}

private fun runCleanupActions(closeDatabase: () -> Unit, stopKoin: () -> Unit) {
    var primaryFailure: Throwable? = null
    try {
        closeDatabase()
    } catch (failure: Throwable) {
        primaryFailure = failure
    }
    try {
        stopKoin()
    } catch (failure: Throwable) {
        if (primaryFailure == null) {
            primaryFailure = failure
        } else if (primaryFailure !== failure) {
            primaryFailure.addSuppressed(failure)
        }
    }
    primaryFailure?.let { throw it }
}

private class KoinFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

@RunWith(RobolectricTestRunner::class)
class KoinModuleTest {
    private var resolvedDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        resolvedDatabase = null
        // Koin's GlobalContext is JVM-wide. A leftover instance is stopped defensively, but any
        // failure is propagated so setup cannot hide cleanup debt from the previous test.
        stopGlobalKoinIfStarted()
    }

    @After
    fun tearDown() {
        val database = resolvedDatabase
        resolvedDatabase = null
        runCleanupActions(
            closeDatabase = { database?.close() },
            stopKoin = ::stopGlobalKoinIfStarted,
        )
    }

    @Test
    fun `cleanup preserves close failure and suppresses stop failure after attempting both`() {
        val closeFailure = IllegalStateException("close failed")
        val stopFailure = IllegalArgumentException("stop failed")
        var stopAttempted = false

        val thrown =
            assertFailsWith<IllegalStateException> {
                runCleanupActions(
                    closeDatabase = { throw closeFailure },
                    stopKoin = {
                        stopAttempted = true
                        throw stopFailure
                    },
                )
            }

        assertSame(closeFailure, thrown)
        assertEquals(listOf(stopFailure), thrown.suppressed.toList())
        assertEquals(true, stopAttempted)
    }

    @Test
    fun `cleanup propagates stop failure after successful database close`() {
        val stopFailure = IllegalStateException("stop failed")
        var closeAttempted = false

        val thrown =
            assertFailsWith<IllegalStateException> {
                runCleanupActions(
                    closeDatabase = { closeAttempted = true },
                    stopKoin = { throw stopFailure },
                )
            }

        assertSame(stopFailure, thrown)
        assertEquals(true, closeAttempted)
    }

    @Test
    fun `every persistence repository resolves via DI with all dependencies satisfied`() {
        val koin = startTestKoin()

        assertNotNull(koin.get<AssetRepository>())
        assertNotNull(koin.get<LiabilityRepository>())
        assertNotNull(koin.get<AccountGroupRepository>())
        assertNotNull(koin.get<NetWorthRepository>())
        assertNotNull(koin.get<PreferencesRepository>())
        assertNotNull(koin.get<DataMaintenanceRepository>())
        assertNotNull(koin.get<BackupRepository>())
    }

    @Test
    fun `savings goal DAO repository and production dependencies resolve as singletons`() {
        val koin = startTestKoin()

        val dao = koin.get<SavingsGoalDao>()
        val repository = koin.get<SavingsGoalRepository>()

        assertSame(dao, koin.get<SavingsGoalDao>())
        assertSame(repository, koin.get<SavingsGoalRepository>())
        assertSame(koin.get<SeedingGate>(), koin.get<SeedingGate>())
        assertSame(koin.get<Clock>(), koin.get<Clock>())
        assertSame(koin.get<AppDatabase>().savingsGoalDao(), dao)
    }

    @Test
    fun `savings goal repository resolved by Koin creates and observes persisted state`() = runTest {
        val koin = startTestKoin()
        val repository = koin.get<SavingsGoalRepository>()

        val goalId =
            repository.create(
                CreateSavingsGoal(
                    name = "Koin goal",
                    target = CurrencyAmount(Money(25_000), Currency.EUR),
                ),
            )

        val goal = repository.observeAll().first().single()
        assertEquals(goalId, goal.id)
        assertEquals("Koin goal", goal.name)
        assertEquals(Money.ZERO, goal.progress)
    }

    @Test
    fun `asset repository uses injected savings goal DAO and clock`() = runTest {
        val fixedInstant = Instant.parse("2026-07-16T12:00:00Z")
        val koin = startTestKoin(module { single<Clock> { KoinFixedClock(fixedInstant) } })
        val assetRepository = koin.get<AssetRepository>()
        val goalRepository = koin.get<SavingsGoalRepository>()
        assetRepository.insert(
            Asset(
                id = "asset-di",
                group = Asset.AssetGroup.BANK,
                name = "Bank",
                subtitle = null,
                amount = CurrencyAmount(Money(100_000), Currency.EUR),
            ),
        )
        val goalId =
            goalRepository.create(
                CreateSavingsGoal(
                    name = "DI goal",
                    target = CurrencyAmount(Money(25_000), Currency.EUR),
                    linkedAssetId = "asset-di",
                ),
            )

        assetRepository.deleteById("asset-di")

        assertEquals(
            fixedInstant.toEpochMilliseconds(),
            goalRepository.observeLinkHistory(goalId).first().last().timestampEpochMs,
        )
    }

    @Test
    fun `Android production builder plus shared configuration opens fresh schema 1`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(PRODUCTION_DATABASE)
        val database =
            createAppDatabaseBuilder(PlatformContext(context))
                .configureAppDatabase()
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                .build()

        try {
            val goalId =
                database.savingsGoalDao().insertGoal(
                    SavingsGoalEntity(
                        name = "Schema 1",
                        targetMinor = 1,
                        currency = "EUR",
                        targetDateEpochDay = null,
                        linkedAssetId = null,
                        lifecycle = "OPEN",
                    ),
                )

            assertEquals("Schema 1", database.savingsGoalDao().findGoal(goalId)?.name)
        } finally {
            database.close()
            context.deleteDatabase(PRODUCTION_DATABASE)
        }
    }

    @Test
    fun `FxRepository resolves via DI with the platform Ktor engine and all dependencies satisfied`() {
        val koin = startTestKoin(fxModule)

        assertNotNull(koin.get<FxRepository>())
    }

    @Test
    fun `every presentation ViewModel resolves with all dependencies satisfied`() {
        val koin = startTestKoin(fxModule, presentationModule)

        assertNotNull(koin.get<AppViewModel>())
        assertNotNull(koin.get<SettingsViewModel>())
        assertNotNull(koin.get<BackupViewModel>())
        assertNotNull(koin.get<PatrimonioViewModel>())
        assertNotNull(koin.get<AddPatrimonioSheetViewModel> { parametersOf(false, null) })
        assertNotNull(koin.get<GruposViewModel>())
        assertNotNull(koin.get<SavingsGoalsViewModel>())
        assertNotNull(koin.get<ProfileViewModel>())
    }

    private fun startTestKoin(vararg additionalModules: Module): Koin {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin =
            startKoin {
                allowOverride(true)
                modules(testDbModule(context), dataModule, *additionalModules)
            }.koin
        resolvedDatabase = koin.get<AppDatabase>()
        return koin
    }

    companion object {
        private const val PRODUCTION_DATABASE = "patrimonio.db"
    }
}
