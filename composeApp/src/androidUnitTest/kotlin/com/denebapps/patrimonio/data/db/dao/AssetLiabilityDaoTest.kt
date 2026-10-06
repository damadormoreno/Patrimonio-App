package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AssetLiabilityDaoTest {
    @Test
    fun `asset insert-list-deleteIfUnlinked full lifecycle`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.assetDao()
        dao.insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))

        assertEquals(1, dao.list().size)
        assertEquals(1, dao.deleteIfUnlinked("a1"))
        assertEquals(0, dao.list().size)
        db.close()
    }

    @Test
    fun `asset observeAll emits after insert`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.assetDao()

        dao.insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val rows = dao.observeAll().first()

        assertEquals(1, rows.size)
        assertEquals("Checking", rows.first().name)
        db.close()
    }

    @Test
    fun `liability insert-list-deleteById full lifecycle`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.liabilityDao()
        dao.insert(LiabilityEntity("l1", "CARD", "Visa", null, 50_00, "EUR"))

        assertEquals(1, dao.list().size)
        dao.deleteById("l1")
        assertEquals(0, dao.list().size)
        db.close()
    }
}
