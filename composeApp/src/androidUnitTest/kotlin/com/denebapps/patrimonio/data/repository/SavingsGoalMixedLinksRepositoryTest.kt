package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.UpdateSavingsGoal
import com.denebapps.patrimonio.testing.FakeFundamentalsSource
import com.denebapps.patrimonio.testing.FakeQuoteSource
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class MixedLinksClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Goals that follow any mix of accounts, portfolio products and groups: persistence, audit events and deletions. */
@RunWith(RobolectricTestRunner::class)
class SavingsGoalMixedLinksRepositoryTest {
    @Test
    fun `create stores accounts products and groups together and audits each link`() = runTest {
        val fixture = fixture()

        val goalId = fixture.goals.create(
            command(setOf("a1", "a2"), setOf("h1"), setOf("g1", "g2")),
        )

        val goal = fixture.goals.observeAll().first().single()
        assertEquals(setOf("a1", "a2"), goal.linkedAssetIds)
        assertEquals(setOf("h1"), goal.linkedHoldingIds)
        assertEquals(setOf("g1", "g2"), goal.linkedGroupIds)
        val events = fixture.goals.observeLinkHistory(goalId).first()
        assertTrue(events.all { it.kind == SavingsGoalLinkEventKind.LINK })
        assertEquals(listOf("a1", "a2", null, null, null), events.map { it.toAssetId })
        assertEquals(listOf(null, null, "h1", null, null), events.map { it.toHoldingId })
        assertEquals(listOf(null, null, null, "g1", "g2"), events.map { it.toGroupId })
        assertEquals(setOf(NOW_MS), events.map { it.timestampEpochMs }.toSet())
        fixture.close()
    }

    @Test
    fun `create with an unknown account product or group or the builtin group writes nothing`() = runTest {
        val fixture = fixture()

        assertFailsWith<SavingsGoalAssetNotFoundException> {
            fixture.goals.create(command(setOf("a1", "ghost"), setOf("h1"), setOf("g1")))
        }
        assertFailsWith<SavingsGoalHoldingNotFoundException> {
            fixture.goals.create(command(setOf("a1"), setOf("h1", "ghost"), setOf("g1")))
        }
        assertFailsWith<SavingsGoalGroupNotFoundException> {
            fixture.goals.create(command(setOf("a1"), setOf("h1"), setOf("g1", "ghost")))
        }
        assertFailsWith<SavingsGoalBuiltinGroupException> {
            fixture.goals.create(command(setOf("a1"), setOf("h1"), setOf(AccountGroup.ALL_ACCOUNTS_ID)))
        }

        assertEquals(emptyList(), fixture.db.savingsGoalDao().listAllGoals())
        assertEquals(emptyList(), fixture.db.savingsGoalDao().listAllLinkedAssets())
        assertEquals(emptyList(), fixture.db.savingsGoalDao().listAllLinkedHoldings())
        assertEquals(emptyList(), fixture.db.savingsGoalDao().listAllLinkedGroups())
        assertEquals(emptyList(), fixture.db.savingsGoalDao().listAllLinkEvents())
        fixture.close()
    }

    @Test
    fun `update diffs every kind of link and audits removals before additions`() = runTest {
        val fixture = fixture()
        val goalId = fixture.goals.create(command(setOf("a1"), setOf("h1"), setOf("g1")))

        fixture.goals.update(goalId, details(setOf("a2"), setOf("h2"), setOf("g2")))

        val goal = fixture.goals.observeAll().first().single()
        assertEquals(setOf("a2"), goal.linkedAssetIds)
        assertEquals(setOf("h2"), goal.linkedHoldingIds)
        assertEquals(setOf("g2"), goal.linkedGroupIds)
        val events = fixture.goals.observeLinkHistory(goalId).first().drop(3)
        assertEquals(
            List(3) { SavingsGoalLinkEventKind.UNLINK } + List(3) { SavingsGoalLinkEventKind.LINK },
            events.map { it.kind },
        )
        assertEquals(listOf("a1", null, null, null, null, null), events.map { it.fromAssetId })
        assertEquals(listOf(null, "h1", null, null, null, null), events.map { it.fromHoldingId })
        assertEquals(listOf(null, null, "g1", null, null, null), events.map { it.fromGroupId })
        assertEquals(listOf(null, null, null, "a2", null, null), events.map { it.toAssetId })
        assertEquals(listOf(null, null, null, null, "h2", null), events.map { it.toHoldingId })
        assertEquals(listOf(null, null, null, null, null, "g2"), events.map { it.toGroupId })
        fixture.close()
    }

