package com.denebapps.patrimonio.data.db

import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
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

    @Test
    fun `first run seeds a single portfolio called Mi cartera`() = runTest {
        val db = buildInMemoryTestDatabase()
        val initializer = DatabaseInitializer(db, db.fxRateDao(), newPortfolioId = { "first-portfolio" })

        initializer.ensureSeeded()

        val portfolio = db.portfolioDao().list().single()
        assertEquals("first-portfolio", portfolio.id)
        assertEquals("Mi cartera", portfolio.name)
        assertEquals(0, portfolio.sortOrder)
        db.close()
    }

    @Test
    fun `the default portfolio gets a random UUID unless told otherwise`() = runTest {
        val db = buildInMemoryTestDatabase()

        DatabaseInitializer(db, db.fxRateDao()).ensureSeeded()

        val id = db.portfolioDao().list().single().id
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
        db.close()
    }

    @Test
    fun `calling ensureSeeded twice does not duplicate the default portfolio`() = runTest {
        val db = buildInMemoryTestDatabase()
        var generated = 0
        val initializer = DatabaseInitializer(db, db.fxRateDao(), newPortfolioId = { "portfolio-${generated++}" })

        initializer.ensureSeeded()
        initializer.ensureSeeded()

        assertEquals(listOf("portfolio-0"), db.portfolioDao().list().map { it.id })
        db.close()
    }

    @Test
    fun `existing portfolios are left alone by the seed`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.portfolioDao().insert(PortfolioEntity("mine", "Jubilación", 3))

        DatabaseInitializer(db, db.fxRateDao()).ensureSeeded()

        assertEquals(listOf(PortfolioEntity("mine", "Jubilación", 3)), db.portfolioDao().list())
        db.close()
    }
}
