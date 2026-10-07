package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.insertGoalReturningId
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SavingsGoalDaoTest {
    @Test
    fun `reactive snapshots keep independent ordered histories without multiplying allocations`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.assetDao().insert(asset("asset-1"))
        val emissions = dao.observeAll().produceIn(backgroundScope)

        assertEquals(emptyList(), emissions.receive())

        val goalId = dao.insertGoalReturningId(goal(linkedAssetId = "asset-1"))
        assertEquals("asset-1", emissions.receive().single().currentAsset?.id)

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
    fun `snapshot exposes current asset then emits missing asset after explicit unlink and deletion`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.assetDao().insert(asset("asset-1"))
        val goalId = dao.insertGoalReturningId(goal(linkedAssetId = "asset-1"))
        val emissions = dao.observeAll().produceIn(backgroundScope)

        val linked = emissions.receive().single()
        assertEquals(goalId, linked.goal.id)
        assertEquals("asset-1", linked.currentAsset?.id)

        assertEquals(1, dao.clearLinkedAsset("asset-1"))
        assertEquals(1, db.assetDao().deleteIfUnlinked("asset-1"))

        val unlinked = emissions.receive().single()
        assertNull(unlinked.goal.linkedAssetId)
        assertNull(unlinked.currentAsset)
        db.close()
    }

    @Test
    fun `goal metadata primitives update only current link and lifecycle`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.assetDao().insert(asset("asset-1"))
        db.assetDao().insert(asset("asset-2"))
        val goalId = dao.insertGoalReturningId(goal(linkedAssetId = "asset-1"))

        assertEquals(1, dao.updateLinkedAsset(goalId, "asset-2"))
        assertEquals("asset-2", dao.findGoal(goalId)?.linkedAssetId)
        assertEquals(1, dao.updateLifecycle(goalId, "CLOSED"))
        assertEquals("CLOSED", dao.findGoal(goalId)?.lifecycle)
        db.close()
    }

    @Test
    fun `group link primitives keep the link exclusive with the asset link`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.assetDao().insert(asset("asset-1"))
        db.accountGroupDao().insertGroup(AccountGroupEntity("g1", "Group", true, 0))
        val goalId = dao.insertGoalReturningId(goal(linkedAssetId = "asset-1"))

        assertEquals(1, dao.updateLinkedGroup(goalId, "g1"))
        assertNull(dao.findGoal(goalId)?.linkedAssetId)
        assertEquals("g1", dao.findGoal(goalId)?.linkedGroupId)
        assertEquals(listOf(goalId), dao.listGoalsLinkedToGroup("g1").map { it.id })

        assertEquals(1, dao.updateLinkedAsset(goalId, "asset-1"))
        assertEquals("asset-1", dao.findGoal(goalId)?.linkedAssetId)
        assertNull(dao.findGoal(goalId)?.linkedGroupId)
        assertEquals(emptyList(), dao.listGoalsLinkedToGroup("g1"))

        dao.updateLinkedGroup(goalId, "g1")
        assertEquals(1, dao.clearLinkedGroup("g1"))
        assertNull(dao.findGoal(goalId)?.linkedGroupId)
        db.close()
    }

    @Test
    fun `deleting a group nulls the group link of its goals through the foreign key`() = runTest {
        val db = buildInMemoryTestDatabase()
        val dao = db.savingsGoalDao()
        db.accountGroupDao().insertGroup(AccountGroupEntity("g1", "Group", true, 0))
        val goalId = dao.insertGoalReturningId(goal(linkedGroupId = "g1"))

        db.accountGroupDao().deleteGroup("g1")

        assertNull(dao.findGoal(goalId)?.linkedGroupId)
        db.close()
    }

    @Test
    fun `a goal cannot reference a group that does not exist`() = runTest {
        val db = buildInMemoryTestDatabase()

        assertFails { db.savingsGoalDao().insertGoal(goal(linkedGroupId = "missing")) }
        db.close()
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

    private fun goal(
        linkedAssetId: String? = null,
        linkedGroupId: String? = null,
        id: String = "goal-${++goalCount}",
        createdAtEpochMs: Long = goalCount,
    ) = SavingsGoalEntity(
        id = id,
        name = "Emergency",
        targetMinor = 10_000,
        currency = "EUR",
        targetDateEpochDay = null,
        linkedAssetId = linkedAssetId,
        lifecycle = "OPEN",
        linkedGroupId = linkedGroupId,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun asset(id: String) = AssetEntity(id, "BANK", "Bank", null, 50_000, "EUR")

    private fun allocation(id: Long, goalId: String, deltaMinor: Long, timestamp: Long) =
        SavingsGoalAllocationEventEntity(id, goalId, deltaMinor, timestamp)

    private fun link(id: Long, goalId: String, timestamp: Long) =
        SavingsGoalLinkEventEntity(id, goalId, null, "asset-1", SavingsGoalLinkEventKind.LINK.name, timestamp)
}
