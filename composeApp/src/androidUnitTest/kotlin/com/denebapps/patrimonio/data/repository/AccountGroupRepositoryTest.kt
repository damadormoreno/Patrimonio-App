package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.insertGoalReturningId
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.AccountGroupNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GroupFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private class LinkEventFailingSource(
    private val delegate: SavingsGoalDataSource,
) : SavingsGoalDataSource by delegate {
    override suspend fun insertLinkEvent(event: SavingsGoalLinkEventEntity): Long =
        error("simulated link-event failure")
}

@RunWith(RobolectricTestRunner::class)
class AccountGroupRepositoryTest {
    @Test
    fun `the builtin all-accounts group is synthesized even when account_groups is empty`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        val repo = AccountGroupRepositoryImpl(db, db.accountGroupDao(), seedingGate)

        val groups = repo.observeAll().first()

        assertEquals(1, groups.size)
        assertEquals(AccountGroup.ALL_ACCOUNTS_ID, groups.first().id)
        assertEquals(null, groups.first().memberAssetIds)
        db.close()
    }

    @Test
    fun `overlapping membership across two explicit groups is preserved`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val seedingGate = testSeedingGate(db)
        val repo = AccountGroupRepositoryImpl(db, db.accountGroupDao(), seedingGate)

        repo.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = setOf("a1")))
        repo.insertGroup(AccountGroup("g2", "Emergency", true, 1, memberAssetIds = setOf("a1")))
        val groups = repo.observeAll().first().filter { it.id != AccountGroup.ALL_ACCOUNTS_ID }

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.memberAssetIds == setOf("a1") })
        db.close()
    }

    @Test
    fun `deleting a group unlinks its goals with a GROUP_DELETED event and keeps goals and ledgers`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        repo.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = setOf("a1")))
        repo.insertGroup(AccountGroup("g2", "Other", true, 1, memberAssetIds = setOf("a1")))
        val open = db.savingsGoalDao().insertGoalReturningId(goal("Open", linkedGroupId = "g1"))
        val closed =
            db.savingsGoalDao().insertGoalReturningId(goal("Closed", lifecycle = "CLOSED", linkedGroupId = "g1"))
        val onOtherGroup = db.savingsGoalDao().insertGoalReturningId(goal("Elsewhere", linkedGroupId = "g2"))
        val onAsset = db.savingsGoalDao().insertGoalReturningId(goal("OnAsset", linkedAssetId = "a1"))
        db.savingsGoalDao().insertAllocationEvent(SavingsGoalAllocationEventEntity(0, open, 12_000, 1_000))
        db.savingsGoalDao().insertLinkEvent(link(goalId = open, toGroupId = "g1", kind = SavingsGoalLinkEventKind.LINK))

        repo.deleteGroup("g1")

        assertNull(db.accountGroupDao().findGroup("g1"))
        val goals = db.savingsGoalDao().listAllGoals().associateBy { it.id }
        assertEquals(setOf(open, closed, onOtherGroup, onAsset), goals.keys)
        assertNull(goals.getValue(open).linkedGroupId)
        assertNull(goals.getValue(closed).linkedGroupId)
        assertEquals("g2", goals.getValue(onOtherGroup).linkedGroupId)
        assertEquals("a1", goals.getValue(onAsset).linkedAssetId)
        assertEquals(listOf(12_000L), db.savingsGoalDao().listAllocationHistory(open).map { it.deltaMinor })
        listOf(open, closed).forEach { goalId ->
            val deleted = db.savingsGoalDao().observeLinkHistory(goalId).first().last()
            assertEquals(SavingsGoalLinkEventKind.GROUP_DELETED.name, deleted.kind)
            assertEquals("g1", deleted.fromGroupId)
            assertNull(deleted.toGroupId)
            assertNull(deleted.fromAssetId)
            assertNull(deleted.toAssetId)
            assertEquals(DELETION_MS, deleted.timestampEpochMs)
        }
        assertEquals(1, db.savingsGoalDao().observeLinkHistory(open).first().count { it.kind == "GROUP_DELETED" })
        assertTrue(db.savingsGoalDao().observeLinkHistory(onOtherGroup).first().isEmpty())
        db.close()
    }

    @Test
    fun `deleting a group without linked goals just removes it`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        repo.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = emptySet()))

        repo.deleteGroup("g1")

        assertNull(db.accountGroupDao().findGroup("g1"))
        assertTrue(db.savingsGoalDao().listAllLinkEvents().isEmpty())
        db.close()
    }

    @Test
    fun `a failed group deletion rolls back the unlink and keeps the group`() = runTest {
        val db = buildInMemoryTestDatabase()
        val failing = groupRepository(db, LinkEventFailingSource(db.savingsGoalDao()))
        failing.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = emptySet()))
        val goalId = db.savingsGoalDao().insertGoalReturningId(goal("Open", linkedGroupId = "g1"))

        assertFails { failing.deleteGroup("g1") }

        assertEquals("g1", db.savingsGoalDao().findGoal(goalId)?.linkedGroupId)
        assertEquals("g1", db.accountGroupDao().findGroup("g1")?.id)
        assertTrue(db.savingsGoalDao().listAllLinkEvents().isEmpty())
        db.close()
    }

    @Test
    fun `editing a group updates it in place and keeps its linked goals`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.assetDao().insert(AssetEntity("a2", "CASH", "Wallet", null, 20_00, "EUR"))
        repo.insertGroup(AccountGroup("g1", "Savings", true, 3, memberAssetIds = setOf("a1")))
        val goalId = db.savingsGoalDao().insertGoalReturningId(goal("Open", linkedGroupId = "g1"))

        repo.updateGroup(AccountGroup("g1", "Colchón", false, 0, memberAssetIds = setOf("a2")))

        val group = repo.observeAll().first().single { it.id == "g1" }
        assertEquals("Colchón", group.name)
        assertEquals(false, group.showBalance)
        assertEquals(3, group.sortOrder)
        assertEquals(setOf("a2"), group.memberAssetIds)
        // An UPDATE, not a REPLACE: the goal keeps its group link and no unlink event is written.
        assertEquals("g1", db.savingsGoalDao().findGoal(goalId)?.linkedGroupId)
        assertTrue(db.savingsGoalDao().listAllLinkEvents().isEmpty())
        db.close()
    }

    @Test
    fun `reordering persists the position of each group`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        listOf("g1", "g2", "g3").forEach { id ->
            repo.insertGroup(AccountGroup(id, id, true, 0, memberAssetIds = emptySet()))
        }

        repo.reorderGroups(listOf("g3", "g1", "g2"))

        assertEquals(
            listOf(AccountGroup.ALL_ACCOUNTS_ID, "g3", "g1", "g2"),
            repo.observeAll().first().map { it.id },
        )
        db.close()
    }

    @Test
    fun `editing a missing or builtin group fails without changes`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)

        assertFailsWith<AccountGroupNotFoundException> {
            repo.updateGroup(AccountGroup("missing", "X", true, 0, memberAssetIds = emptySet()))
        }
        assertFailsWith<IllegalArgumentException> { repo.updateGroup(AccountGroup.allAccounts()) }
        assertTrue(db.accountGroupDao().listGroups().isEmpty())
        db.close()
    }

    @Test
    fun `removing a member from a group keeps the other members`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.assetDao().insert(AssetEntity("a2", "CASH", "Wallet", null, 20_00, "EUR"))
        val seedingGate = testSeedingGate(db)
        val repo = AccountGroupRepositoryImpl(db, db.accountGroupDao(), seedingGate)
        repo.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = setOf("a1", "a2")))

        repo.setMembers("g1", setOf("a2"))
        val group = repo.observeAll().first().first { it.id == "g1" }

        assertEquals(setOf("a2"), group.memberAssetIds)
        db.close()
    }

    private fun TestScope.groupRepository(db: AppDatabase, goals: SavingsGoalDataSource = db.savingsGoalDao()) =
        AccountGroupRepositoryImpl(
            db,
            db.accountGroupDao(),
            testSeedingGate(db),
            goals,
            GroupFixedClock(Instant.parse(DELETION_TIME)),
        )

    private fun goal(
        name: String,
        lifecycle: String = "OPEN",
        linkedAssetId: String? = null,
        linkedGroupId: String? = null,
    ) = SavingsGoalEntity(
        id = "goal-$name",
        name = name,
        targetMinor = 100_000,
        currency = "EUR",
        targetDateEpochDay = null,
        linkedAssetId = linkedAssetId,
        lifecycle = lifecycle,
        linkedGroupId = linkedGroupId,
        createdAtEpochMs = 0,
    )

    private fun link(goalId: String, toGroupId: String, kind: SavingsGoalLinkEventKind) = SavingsGoalLinkEventEntity(
        goalId = goalId,
        fromAssetId = null,
        toAssetId = null,
        kind = kind.name,
        timestampEpochMs = 500,
        toGroupId = toGroupId,
    )

    private companion object {
        const val DELETION_TIME = "2026-07-13T12:00:00Z"
        const val DELETION_MS = 1_783_944_000_000L
    }
}
