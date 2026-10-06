package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class NetWorthFxDaoTest {
    @Test
    fun `upsert then find returns the persisted snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.netWorthDao()
        dao.upsert(NetWorthSnapshotEntity("2026-05", 100_000, 20_000))

        val found = dao.find("2026-05")

        assertEquals(100_000L, found?.assetsMinor)
        db.close()
    }

    @Test
    fun `findMostRecentBefore returns the most recent snapshot strictly before the given month, skipping gaps`() =
        runTest {
            val db = buildInMemoryTestDatabase()
            val dao = db.netWorthDao()
            dao.upsert(NetWorthSnapshotEntity("2026-02", 50_000, 0))
            dao.upsert(NetWorthSnapshotEntity("2026-03", 60_000, 0))
            // 2026-04 skipped (no row)

            val result = dao.findMostRecentBefore("2026-05")

            assertEquals("2026-03", result?.yearMonth)
            db.close()
        }

    @Test
    fun `findMostRecentBefore returns null when no earlier snapshot exists`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.netWorthDao()
        dao.upsert(NetWorthSnapshotEntity("2026-05", 100_000, 0))

        val result = dao.findMostRecentBefore("2026-05")

        assertNull(result)
        db.close()
    }

    @Test
    fun `fxRateDao upsertAll then count reflects the persisted rows`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.fxRateDao()

        dao.upsertAll(
            listOf(
                FxRateEntity("EUR", 1_000_000, 0),
                FxRateEntity("USD", 920_000, 0),
            ),
        )

        assertEquals(2, dao.count())
        db.close()
    }
}
