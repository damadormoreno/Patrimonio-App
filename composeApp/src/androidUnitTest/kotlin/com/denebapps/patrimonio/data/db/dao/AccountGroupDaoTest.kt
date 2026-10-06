package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccountGroupDaoTest {
    @Test
    fun `observeAll with members join returns the group's membership`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.assetDao().insert(AssetEntity("a2", "CASH", "Wallet", null, 20_00, "EUR"))
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Savings", true, 0))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))

        val rows = dao.observeAll().first()

        assertEquals(1, rows.size)
        assertEquals(setOf("a1"), rows.first().members.map { it.assetId }.toSet())
        db.close()
    }

    @Test
    fun `deleting a group cascades its membership rows`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Savings", true, 0))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))

        dao.deleteGroup("g1")
        val rows = dao.observeAll().first()

        assertTrue(rows.isEmpty())
        db.close()
    }

    @Test
    fun `an asset can overlap membership across two different groups`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Savings", true, 0))
        dao.insertGroup(AccountGroupEntity("g2", "Emergency", true, 1))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))
        dao.insertMember(AccountGroupMemberEntity("g2", "a1"))

        val rows = dao.observeAll().first()

        assertEquals(2, rows.size)
        assertTrue(rows.all { group -> group.members.any { it.assetId == "a1" } })
        db.close()
    }
}
