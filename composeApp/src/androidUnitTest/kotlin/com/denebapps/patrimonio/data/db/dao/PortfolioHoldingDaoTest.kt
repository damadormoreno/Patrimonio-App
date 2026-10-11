package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PortfolioHoldingDaoTest {
    private fun quoted(id: String, name: String = id) = PortfolioHoldingEntity(
        id = id,
        name = name,
        type = "FUND",
        isin = "IE00B03HCZ61",
        ticker = null,
        kind = "QUOTED",
        provider = "MORNINGSTAR",
        providerCode = "0P00012PP6",
        currency = "EUR",
        unitsScaled = 8_100_001,
        priceScaled = null,
        priceDateEpochDay = null,
        priceFetchedAtEpochMs = null,
        valueMinor = null,
        valuedAtEpochMs = null,
    )

    private fun manual(id: String) = PortfolioHoldingEntity(
        id = id,
        name = id,
        type = "OTHER",
        isin = null,
        ticker = null,
        kind = "MANUAL",
        provider = null,
        providerCode = null,
        currency = "USD",
        unitsScaled = null,
        priceScaled = null,
        priceDateEpochDay = null,
        priceFetchedAtEpochMs = null,
        valueMinor = 250_000,
        valuedAtEpochMs = 7,
    )

    @Test
    fun `rows round trip and list in case insensitive name order`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.portfolioHoldingDao()
        dao.insert(quoted("a", name = "vanguard"))
        dao.insert(manual("b"))
        dao.insert(quoted("c", name = "Amundi"))

        assertEquals(listOf("c", "b", "a"), dao.list().map { it.id })
        assertEquals(dao.list(), dao.observeAll().first())
        assertEquals(manual("b"), dao.find("b"))
        assertNull(dao.find("missing"))
        db.close()
    }

    @Test
    fun `inserting an existing id fails`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.portfolioHoldingDao()
        dao.insert(quoted("a"))

        assertTrue(runCatching { dao.insert(quoted("a", name = "other")) }.isFailure)
        assertEquals("a", dao.find("a")?.name)
        db.close()
    }

    @Test
    fun `update replaces the whole row and reports whether it matched`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.portfolioHoldingDao()
        dao.insert(quoted("a"))

        assertEquals(1, dao.update(quoted("a", name = "Renamed")))
        assertEquals(0, dao.update(quoted("missing")))
        assertEquals("Renamed", dao.find("a")?.name)
        db.close()
    }

    @Test
    fun `targeted updates only touch rows of the matching kind`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.portfolioHoldingDao()
        dao.insert(quoted("q"))
        dao.insert(manual("m"))

        assertEquals(1, dao.updateUnits("q", 5_000_000))
        assertEquals(0, dao.updateUnits("m", 5_000_000))
        assertEquals(1, dao.updatePrice("q", 12_345_678, 20_000, 1_000))
        assertEquals(0, dao.updatePrice("m", 1, 1, 1))
        assertEquals(1, dao.updateManualValue("m", 9_900, "GBP", 2_000))
        assertEquals(0, dao.updateManualValue("q", 9_900, "GBP", 2_000))

        val q = dao.find("q")
        assertEquals(5_000_000L, q?.unitsScaled)
        assertEquals(12_345_678L, q?.priceScaled)
        assertEquals(20_000L, q?.priceDateEpochDay)
        assertEquals(1_000L, q?.priceFetchedAtEpochMs)
        val m = dao.find("m")
        assertEquals(9_900L, m?.valueMinor)
        assertEquals("GBP", m?.currency)
        assertEquals(2_000L, m?.valuedAtEpochMs)
        assertNull(m?.unitsScaled)
        db.close()
    }

    @Test
    fun `delete removes one row and deleteAll empties the table`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.portfolioHoldingDao()
        dao.insert(quoted("a"))
        dao.insert(manual("b"))
        dao.insert(manual("c"))

        assertEquals(1, dao.delete("a"))
        assertEquals(0, dao.delete("a"))
        dao.deleteAll()

        assertEquals(emptyList(), dao.list())
        db.close()
    }
}