    @Test
    fun `update keeping or adding links only audits the difference`() = runTest {
        val fixture = fixture()
        val goalId = fixture.goals.create(command(setOf("a1"), emptySet(), setOf("g1")))

        fixture.goals.update(goalId, details(setOf("a1"), emptySet(), setOf("g1")))
        assertEquals(2, fixture.goals.observeLinkHistory(goalId).first().size)

        fixture.goals.update(goalId, details(setOf("a1"), setOf("h1"), setOf("g1", "g2")))

        val goal = fixture.goals.observeAll().first().single()
        assertEquals(setOf("g1", "g2"), goal.linkedGroupIds)
        assertEquals(setOf("h1"), goal.linkedHoldingIds)
        val added = fixture.goals.observeLinkHistory(goalId).first().drop(2)
        assertEquals(listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.LINK), added.map { it.kind })
        assertEquals(listOf("h1", null), added.map { it.toHoldingId })
        assertEquals(listOf(null, "g2"), added.map { it.toGroupId })

        fixture.goals.update(goalId, details(emptySet(), emptySet(), emptySet()))

        val unlinked = fixture.goals.observeAll().first().single()
        assertEquals(emptySet(), unlinked.linkedAssetIds + unlinked.linkedHoldingIds + unlinked.linkedGroupIds)
        fixture.close()
    }

    @Test
    fun `swapping a group is an unlink and a link now that a goal may follow several groups`() = runTest {
        val fixture = fixture()
        val goalId = fixture.goals.create(command(emptySet(), emptySet(), setOf("g1")))

        fixture.goals.update(goalId, details(emptySet(), emptySet(), setOf("g2")))

        val events = fixture.goals.observeLinkHistory(goalId).first()
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.UNLINK, SavingsGoalLinkEventKind.LINK),
            events.map { it.kind },
        )
        assertEquals(listOf(null, "g1", null), events.map { it.fromGroupId })
        assertEquals(listOf("g1", null, "g2"), events.map { it.toGroupId })
        fixture.close()
    }

    @Test
    fun `a failed update rolls back the details and every link change`() = runTest {
        val fixture = fixture()
        val goalId = fixture.goals.create(command(setOf("a1"), setOf("h1"), setOf("g1")))

        assertFailsWith<SavingsGoalHoldingNotFoundException> {
            fixture.goals.update(goalId, details(setOf("a2"), setOf("ghost"), setOf("g2")).copy(name = "Otro"))
        }
        assertFailsWith<SavingsGoalGroupNotFoundException> {
            fixture.goals.update(goalId, details(setOf("a2"), setOf("h2"), setOf("ghost")).copy(name = "Otro"))
        }
        assertFailsWith<SavingsGoalBuiltinGroupException> {
            fixture.goals.update(goalId, details(setOf("a2"), setOf("h2"), setOf(AccountGroup.ALL_ACCOUNTS_ID)))
        }

        val goal = fixture.goals.observeAll().first().single()
        assertEquals("Goal", goal.name)
        assertEquals(setOf("a1"), goal.linkedAssetIds)
        assertEquals(setOf("h1"), goal.linkedHoldingIds)
        assertEquals(setOf("g1"), goal.linkedGroupIds)
        assertEquals(3, fixture.goals.observeLinkHistory(goalId).first().size)
        fixture.close()
    }

    @Test
    fun `deleting a product writes HOLDING_DELETED for the goals that follow it directly and keeps the rest`() =
        runTest {
            val fixture = fixture()
            fixture.groups.setMembers("g1", setOf("a1"), setOf("h1"))
            val direct = fixture.goals.create(command(setOf("a1"), setOf("h1", "h2"), emptySet()))
            val viaGroup = fixture.goals.create(command(emptySet(), emptySet(), setOf("g1")))

            fixture.portfolio.delete("h1")

            val goals = fixture.goals.observeAll().first().associateBy { it.id }
            assertEquals(setOf("h2"), goals.getValue(direct).linkedHoldingIds)
            assertEquals(setOf("a1"), goals.getValue(direct).linkedAssetIds)
            assertEquals(setOf("g1"), goals.getValue(viaGroup).linkedGroupIds)
            val deleted = fixture.goals.observeLinkHistory(direct).first().last()
            assertEquals(SavingsGoalLinkEventKind.HOLDING_DELETED, deleted.kind)
            assertEquals("h1", deleted.fromHoldingId)
            assertEquals(NOW_MS, deleted.timestampEpochMs)
            // The goal that only follows the group has nothing to unlink: the product just left the group.
            assertEquals(
                listOf(SavingsGoalLinkEventKind.LINK),
                fixture.goals.observeLinkHistory(viaGroup).first().map { it.kind },
            )
            fixture.close()
        }

    @Test
    fun `deleting a group writes GROUP_DELETED for every goal that follows it and keeps its other groups`() = runTest {
        val fixture = fixture()
        val both = fixture.goals.create(command(setOf("a1"), setOf("h1"), setOf("g1", "g2")))
        val onlyFirst = fixture.goals.create(command(emptySet(), emptySet(), setOf("g1")))
        val elsewhere = fixture.goals.create(command(emptySet(), emptySet(), setOf("g2")))

        fixture.groups.deleteGroup("g1")

        val goals = fixture.goals.observeAll().first().associateBy { it.id }
        assertEquals(setOf("g2"), goals.getValue(both).linkedGroupIds)
        assertEquals(emptySet(), goals.getValue(onlyFirst).linkedGroupIds)
        assertEquals(setOf("g2"), goals.getValue(elsewhere).linkedGroupIds)
        assertEquals(setOf("a1"), goals.getValue(both).linkedAssetIds)
        assertEquals(setOf("h1"), goals.getValue(both).linkedHoldingIds)
        listOf(both, onlyFirst).forEach { goalId ->
            val deleted = fixture.goals.observeLinkHistory(goalId).first().last()
            assertEquals(SavingsGoalLinkEventKind.GROUP_DELETED, deleted.kind)
            assertEquals("g1", deleted.fromGroupId)
            assertEquals(NOW_MS, deleted.timestampEpochMs)
        }
        assertEquals(
            0,
            fixture.goals.observeLinkHistory(
                elsewhere,
            ).first().count { it.kind == SavingsGoalLinkEventKind.GROUP_DELETED },
        )
        fixture.close()
    }

    @Test
    fun `link events of a deleted product keep their ids after the product is gone`() = runTest {
        val fixture = fixture()
        val goalId = fixture.goals.create(command(emptySet(), setOf("h1"), emptySet()))

        fixture.portfolio.delete("h1")

        val events: List<SavingsGoalLinkEvent> = fixture.goals.observeLinkHistory(goalId).first()
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.HOLDING_DELETED),
            events.map { it.kind },
        )
        assertEquals(listOf("h1", null), events.map { it.toHoldingId })
        assertEquals(listOf(null, "h1"), events.map { it.fromHoldingId })
        fixture.close()
    }

    private fun command(assetIds: Set<String>, holdingIds: Set<String>, groupIds: Set<String>) = CreateSavingsGoal(
        name = "Goal",
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        linkedAssetIds = assetIds,
        linkedHoldingIds = holdingIds,
        linkedGroupIds = groupIds,
    )

    private fun details(assetIds: Set<String>, holdingIds: Set<String>, groupIds: Set<String>) = UpdateSavingsGoal(
        name = "Goal",
        targetAmount = Money(100_000),
        targetDate = null,
        linkedAssetIds = assetIds,
        linkedHoldingIds = holdingIds,
        linkedGroupIds = groupIds,
    )

    /** Two accounts, two manual products of the default portfolio and two groups. */
    private suspend fun TestScope.fixture(): Fixture {
        val db = buildInMemoryTestDatabase()
        val gate = testSeedingGate(db)
        gate.await()
        val clock = MixedLinksClock(Instant.fromEpochMilliseconds(NOW_MS))
        listOf("a1", "a2").forEach { db.assetDao().insert(AssetEntity(it, "BANK", it, null, 10_000, "EUR")) }
        listOf("h1", "h2").forEach { id ->
            db.portfolioHoldingDao().insert(
                PortfolioHoldingEntity(
                    id, TEST_PORTFOLIO_ID, "Fondo $id", "OTHER", null, null, "MANUAL", null, null, "EUR",
                    null, null, null, null, 250_000, 1L,
                ),
            )
        }
        listOf("g1", "g2").forEach { db.accountGroupDao().insertGroup(AccountGroupEntity(it, it, true, 0)) }
        val snapshotUpserter = NetWorthSnapshotUpserter(
            db.assetDao(),
            db.liabilityDao(),
            db.portfolioHoldingDao(),
            db.fxRateDao(),
            db.netWorthDao(),
            clock,
        ) { TimeZone.UTC }
        return Fixture(
            db = db,
            goals = SavingsGoalRepositoryImpl(db, db.savingsGoalDao(), db.assetDao(), gate, clock),
            groups = AccountGroupRepositoryImpl(db, db.accountGroupDao(), gate, db.savingsGoalDao(), clock),
            portfolio = PortfolioRepositoryImpl(
                db,
                db.portfolioHoldingDao(),
                FakeQuoteSource(),
                snapshotUpserter,
                gate,
                clock,
                FakeFundamentalsSource(),
            ),
        )
    }

    private class Fixture(
        val db: AppDatabase,
        val goals: SavingsGoalRepository,
        val groups: AccountGroupRepositoryImpl,
        val portfolio: PortfolioRepositoryImpl,
    ) {
        fun close() = db.close()
    }

    private companion object {
        const val NOW_MS = 1_783_944_000_000L
    }
}
