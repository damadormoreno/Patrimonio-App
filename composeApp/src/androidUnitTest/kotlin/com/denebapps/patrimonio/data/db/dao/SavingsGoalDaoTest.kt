package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalGroupEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.insertGoalReturningId
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails

@RunWith(RobolectricTestRunner::class)
class SavingsGoalDaoTest {
    @Test
    fun `reactive snapshots keep independent ordered histories without multiplying allocations`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.assetDao().insert(asset("asset-1"))
        val emissions = dao.observeAll().produceIn(backgroundScope)

        assertEquals(emptyList(), emissions.receive())

        val goalId = dao.insertGoalReturningId(goal())
        emissions.receive()
        dao.insertLinkedAssets(listOf(SavingsGoalAssetEntity(goalId, "asset-1")))
        assertEquals(setOf("asset-1"), emissions.receive().single().linkedAssetIds)

        dao.insertAllocationEvent(allocation(id = 20, goalId = goalId, deltaMinor = 200, timestamp = 1_000))
        emissions.receive()
        dao.insertAllocationEvent(allocation(id = 10, goalId = goalId, deltaMinor = 100, timestamp = 1_000))
        emissions.receive()
        dao.insertLinkEvent(link(id = 40, goalId = goalId, timestamp = 2_000))
        emissions.receive()
        dao.insertLinkEvent(link(id = 30, goalId = goalId, timestamp = 2_000))

