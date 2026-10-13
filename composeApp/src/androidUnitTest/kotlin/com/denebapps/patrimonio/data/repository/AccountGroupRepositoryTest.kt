package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.entity.AccountGroupHoldingMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalGroupEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.insertGoalReturningId
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.AccountGroupRef
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.AccountGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
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
        val open = db.insertGoalLinkedTo(goal("Open"), "g1")
        val closed = db.insertGoalLinkedTo(goal("Closed", lifecycle = "CLOSED"), "g1")
        val onOtherGroup = db.insertGoalLinkedTo(goal("Elsewhere"), "g2")
        val onAsset = db.savingsGoalDao().insertGoalReturningId(goal("OnAsset"))
        db.savingsGoalDao().insertLinkedAssets(listOf(SavingsGoalAssetEntity(onAsset, "a1")))
        db.savingsGoalDao().insertAllocationEvent(SavingsGoalAllocationEventEntity(0, open, 12_000, 1_000))
        db.savingsGoalDao().insertLinkEvent(link(goalId = open, toGroupId = "g1", kind = SavingsGoalLinkEventKind.LINK))

        repo.deleteGroup("g1")

        assertNull(db.accountGroupDao().findGroup("g1"))
        val goals = db.savingsGoalDao().listAllGoals().associateBy { it.id }
        assertEquals(setOf(open, closed, onOtherGroup, onAsset), goals.keys)
        assertEquals(emptyList(), db.savingsGoalDao().listLinkedGroupIds(open))
        assertEquals(emptyList(), db.savingsGoalDao().listLinkedGroupIds(closed))
        assertEquals(listOf("g2"), db.savingsGoalDao().listLinkedGroupIds(onOtherGroup))
        assertEquals(listOf("a1"), db.savingsGoalDao().listLinkedAssetIds(onAsset))
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
        val goalId = db.insertGoalLinkedTo(goal("Open"), "g1")

        assertFails { failing.deleteGroup("g1") }

        assertEquals(listOf("g1"), db.savingsGoalDao().listLinkedGroupIds(goalId))
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
        val goalId = db.insertGoalLinkedTo(goal("Open"), "g1")

        repo.updateGroup(AccountGroup("g1", "Colchón", false, 0, memberAssetIds = setOf("a2")))

        val group = repo.observeAll().first().single { it.id == "g1" }
        assertEquals("Colchón", group.name)
        assertEquals(false, group.showBalance)
        assertEquals(3, group.sortOrder)
        assertEquals(setOf("a2"), group.memberAssetIds)
        // An UPDATE, not a REPLACE: the goal keeps its group link and no unlink event is written.
        assertEquals(listOf("g1"), db.savingsGoalDao().listLinkedGroupIds(goalId))
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

        repo.setMembers("g1", setOf("a2"), emptySet())
        val group = repo.observeAll().first().first { it.id == "g1" }

        assertEquals(setOf("a2"), group.memberAssetIds)
        db.close()
    }

    @Test
    fun `setting the members replaces the accounts and the holdings of the group together`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1")))
        repo.insertGroup(AccountGroup("g2", "Otro", true, 1, setOf("a1"), setOf("h1")))

        repo.setMembers("g1", setOf("a2"), setOf("h2", "h3"))

        val groups = repo.observeAll().first().associateBy { it.id }
        assertEquals(setOf("a2"), groups.getValue("g1").memberAssetIds)
        assertEquals(setOf("h2", "h3"), groups.getValue("g1").memberHoldingIds)
        assertEquals(setOf("a1"), groups.getValue("g2").memberAssetIds)
        assertEquals(setOf("h1"), groups.getValue("g2").memberHoldingIds)
        db.close()
    }

    @Test
    fun `a group keeps accounts and portfolio holdings as members and a holding may be in several groups`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)

        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1", "h2")))
        repo.insertGroup(AccountGroup("g2", "Viaje", true, 1, emptySet(), setOf("h1")))
        repo.insertGroup(AccountGroup("g3", "Solo cuentas", true, 2, setOf("a2")))

        val groups = repo.observeAll().first().associateBy { it.id }
        assertEquals(setOf("a1"), groups.getValue("g1").memberAssetIds)
        assertEquals(setOf("h1", "h2"), groups.getValue("g1").memberHoldingIds)
        assertEquals(emptySet(), groups.getValue("g2").memberAssetIds)
        assertEquals(setOf("h1"), groups.getValue("g2").memberHoldingIds)
        assertEquals(setOf("a2"), groups.getValue("g3").memberAssetIds)
        assertEquals(emptySet(), groups.getValue("g3").memberHoldingIds)
        db.close()
    }

    @Test
    fun `the builtin all-accounts group never carries holdings`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1")))

        val builtin = repo.observeAll().first().first()

        assertEquals(AccountGroup.ALL_ACCOUNTS_ID, builtin.id)
        assertNull(builtin.memberAssetIds)
        assertEquals(emptySet(), builtin.memberHoldingIds)
        db.close()
    }

    @Test
    fun `editing a group replaces its holdings in place and keeps its linked goals`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 3, setOf("a1"), setOf("h1", "h2")))
        val goalId = db.insertGoalLinkedTo(goal("Open"), "g1")

        repo.updateGroup(AccountGroup("g1", "Casa nueva", true, 0, setOf("a1"), setOf("h3")))

        val group = repo.observeAll().first().single { it.id == "g1" }
        assertEquals("Casa nueva", group.name)
        assertEquals(setOf("a1"), group.memberAssetIds)
        assertEquals(setOf("h3"), group.memberHoldingIds)
        assertEquals(listOf("g1"), db.savingsGoalDao().listLinkedGroupIds(goalId))

        repo.updateGroup(AccountGroup("g1", "Casa nueva", true, 0, emptySet(), emptySet()))

        val emptied = repo.observeAll().first().single { it.id == "g1" }
        assertEquals(emptySet(), emptied.memberAssetIds)
        assertEquals(emptySet(), emptied.memberHoldingIds)
        db.close()
    }

    @Test
    fun `a new group with an unknown holding is refused and nothing is written`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)

        assertFailsWith<PortfolioHoldingNotFoundException> {
            repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1", "ghost")))
        }

        assertTrue(db.accountGroupDao().listGroups().isEmpty())
        assertTrue(db.accountGroupDao().listMembers().isEmpty())
        assertTrue(db.accountGroupDao().listHoldingMembers().isEmpty())
        db.close()
    }

    @Test
    fun `a new group with an unknown account is refused and no holding member is left behind`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)

        assertFails { repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1", "ghost"), setOf("h1"))) }

        assertTrue(db.accountGroupDao().listGroups().isEmpty())
        assertTrue(db.accountGroupDao().listMembers().isEmpty())
        assertTrue(db.accountGroupDao().listHoldingMembers().isEmpty())
        db.close()
    }

    @Test
    fun `an edit with an unknown holding keeps the group exactly as it was`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1")))

        assertFailsWith<PortfolioHoldingNotFoundException> {
            repo.updateGroup(AccountGroup("g1", "Otro nombre", false, 0, setOf("a2"), setOf("h2", "ghost")))
        }

        val group = repo.observeAll().first().single { it.id == "g1" }
        assertEquals("Casa", group.name)
        assertEquals(true, group.showBalance)
        assertEquals(setOf("a1"), group.memberAssetIds)
        assertEquals(setOf("h1"), group.memberHoldingIds)
        db.close()
    }

    @Test
    fun `setting the members with an unknown holding keeps the previous members`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1")))

        assertFailsWith<PortfolioHoldingNotFoundException> { repo.setMembers("g1", setOf("a2"), setOf("ghost")) }

        val group = repo.observeAll().first().single { it.id == "g1" }
        assertEquals(setOf("a1"), group.memberAssetIds)
        assertEquals(setOf("h1"), group.memberHoldingIds)
        db.close()
    }

    @Test
    fun `deleting a group keeps the holdings and the other groups that contain them`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, emptySet(), setOf("h1")))
        repo.insertGroup(AccountGroup("g2", "Viaje", true, 1, emptySet(), setOf("h1")))

        repo.deleteGroup("g1")

        assertEquals(listOf("h1", "h2", "h3"), db.portfolioHoldingDao().list().map { it.id }.sorted())
        assertEquals(listOf(AccountGroupHoldingMemberEntity("g2", "h1")), db.accountGroupDao().listHoldingMembers())
        db.close()
    }

    @Test
    fun `the groups of a holding come back with their ids and names in the user's order`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Viaje", true, 2, emptySet(), setOf("h1")))
        repo.insertGroup(AccountGroup("g2", "Casa", true, 0, setOf("a1"), setOf("h1", "h2")))
        repo.insertGroup(AccountGroup("g3", "Sin fondos", true, 1, setOf("a1")))

        assertEquals(
            listOf(AccountGroupRef("g2", "Casa"), AccountGroupRef("g1", "Viaje")),
            repo.groupsContainingHolding("h1"),
        )
        assertEquals(listOf(AccountGroupRef("g2", "Casa")), repo.groupsContainingHolding("h2"))
        assertTrue(repo.groupsContainingHolding("h3").isEmpty())
        assertTrue(repo.groupsContainingHolding("ghost").isEmpty())
        db.close()
    }

    @Test
    fun `a holding that is deleted leaves its groups and no longer counts as a member`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repo = groupRepository(db)
        seedAccountsAndHoldings(db)
        repo.insertGroup(AccountGroup("g1", "Casa", true, 0, setOf("a1"), setOf("h1", "h2")))

        db.portfolioHoldingDao().delete("h1")

        val group = repo.observeAll().first().single { it.id == "g1" }
        assertEquals(setOf("a1"), group.memberAssetIds)
        assertEquals(setOf("h2"), group.memberHoldingIds)
        assertTrue(repo.groupsContainingHolding("h1").isEmpty())
        db.close()
    }

    /** Two accounts and three manual holdings of the default portfolio (the seeding gate creates it). */
    private suspend fun TestScope.seedAccountsAndHoldings(db: AppDatabase) {
        testSeedingGate(db).await()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.assetDao().insert(AssetEntity("a2", "CASH", "Wallet", null, 20_00, "EUR"))
        listOf("h1", "h2", "h3").forEach { id ->
            db.portfolioHoldingDao().insert(
                PortfolioHoldingEntity(
                    id, TEST_PORTFOLIO_ID, "Fondo $id", "OTHER", null, null, "MANUAL", null, null, "EUR",
                    null, null, null, null, 250_000, 1L,
                ),
            )
        }
    }

    private fun TestScope.groupRepository(db: AppDatabase, goals: SavingsGoalDataSource = db.savingsGoalDao()) =
        AccountGroupRepositoryImpl(
            db,
            db.accountGroupDao(),
            testSeedingGate(db),
            goals,
            GroupFixedClock(Instant.parse(DELETION_TIME)),
        )

    private suspend fun AppDatabase.insertGoalLinkedTo(goal: SavingsGoalEntity, groupId: String): String {
        savingsGoalDao().insertGoal(goal)
        savingsGoalDao().insertLinkedGroups(listOf(SavingsGoalGroupEntity(goal.id, groupId)))
        return goal.id
    }

    private fun goal(name: String, lifecycle: String = "OPEN") = SavingsGoalEntity(
        id = "goal-$name",
        name = name,
        targetMinor = 100_000,
        currency = "EUR",
        targetDateEpochDay = null,
        lifecycle = lifecycle,
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
