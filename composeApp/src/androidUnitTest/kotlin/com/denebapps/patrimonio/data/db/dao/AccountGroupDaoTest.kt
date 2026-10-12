package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupHoldingMemberEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    @Test
    fun `observeAll carries the holding members next to the asset members`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.insertManualHoldings("h1", "h2")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Casa", true, 0))
        dao.insertGroup(AccountGroupEntity("g2", "Vacía", true, 1))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h2"))

        val rows = dao.observeAll().first().associateBy { it.group.id }

        assertEquals(setOf("a1"), rows.getValue("g1").members.map { it.assetId }.toSet())
        assertEquals(setOf("h1", "h2"), rows.getValue("g1").holdingMembers.map { it.holdingId }.toSet())
        assertTrue(rows.getValue("g2").members.isEmpty() && rows.getValue("g2").holdingMembers.isEmpty())
        db.close()
    }

    @Test
    fun `a holding can be in several groups and a repeated insert is ignored`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertManualHoldings("h1")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Casa", true, 0))
        dao.insertGroup(AccountGroupEntity("g2", "Viaje", true, 1))

        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g2", "h1"))

        assertEquals(
            listOf(AccountGroupHoldingMemberEntity("g1", "h1"), AccountGroupHoldingMemberEntity("g2", "h1")),
            dao.listHoldingMembers(),
        )
        db.close()
    }

    @Test
    fun `deleting a group cascades its holding members and keeps the holdings`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertManualHoldings("h1")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Casa", true, 0))
        dao.insertGroup(AccountGroupEntity("g2", "Viaje", true, 1))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g2", "h1"))

        dao.deleteGroup("g1")

        assertEquals(listOf(AccountGroupHoldingMemberEntity("g2", "h1")), dao.listHoldingMembers())
        assertEquals(listOf("h1"), db.portfolioHoldingDao().list().map { it.id })
        db.close()
    }

    @Test
    fun `deleting a holding cascades its memberships and keeps the groups`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertManualHoldings("h1", "h2")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Casa", true, 0))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h2"))

        db.portfolioHoldingDao().delete("h1")

        assertEquals(listOf(AccountGroupHoldingMemberEntity("g1", "h2")), dao.listHoldingMembers())
        assertEquals(listOf("g1"), dao.listGroups().map { it.id })
        db.close()
    }

    @Test
    fun `a holding member needs an existing group and an existing holding`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertManualHoldings("h1")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Casa", true, 0))

        assertFails { dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "ghost")) }
        assertFails { dao.insertHoldingMember(AccountGroupHoldingMemberEntity("ghost", "h1")) }

        assertTrue(dao.listHoldingMembers().isEmpty())
        db.close()
    }

    @Test
    fun `deleting the holding members of a group leaves the other groups and the asset members`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.insertManualHoldings("h1")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Casa", true, 0))
        dao.insertGroup(AccountGroupEntity("g2", "Viaje", true, 1))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g2", "h1"))

        dao.deleteHoldingMembers("g1")

        assertEquals(listOf(AccountGroupHoldingMemberEntity("g2", "h1")), dao.listHoldingMembers())
        assertEquals(listOf(AccountGroupMemberEntity("g1", "a1")), dao.listMembers())

        dao.deleteAllHoldingMembers()

        assertTrue(dao.listHoldingMembers().isEmpty())
        assertEquals(listOf("g1", "g2"), dao.listGroups().map { it.id })
        db.close()
    }

    @Test
    fun `the groups containing a holding come back in the user's order`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.insertManualHoldings("h1", "h2")
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Viaje", true, 2))
        dao.insertGroup(AccountGroupEntity("g2", "Casa", true, 0))
        dao.insertGroup(AccountGroupEntity("g3", "Otro", true, 1))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g2", "h1"))
        dao.insertHoldingMember(AccountGroupHoldingMemberEntity("g3", "h2"))

        assertEquals(listOf("g2", "g1"), dao.listGroupsContainingHolding("h1").map { it.id })
        assertEquals(listOf("g3"), dao.listGroupsContainingHolding("h2").map { it.id })
        assertTrue(dao.listGroupsContainingHolding("ghost").isEmpty())
        db.close()
    }

    private suspend fun AppDatabase.insertManualHoldings(vararg ids: String) {
        insertTestPortfolio()
        ids.forEach { id ->
            portfolioHoldingDao().insert(
                PortfolioHoldingEntity(
                    id, TEST_PORTFOLIO_ID, "Fondo $id", "OTHER", null, null, "MANUAL", null, null, "EUR",
                    null, null, null, null, 250_000, 1L,
                ),
            )
        }
    }
}
