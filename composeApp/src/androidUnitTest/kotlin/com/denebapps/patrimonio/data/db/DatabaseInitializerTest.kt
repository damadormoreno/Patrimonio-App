package com.denebapps.patrimonio.data.db

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DatabaseInitializerTest {
    @Test
    fun `first run seeds the FX fallback rates with fetchedAtEpochMs pinned to 0`() = runTest {
        val db = buildInMemoryTestDatabase()
        val initializer = DatabaseInitializer(db, db.fxRateDao())

        initializer.ensureSeeded()
        val rates = db.fxRateDao().list()

        assertEquals(4, rates.size)
        assertTrue(rates.all { it.fetchedAtEpochMs == 0L })
        db.close()
    }

    @Test
    fun `calling ensureSeeded twice does not duplicate FX rates`() = runTest {
        val db = buildInMemoryTestDatabase()
        val initializer = DatabaseInitializer(db, db.fxRateDao())

        initializer.ensureSeeded()
        initializer.ensureSeeded()

        assertEquals(4, db.fxRateDao().count())
        db.close()
    }

    @Test
    fun `existing FX rates are not overwritten by the seed`() = runTest {
        val db = buildInMemoryTestDatabase()
        val fresh = DatabaseInitializer.SEED_FX_RATES.first().copy(fetchedAtEpochMs = 42)
        db.fxRateDao().upsertAll(listOf(fresh))
        val initializer = DatabaseInitializer(db, db.fxRateDao())

        initializer.ensureSeeded()

        assertEquals(listOf(fresh), db.fxRateDao().list())
        db.close()
    }
}
