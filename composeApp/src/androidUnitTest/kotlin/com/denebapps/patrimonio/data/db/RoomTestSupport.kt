package com.denebapps.patrimonio.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDao
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Builds an in-memory [AppDatabase] for tests. Room's Android `inMemoryDatabaseBuilder` overload
 * requires a real `Context` + `KClass` (no reified no-arg overload exists on the Android target —
 * confirmed at apply time, see design.md Decision #9 / Open Question a); Robolectric supplies a
 * fake `Application` Context for plain JVM unit tests (`testDebugUnitTest`), no device/emulator
 * needed.
 *
 * Uses [AndroidSQLiteDriver] (the framework driver), NOT `BundledSQLiteDriver`: the bundled
 * driver's native `.so` targets Android device ABIs (Bionic libc) and cannot load under a plain
 * JVM even with Robolectric — confirmed at apply time via `UnsatisfiedLinkError: no sqliteJni in
 * java.library.path`. Robolectric shadows `android.database.sqlite.SQLiteDatabase` with its own
 * host-native SQLite, so the framework driver works fine for tests. Production `dbModule` keeps
 * `BundledSQLiteDriver` for a consistent SQLite version across OEM devices — this divergence is
 * test-infrastructure-only.
 */
fun buildInMemoryTestDatabase(): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
}

/** Inserts [goal] and returns its id, for tests written against the old autoincrement `insertGoal`. */
suspend fun SavingsGoalDao.insertGoalReturningId(goal: SavingsGoalEntity): String {
    insertGoal(goal)
    return goal.id
}

/** A [SeedingGate] whose [DatabaseInitializer.ensureSeeded] starts immediately on [this] scope —
 *  used by repository tests that need the FX seed rows. */
fun CoroutineScope.testSeedingGate(db: AppDatabase): SeedingGate =
    SeedingGate(this, DatabaseInitializer(db, db.fxRateDao()))