        val snapshot = emissions.receive().single()
        assertEquals(listOf(10L, 20L), snapshot.allocationEvents.map { it.id })
        assertEquals(listOf(30L, 40L), snapshot.linkEvents.map { it.id })
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK.name, SavingsGoalLinkEventKind.LINK.name),
            snapshot.linkEvents.map { it.kind },
        )
        assertEquals(listOf(100L, 200L), snapshot.allocationEvents.map { it.deltaMinor })
        assertEquals(2, snapshot.allocationEvents.size)
        assertEquals(2, snapshot.linkEvents.size)
        db.close()
    }

    @Test
    fun `history flows order by timestamp then id and retain every appended event`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        val goalId = dao.insertGoalReturningId(goal())

        dao.insertAllocationEvent(allocation(id = 30, goalId = goalId, deltaMinor = 300, timestamp = 2_000))
        dao.insertAllocationEvent(allocation(id = 20, goalId = goalId, deltaMinor = 200, timestamp = 1_000))
        dao.insertAllocationEvent(allocation(id = 10, goalId = goalId, deltaMinor = 100, timestamp = 1_000))
        dao.insertLinkEvent(link(id = 30, goalId = goalId, timestamp = 2_000))
        dao.insertLinkEvent(link(id = 20, goalId = goalId, timestamp = 1_000))
        dao.insertLinkEvent(link(id = 10, goalId = goalId, timestamp = 1_000))

        assertEquals(listOf(10L, 20L, 30L), dao.observeAllocationHistory(goalId).first().map { it.id })
        assertEquals(listOf(10L, 20L, 30L), dao.observeLinkHistory(goalId).first().map { it.id })
        assertEquals(listOf(100L, 200L, 300L), dao.observeAllocationHistory(goalId).first().map { it.deltaMinor })
        db.close()
    }

    @Test
    fun `snapshot lists the linked assets and drops one after it is unlinked and deleted`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.assetDao().insert(asset("asset-1"))
        db.assetDao().insert(asset("asset-2"))
        val goalId = dao.insertGoalReturningId(goal())
        dao.insertLinkedAssets(listOf("asset-1", "asset-2").map { SavingsGoalAssetEntity(goalId, it) })
        val emissions = dao.observeAll().produceIn(backgroundScope)

        val linked = emissions.receive().single()
        assertEquals(goalId, linked.goal.id)
        assertEquals(setOf("asset-1", "asset-2"), linked.linkedAssetIds)
        assertEquals(listOf(goalId), dao.listGoalsLinkedToAsset("asset-1").map { it.id })
        assertEquals(0, db.assetDao().deleteIfUnlinked("asset-1"))

        assertEquals(1, dao.clearLinkedAsset("asset-1"))
        assertEquals(1, db.assetDao().deleteIfUnlinked("asset-1"))

        assertEquals(setOf("asset-2"), emissions.receive().single().linkedAssetIds)
        assertEquals(emptyList(), dao.listGoalsLinkedToAsset("asset-1"))
        assertEquals(listOf("asset-2"), dao.listLinkedAssetIds(goalId))
        db.close()
    }

    @Test
    fun `linked asset primitives add and remove rows and reject unknown assets`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        listOf("asset-1", "asset-2", "asset-3").forEach { db.assetDao().insert(asset(it)) }
        val goalId = dao.insertGoalReturningId(goal())

        dao.insertLinkedAssets(listOf("asset-3", "asset-1", "asset-2").map { SavingsGoalAssetEntity(goalId, it) })
        assertEquals(listOf("asset-1", "asset-2", "asset-3"), dao.listLinkedAssetIds(goalId))
        assertEquals(2, dao.deleteLinkedAssets(goalId, listOf("asset-1", "asset-3", "elsewhere")))
        assertEquals(listOf("asset-2"), dao.listLinkedAssetIds(goalId))
        assertFails { dao.insertLinkedAssets(listOf(SavingsGoalAssetEntity(goalId, "missing"))) }
        assertFails { dao.insertLinkedAssets(listOf(SavingsGoalAssetEntity(goalId, "asset-2"))) }

        assertEquals(1, dao.updateLifecycle(goalId, "CLOSED"))
        assertEquals("CLOSED", dao.findGoal(goalId)?.lifecycle)
        // Deleting the goal takes its linked-asset rows with it, never the assets.
        assertEquals(1, dao.deleteGoal(goalId))
        assertEquals(emptyList(), dao.listAllLinkedAssets())
        assertEquals(3, db.assetDao().list().size)
        db.close()
    }

    @Test
    fun `group link primitives add remove and clear several groups`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        listOf("g1", "g2", "g3").forEach { db.accountGroupDao().insertGroup(AccountGroupEntity(it, it, true, 0)) }
        val goalId = dao.insertGoalReturningId(goal())
        val otherId = dao.insertGoalReturningId(goal())

        dao.insertLinkedGroups(listOf("g3", "g1", "g2").map { SavingsGoalGroupEntity(goalId, it) })
        dao.insertLinkedGroups(listOf(SavingsGoalGroupEntity(otherId, "g1")))
        assertEquals(listOf("g1", "g2", "g3"), dao.listLinkedGroupIds(goalId))
        assertEquals(listOf(goalId, otherId), dao.listGoalsLinkedToGroup("g1").map { it.id })
        assertEquals(listOf(goalId), dao.listGoalsLinkedToGroup("g2").map { it.id })
        assertFails { dao.insertLinkedGroups(listOf(SavingsGoalGroupEntity(goalId, "missing"))) }
        assertFails { dao.insertLinkedGroups(listOf(SavingsGoalGroupEntity(goalId, "g1"))) }

        assertEquals(2, dao.deleteLinkedGroups(goalId, listOf("g1", "g3", "elsewhere")))
        assertEquals(listOf("g2"), dao.listLinkedGroupIds(goalId))
        assertEquals(1, dao.clearLinkedGroup("g1"))
        assertEquals(emptyList(), dao.listGoalsLinkedToGroup("g1"))
        db.close()
    }

    @Test
    fun `holding link primitives add remove and clear several products`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        testHoldings(db, "h1", "h2", "h3")
        val goalId = dao.insertGoalReturningId(goal())

        dao.insertLinkedHoldings(listOf("h3", "h1", "h2").map { SavingsGoalHoldingEntity(goalId, it) })
        assertEquals(listOf("h1", "h2", "h3"), dao.listLinkedHoldingIds(goalId))
        assertEquals(listOf(goalId), dao.listGoalsLinkedToHolding("h2").map { it.id })
        assertFails { dao.insertLinkedHoldings(listOf(SavingsGoalHoldingEntity(goalId, "missing"))) }
        assertFails { dao.insertLinkedHoldings(listOf(SavingsGoalHoldingEntity(goalId, "h1"))) }

        assertEquals(2, dao.deleteLinkedHoldings(goalId, listOf("h1", "h3", "elsewhere")))
        assertEquals(1, dao.clearLinkedHolding("h2"))
        assertEquals(emptyList(), dao.listLinkedHoldingIds(goalId))
        db.close()
    }

    @Test
    fun `deleting a group or a holding drops only its link rows and a goal drops all of its own`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        testHoldings(db, "h1")
        db.accountGroupDao().insertGroup(AccountGroupEntity("g1", "Group", true, 0))
        val goalId = dao.insertGoalReturningId(goal())
        dao.insertLinkedGroups(listOf(SavingsGoalGroupEntity(goalId, "g1")))
        dao.insertLinkedHoldings(listOf(SavingsGoalHoldingEntity(goalId, "h1")))

        db.accountGroupDao().deleteGroup("g1")
        assertEquals(emptyList(), dao.listLinkedGroupIds(goalId))
        assertEquals(listOf("h1"), dao.listLinkedHoldingIds(goalId))
        db.portfolioHoldingDao().delete("h1")
        assertEquals(emptyList(), dao.listLinkedHoldingIds(goalId))
        assertEquals(listOf(goalId), dao.listAllGoals().map { it.id })

        db.accountGroupDao().insertGroup(AccountGroupEntity("g2", "Group", true, 0))
        dao.insertLinkedGroups(listOf(SavingsGoalGroupEntity(goalId, "g2")))
        dao.deleteGoal(goalId)
        assertEquals(emptyList(), dao.listAllLinkedGroups())
        db.close()
    }

    @Test
    fun `relation snapshots carry the accounts products and groups of each goal`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        testHoldings(db, "h1")
        db.assetDao().insert(asset("a1"))
        db.accountGroupDao().insertGroup(AccountGroupEntity("g1", "Group", true, 0))
        val goalId = dao.insertGoalReturningId(goal())
        dao.insertLinkedAssets(listOf(SavingsGoalAssetEntity(goalId, "a1")))
        dao.insertLinkedHoldings(listOf(SavingsGoalHoldingEntity(goalId, "h1")))
        dao.insertLinkedGroups(listOf(SavingsGoalGroupEntity(goalId, "g1")))

        val snapshot = dao.observeAll().first().single()

        assertEquals(setOf("a1"), snapshot.linkedAssetIds)
        assertEquals(setOf("h1"), snapshot.linkedHoldingIds)
        assertEquals(setOf("g1"), snapshot.linkedGroupIds)
        db.close()
    }

    private suspend fun testHoldings(db: AppDatabase, vararg ids: String) {
        db.insertTestPortfolio()
        ids.forEach { id ->
            db.portfolioHoldingDao().insert(
                PortfolioHoldingEntity(
                    id, TEST_PORTFOLIO_ID, "Fondo $id", "OTHER", null, null, "MANUAL", null, null, "EUR",
                    null, null, null, null, 250_000, 1L,
                ),
            )
        }
    }

    @Test
    fun `goals are listed in creation order whatever their ids`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        dao.insertGoal(goal(id = "b", createdAtEpochMs = 3_000))
        dao.insertGoal(goal(id = "c", createdAtEpochMs = 1_000))
        dao.insertGoal(goal(id = "a", createdAtEpochMs = 2_000))

        assertEquals(listOf("c", "a", "b"), dao.observeAll().first().map { it.goal.id })
        assertEquals(listOf("c", "a", "b"), dao.listAllGoals().map { it.id })
        db.close()
    }

    @Test
    fun `relation snapshots isolate each goals allocation and link events`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        val firstGoalId = dao.insertGoalReturningId(goal())
        val secondGoalId = dao.insertGoalReturningId(goal())
        dao.insertAllocationEvent(allocation(id = 10, goalId = firstGoalId, deltaMinor = 100, timestamp = 1_000))
        dao.insertLinkEvent(link(id = 20, goalId = firstGoalId, timestamp = 1_000))
        dao.insertAllocationEvent(allocation(id = 30, goalId = secondGoalId, deltaMinor = 300, timestamp = 1_000))

        val snapshots = dao.observeAll().first().associateBy { it.goal.id }
        assertEquals(listOf(10L), snapshots.getValue(firstGoalId).allocationEvents.map { it.id })
        assertEquals(listOf(20L), snapshots.getValue(firstGoalId).linkEvents.map { it.id })
        assertEquals(listOf(30L), snapshots.getValue(secondGoalId).allocationEvents.map { it.id })
        assertEquals(emptyList(), snapshots.getValue(secondGoalId).linkEvents)
        db.close()
    }

    private var goalCount = 0L

    private fun goal(id: String = "goal-${++goalCount}", createdAtEpochMs: Long = goalCount) = SavingsGoalEntity(
        id = id,
        name = "Emergency",
        targetMinor = 10_000,
        currency = "EUR",
        targetDateEpochDay = null,
        lifecycle = "OPEN",
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun asset(id: String) = AssetEntity(id, "BANK", "Bank", null, 50_000, "EUR")

    private fun allocation(id: Long, goalId: String, deltaMinor: Long, timestamp: Long) =
        SavingsGoalAllocationEventEntity(id, goalId, deltaMinor, timestamp)

    private fun link(id: Long, goalId: String, timestamp: Long) =
        SavingsGoalLinkEventEntity(id, goalId, null, "asset-1", SavingsGoalLinkEventKind.LINK.name, timestamp)
}
