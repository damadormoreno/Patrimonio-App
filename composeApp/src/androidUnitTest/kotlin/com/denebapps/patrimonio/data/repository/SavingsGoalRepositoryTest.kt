package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.insertGoalReturningId
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalDeltaException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalNameException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalTargetException
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.SavingsGoalArithmeticOverflowException
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.TerminalSavingsGoalException
import com.denebapps.patrimonio.domain.repository.UpdateSavingsGoal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CountingClock(private val instant: Instant) : Clock {
    var calls: Int = 0
        private set

    override fun now(): Instant {
        calls += 1
        return instant
    }
}

private class FailingLinkEventSource(
    private val delegate: SavingsGoalDataSource,
) : SavingsGoalDataSource by delegate {
    override suspend fun insertLinkEvent(event: SavingsGoalLinkEventEntity): Long =
        error("simulated link-event failure")
}

private class FailingLifecycleSource(
    private val delegate: SavingsGoalDataSource,
) : SavingsGoalDataSource by delegate {
    override suspend fun updateLifecycle(goalId: String, lifecycle: String): Int = error("simulated lifecycle failure")
}

@RunWith(RobolectricTestRunner::class)
class SavingsGoalRepositoryTest {
    @Test
    fun `create persists goals linked to several accounts of any currency with one captured timestamp`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-eur", "EUR", group = "CASH")
        fixture.seedAsset("asset-usd", "USD")

        val linkedId = fixture.repository.create(command("Emergency", linkedAssetIds = setOf("asset-eur", "asset-usd")))
        val unlinkedId = fixture.repository.create(command("Travel"))

        val goals = fixture.repository.observeAll().first().associateBy { it.id }
        assertEquals("Emergency", goals.getValue(linkedId).name)
        assertEquals(setOf("asset-eur", "asset-usd"), goals.getValue(linkedId).linkedAssetIds)
        assertEquals(LocalDate(2027, 1, 2), goals.getValue(linkedId).targetDate)
        assertEquals(emptySet(), goals.getValue(unlinkedId).linkedAssetIds)
        val links = fixture.repository.observeLinkHistory(linkedId).first()
        assertEquals(listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.LINK), links.map { it.kind })
        assertEquals(listOf("asset-eur", "asset-usd"), links.map { it.toAssetId })
        assertEquals(listOf(NOW_MS, NOW_MS), links.map { it.timestampEpochMs })
        assertEquals(NOW_MS, fixture.db.savingsGoalDao().findGoal(linkedId)?.createdAtEpochMs)
        // One read per create, shared by the goal's creation time and its LINK events.
        assertEquals(2, fixture.clock.calls)
        fixture.close()
    }

    @Test
    fun `create rejects invalid name target and missing assets without rows`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-usd", "USD")

        assertFailsWith<InvalidSavingsGoalNameException> { fixture.repository.create(command("")) }
        assertFailsWith<InvalidSavingsGoalNameException> { fixture.repository.create(command(" padded ")) }
        assertFailsWith<InvalidSavingsGoalTargetException> {
            fixture.repository.create(command("Zero", targetMinor = 0))
        }
        assertFailsWith<InvalidSavingsGoalTargetException> {
            fixture.repository.create(command("Negative", targetMinor = -1))
        }
        assertFailsWith<SavingsGoalAssetNotFoundException> {
            fixture.repository.create(command("Missing", linkedAssetIds = setOf("asset-usd", "missing")))
        }

        assertEquals(emptyList(), fixture.repository.observeAll().first())
        assertTrue(fixture.db.savingsGoalDao().listAllLinkedAssets().isEmpty())
        assertEquals(0, fixture.clock.calls)
        fixture.close()
    }

    @Test
    fun `allocate overfunds and withdraws while retaining every signed event`() = runTest {
        val fixture = fixture()
        val goalId = fixture.repository.create(command("Goal", targetMinor = 100))

        fixture.repository.allocate(goalId, Money(130))
        fixture.repository.withdraw(goalId, Money(30))

        val goal = fixture.repository.observeAll().first().single()
        assertEquals(Money(100), goal.progress)
        assertTrue(goal.targetReached)
        assertEquals(SavingsGoalLifecycle.OPEN, goal.lifecycle)
        assertEquals(
            listOf(130L, -30L),
            fixture.repository.observeAllocationHistory(goalId).first().map { it.delta.minorUnits },
        )
        assertEquals(3, fixture.clock.calls)
        fixture.close()
    }

    @Test
    fun `allocation rejects zero negative progress and overflow without appending`() = runTest {
        val fixture = fixture()
        val goalId = fixture.repository.create(command("Goal", targetMinor = 1))

        assertFailsWith<InvalidSavingsGoalDeltaException> { fixture.repository.allocate(goalId, Money.ZERO) }
        assertFailsWith<InvalidSavingsGoalDeltaException> { fixture.repository.allocate(goalId, Money(-1)) }
        assertFailsWith<InvalidSavingsGoalDeltaException> { fixture.repository.withdraw(goalId, Money.ZERO) }
        assertFailsWith<InvalidSavingsGoalDeltaException> { fixture.repository.withdraw(goalId, Money(-1)) }
        assertFailsWith<NegativeSavingsGoalProgressException> { fixture.repository.withdraw(goalId, Money(1)) }
        fixture.repository.allocate(goalId, Money(Long.MAX_VALUE))
        assertFailsWith<SavingsGoalArithmeticOverflowException> { fixture.repository.allocate(goalId, Money(1)) }

        assertEquals(
            listOf(Long.MAX_VALUE),
            fixture.repository.observeAllocationHistory(goalId).first().map { it.delta.minorUnits },
        )
        fixture.close()
    }

    @Test
    fun `update adds and removes accounts with one event each without changing funded progress`() = runTest {
        val fixture = fixture()
        listOf("asset-1", "asset-2", "asset-3").forEach { fixture.seedAsset(it, "EUR") }
        val goalId = fixture.repository.create(command("Goal"))
        fixture.repository.allocate(goalId, Money(400))

        fixture.repository.update(goalId, details(linkedAssetIds = setOf("asset-1", "asset-2")))
        fixture.repository.update(goalId, details(linkedAssetIds = setOf("asset-2", "asset-3")))
        val moved = fixture.repository.observeAll().first().single()
        assertEquals(setOf("asset-2", "asset-3"), moved.linkedAssetIds)
        assertEquals(Money(400), moved.progress)
        fixture.repository.update(goalId, details())

        val goal = fixture.repository.observeAll().first().single()
        assertEquals(Money(400), goal.progress)
        assertEquals(emptySet(), goal.linkedAssetIds)
        assertEquals(
            listOf(400L),
            fixture.repository.observeAllocationHistory(goalId).first().map { it.delta.minorUnits },
        )
        val links = fixture.repository.observeLinkHistory(goalId).first()
        val link = SavingsGoalLinkEventKind.LINK
        val unlink = SavingsGoalLinkEventKind.UNLINK
        assertEquals(listOf(link, link, unlink, link, unlink, unlink), links.map { it.kind })
        assertEquals(listOf(null, null, "asset-1", null, "asset-2", "asset-3"), links.map { it.fromAssetId })
        assertEquals(listOf("asset-1", "asset-2", null, "asset-3", null, null), links.map { it.toAssetId })
        assertTrue(fixture.db.savingsGoalDao().listAllLinkedAssets().isEmpty())
        fixture.close()
    }

    @Test
    fun `create links a goal to a persisted group of any currency and audits it`() = runTest {
        val fixture = fixture()
        fixture.seedGroup("g1")

        val goalId = fixture.repository.create(command("Colchón", linkedGroupId = "g1"))

        val goal = fixture.repository.observeAll().first().single()
        assertEquals("g1", goal.linkedGroupId)
        assertEquals(emptySet(), goal.linkedAssetIds)
        val link = fixture.repository.observeLinkHistory(goalId).first().single()
        assertEquals(SavingsGoalLinkEventKind.LINK, link.kind)
        assertEquals("g1", link.toGroupId)
        assertNull(link.fromGroupId)
        assertNull(link.fromAssetId)
        assertNull(link.toAssetId)
        fixture.close()
    }

    @Test
    fun `create rejects a missing group a builtin group and a double link without rows`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-eur", "EUR")
        fixture.seedGroup("g1")

        assertFailsWith<SavingsGoalGroupNotFoundException> {
            fixture.repository.create(command("Missing", linkedGroupId = "missing"))
        }
        assertFailsWith<SavingsGoalBuiltinGroupException> {
            fixture.repository.create(command("Builtin", linkedGroupId = AccountGroup.ALL_ACCOUNTS_ID))
        }
        assertFailsWith<IllegalArgumentException> {
            command("Both", linkedAssetIds = setOf("asset-eur"), linkedGroupId = "g1")
        }

        assertEquals(emptyList(), fixture.repository.observeAll().first())
        assertEquals(0, fixture.clock.calls)
        fixture.close()
    }

    @Test
    fun `links move between accounts and groups keeping them exclusive and auditing both sides`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        fixture.seedGroup("g1")
        fixture.seedGroup("g2")
        val goalId = fixture.repository.create(command("Goal"))
        fixture.repository.allocate(goalId, Money(400))

        fixture.repository.update(goalId, details(linkedGroupId = "g1"))
        assertEquals("g1", fixture.repository.observeAll().first().single().linkedGroupId)
        fixture.repository.update(goalId, details(linkedGroupId = "g2"))
        fixture.repository.update(goalId, details(linkedAssetIds = setOf("asset-1")))
        val onAsset = fixture.repository.observeAll().first().single()
        assertEquals(setOf("asset-1"), onAsset.linkedAssetIds)
        assertNull(onAsset.linkedGroupId)
        fixture.repository.update(goalId, details(linkedGroupId = "g1"))
        val onGroup = fixture.repository.observeAll().first().single()
        assertEquals("g1", onGroup.linkedGroupId)
        assertEquals(emptySet(), onGroup.linkedAssetIds)
        fixture.repository.update(goalId, details())

        val goal = fixture.repository.observeAll().first().single()
        assertNull(goal.linkedGroupId)
        assertEquals(emptySet(), goal.linkedAssetIds)
        assertEquals(Money(400), goal.progress)
        val links = fixture.repository.observeLinkHistory(goalId).first()
        val link = SavingsGoalLinkEventKind.LINK
        val unlink = SavingsGoalLinkEventKind.UNLINK
        assertEquals(
            listOf(link, SavingsGoalLinkEventKind.RELINK, unlink, link, unlink, link, unlink),
            links.map { it.kind },
        )
        assertEquals(listOf(null, "g1", "g2", null, null, null, "g1"), links.map { it.fromGroupId })
        assertEquals(listOf("g1", "g2", null, null, null, "g1", null), links.map { it.toGroupId })
        assertEquals(listOf(null, null, null, null, "asset-1", null, null), links.map { it.fromAssetId })
        assertEquals(listOf(null, null, null, "asset-1", null, null, null), links.map { it.toAssetId })
        fixture.close()
    }

    @Test
    fun `target reached remains open and close preserves progress and reservation`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        val goalId = fixture.repository.create(command("Goal", targetMinor = 100, linkedAssetIds = setOf("asset-1")))
        fixture.repository.allocate(goalId, Money(100))

        val reached = fixture.repository.observeAll().first().single()
        assertTrue(reached.targetReached)
        assertEquals(SavingsGoalLifecycle.OPEN, reached.lifecycle)
        fixture.repository.close(goalId)

        val closed = fixture.repository.observeAll().first().single()
        assertEquals(SavingsGoalLifecycle.CLOSED, closed.lifecycle)
        assertEquals(Money(100), closed.progress)
        assertEquals(setOf("asset-1"), closed.linkedAssetIds)
        fixture.close()
    }

    @Test
    fun `update edits the details and moves the link with audit events`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        fixture.seedGroup("g1")
        val goalId = fixture.repository.create(command("Viaje", targetMinor = 1_000, linkedAssetIds = setOf("asset-1")))
        fixture.repository.allocate(goalId, Money(300))

        fixture.repository.update(
            goalId,
            UpdateSavingsGoal(
                name = "Japón",
                targetAmount = Money(5_000),
                targetDate = LocalDate(2028, 3, 1),
                linkedAssetIds = emptySet(),
                linkedGroupId = "g1",
            ),
        )

        val goal = fixture.repository.observeAll().first().single()
        assertEquals("Japón", goal.name)
        assertEquals(CurrencyAmount(Money(5_000), Currency.EUR), goal.target)
        assertEquals(LocalDate(2028, 3, 1), goal.targetDate)
        assertEquals(emptySet(), goal.linkedAssetIds)
        assertEquals("g1", goal.linkedGroupId)
        assertEquals(Money(300), goal.progress)
        val (unlinked, linked) = fixture.repository.observeLinkHistory(goalId).first().takeLast(2)
        assertEquals(SavingsGoalLinkEventKind.UNLINK, unlinked.kind)
        assertEquals("asset-1", unlinked.fromAssetId)
        assertEquals(SavingsGoalLinkEventKind.LINK, linked.kind)
        assertEquals("g1", linked.toGroupId)

        // Unchanged link: no new event. Removing the link: UNLINK.
        val unchanged = details(name = "Japón", targetMinor = 6_000, linkedGroupId = "g1")
        fixture.repository.update(goalId, unchanged)
        assertEquals(3, fixture.repository.observeLinkHistory(goalId).first().size)
        fixture.repository.update(goalId, unchanged.copy(linkedGroupId = null))
        assertEquals(SavingsGoalLinkEventKind.UNLINK, fixture.repository.observeLinkHistory(goalId).first().last().kind)
        fixture.close()
    }

    @Test
    fun `update rejects invalid details, missing links and closed goals without changes`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-usd", "USD")
        val goalId = fixture.repository.create(command("Viaje", targetMinor = 1_000))
        val valid = details(name = "Viaje", targetMinor = 1_000)

        assertFailsWith<InvalidSavingsGoalNameException> { fixture.repository.update(goalId, valid.copy(name = " ")) }
        assertFailsWith<InvalidSavingsGoalTargetException> {
            fixture.repository.update(goalId, valid.copy(targetAmount = Money.ZERO))
        }
        assertFailsWith<SavingsGoalAssetNotFoundException> {
            fixture.repository.update(goalId, valid.copy(name = "Otro", linkedAssetIds = setOf("asset-usd", "missing")))
        }
        assertFailsWith<SavingsGoalGroupNotFoundException> {
            fixture.repository.update(goalId, valid.copy(name = "Otro", linkedGroupId = "missing"))
        }
        assertFailsWith<SavingsGoalBuiltinGroupException> {
            fixture.repository.update(goalId, valid.copy(linkedGroupId = AccountGroup.ALL_ACCOUNTS_ID))
        }
        // A failed link rolls the whole update back, including the name.
        val goal = fixture.repository.observeAll().first().single()
        assertEquals("Viaje", goal.name)
        assertEquals(emptySet(), goal.linkedAssetIds)
        assertEquals(emptyList(), fixture.repository.observeLinkHistory(goalId).first())

        fixture.repository.cancel(goalId)
        assertFailsWith<TerminalSavingsGoalException> { fixture.repository.update(goalId, valid) }
        assertFailsWith<SavingsGoalNotFoundException> { fixture.repository.update("missing", valid) }
        fixture.close()
    }

    @Test
    fun `delete removes an open or cancelled goal with its whole history`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        val openId = fixture.repository.create(command("Open", linkedAssetIds = setOf("asset-1")))
        val cancelledId = fixture.repository.create(command("Cancelled"))
        val keptId = fixture.repository.create(command("Kept"))
        fixture.repository.allocate(openId, Money(250))
        fixture.repository.allocate(cancelledId, Money(100))
        fixture.repository.cancel(cancelledId)

        fixture.repository.delete(openId)
        fixture.repository.delete(cancelledId)

        assertEquals(listOf(keptId), fixture.repository.observeAll().first().map { it.id })
        assertTrue(fixture.db.savingsGoalDao().listAllAllocationEvents().isEmpty())
        assertTrue(fixture.db.savingsGoalDao().listAllLinkEvents().isEmpty())
        assertTrue(fixture.db.savingsGoalDao().listAllLinkedAssets().isEmpty())
        // The linked asset stays: deleting a goal never touches what it pointed at.
        assertEquals("asset-1", fixture.db.assetDao().find("asset-1")?.id)
        assertFailsWith<SavingsGoalNotFoundException> { fixture.repository.delete(openId) }
        fixture.close()
    }

    @Test
    fun `cancel funded appends exact release while empty cancel writes no zero event`() = runTest {
        val fixture = fixture()
        val fundedId = fixture.repository.create(command("Funded"))
        val emptyId = fixture.repository.create(command("Empty"))
        fixture.repository.allocate(fundedId, Money(250))

        fixture.repository.cancel(fundedId)
        fixture.repository.cancel(emptyId)

        val goals = fixture.repository.observeAll().first().associateBy { it.id }
        assertEquals(SavingsGoalLifecycle.CANCELLED, goals.getValue(fundedId).lifecycle)
        assertEquals(Money.ZERO, goals.getValue(fundedId).progress)
        assertEquals(
            listOf(250L, -250L),
            fixture.repository.observeAllocationHistory(fundedId).first().map { it.delta.minorUnits },
        )
        assertEquals(SavingsGoalLifecycle.CANCELLED, goals.getValue(emptyId).lifecycle)
        assertEquals(emptyList(), fixture.repository.observeAllocationHistory(emptyId).first())
        fixture.close()
    }

    @Test
    fun `closed and cancelled goals reject every public mutation without changes`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        fixture.seedAsset("asset-2", "EUR")
        val closedId = fixture.repository.create(command("Closed", linkedAssetIds = setOf("asset-1")))
        val cancelledId = fixture.repository.create(command("Cancelled", linkedAssetIds = setOf("asset-1")))
        fixture.repository.close(closedId)
        fixture.repository.cancel(cancelledId)

        assertTerminalMutations(fixture.repository, closedId)
        assertTerminalMutations(fixture.repository, cancelledId)

        assertEquals(emptyList(), fixture.repository.observeAllocationHistory(closedId).first())
        assertEquals(emptyList(), fixture.repository.observeAllocationHistory(cancelledId).first())
        assertEquals(1, fixture.repository.observeLinkHistory(closedId).first().size)
        assertEquals(1, fixture.repository.observeLinkHistory(cancelledId).first().size)
        fixture.close()
    }

    @Test
    fun `linked create rolls back goal and emits nothing when link event insert fails`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        val failingRepository = fixture.repository(FailingLinkEventSource(fixture.db.savingsGoalDao()))
        val emissions = failingRepository.observeAll().produceIn(backgroundScope)
        assertEquals(emptyList(), emissions.receive())

        assertFailsWith<IllegalStateException> {
            failingRepository.create(command("Goal", linkedAssetIds = setOf("asset-1")))
        }

        assertEquals(emptyList(), fixture.repository.observeAll().first())
        assertTrue(fixture.db.savingsGoalDao().listAllLinkedAssets().isEmpty())
        assertNull(receiveOrNull(emissions))
        fixture.close()
    }

    @Test
    fun `cancel rolls back release event when lifecycle update fails`() = runTest {
        val fixture = fixture()
        val goalId = fixture.repository.create(command("Goal"))
        fixture.repository.allocate(goalId, Money(200))
        val failingRepository = fixture.repository(FailingLifecycleSource(fixture.db.savingsGoalDao()))

        assertFailsWith<IllegalStateException> { failingRepository.cancel(goalId) }

        val goal = fixture.repository.observeAll().first().single()
        assertEquals(SavingsGoalLifecycle.OPEN, goal.lifecycle)
        assertEquals(Money(200), goal.progress)
        assertEquals(
            listOf(200L),
            fixture.repository.observeAllocationHistory(goalId).first().map { it.delta.minorUnits },
        )
        fixture.close()
    }

    @Test
    fun `reactive success emits one complete allocation snapshot`() = runTest {
        val fixture = fixture()
        val goalId = fixture.repository.create(command("Goal"))
        val emissions = fixture.repository.observeAll().produceIn(backgroundScope)
        assertEquals(Money.ZERO, emissions.receive().single().progress)

        fixture.repository.allocate(goalId, Money(75))

        val updated = emissions.receive().single()
        assertEquals(Money(75), updated.progress)
        assertEquals(
            listOf(75L),
            fixture.repository.observeAllocationHistory(goalId).first().map { it.delta.minorUnits },
        )
        fixture.close()
    }

    @Test
    fun `reactive history flows emit complete appended allocation and link events`() = runTest {
        val fixture = fixture()
        fixture.seedAsset("asset-1", "EUR")
        val goalId = fixture.repository.create(command("Goal"))
        val allocations = fixture.repository.observeAllocationHistory(goalId).produceIn(backgroundScope)
        val links = fixture.repository.observeLinkHistory(goalId).produceIn(backgroundScope)
        assertEquals(emptyList(), allocations.receive())
        assertEquals(emptyList(), links.receive())

        fixture.repository.allocate(goalId, Money(75))
        fixture.repository.update(goalId, details(name = "Goal", linkedAssetIds = setOf("asset-1")))

        assertEquals(listOf(75L), allocations.receive().map { it.delta.minorUnits })
        assertEquals(listOf(SavingsGoalLinkEventKind.LINK), links.receive().map { it.kind })
        fixture.close()
    }

    @Test
    fun `goal commands leave asset rows and net-worth snapshots unchanged`() = runTest {
        val fixture = fixture()
        fixture.seedingGate.await()
        fixture.seedAsset("asset-1", "EUR", amountMinor = 50_000)
        fixture.db.netWorthDao().upsert(NetWorthSnapshotEntity("2026-07", 50_000, 0))
        val assetsBefore = fixture.db.assetDao().list()
        val snapshotBefore = fixture.db.netWorthDao().find("2026-07")

        val goalId = fixture.repository.create(command("Goal", linkedAssetIds = setOf("asset-1")))
        fixture.repository.allocate(goalId, Money(10_000))
        fixture.repository.update(goalId, details(name = "Goal"))

        assertEquals(assetsBefore, fixture.db.assetDao().list())
        assertEquals(snapshotBefore, fixture.db.netWorthDao().find("2026-07"))
        fixture.close()
    }

    @Test
    fun `checked mapping rejects zero and overflowing persisted allocation relations`() = runTest {
        val zeroFixture = fixture()
        val zeroGoalId = zeroFixture.db.savingsGoalDao().insertGoalReturningId(goalEntity("Zero"))
        zeroFixture.db.savingsGoalDao().insertAllocationEvent(
            SavingsGoalAllocationEventEntity(0, zeroGoalId, 0, NOW_MS),
        )
        assertFailsWith<InvalidSavingsGoalDeltaException> { zeroFixture.repository.observeAll().first() }
        zeroFixture.close()

        val overflowFixture = fixture()
        val overflowGoalId = overflowFixture.db.savingsGoalDao().insertGoalReturningId(goalEntity("Overflow"))
        overflowFixture.db.savingsGoalDao().insertAllocationEvent(
            SavingsGoalAllocationEventEntity(0, overflowGoalId, Long.MAX_VALUE, NOW_MS),
        )
        overflowFixture.db.savingsGoalDao().insertAllocationEvent(
            SavingsGoalAllocationEventEntity(0, overflowGoalId, 1, NOW_MS + 1),
        )
        assertFailsWith<SavingsGoalArithmeticOverflowException> { overflowFixture.repository.observeAll().first() }
        overflowFixture.close()
    }

    private suspend fun assertTerminalMutations(repository: SavingsGoalRepository, goalId: String) {
        val commands: List<suspend () -> Unit> = listOf(
            { repository.allocate(goalId, Money(1)) },
            { repository.withdraw(goalId, Money(1)) },
            { repository.update(goalId, details(linkedAssetIds = setOf("asset-2"))) },
            { repository.update(goalId, details()) },
            { repository.close(goalId) },
            { repository.cancel(goalId) },
        )
        commands.forEach { command -> assertFailsWith<TerminalSavingsGoalException> { command() } }
    }

    private fun TestScope.fixture(): Fixture {
        val db = buildInMemoryTestDatabase()
        val clock = CountingClock(Instant.parse(NOW))
        val gate = testSeedingGate(db)
        return Fixture(db, gate, clock)
    }

    private fun command(
        name: String,
        targetMinor: Long = 1_000,
        linkedAssetIds: Set<String> = emptySet(),
        linkedGroupId: String? = null,
    ) = CreateSavingsGoal(
        name = name,
        target = CurrencyAmount(Money(targetMinor), Currency.EUR),
        targetDate = LocalDate(2027, 1, 2),
        linkedAssetIds = linkedAssetIds,
        linkedGroupId = linkedGroupId,
    )

    private fun details(
        name: String = "Goal",
        targetMinor: Long = 1_000,
        linkedAssetIds: Set<String> = emptySet(),
        linkedGroupId: String? = null,
    ) = UpdateSavingsGoal(name, Money(targetMinor), null, linkedAssetIds, linkedGroupId)

    private fun goalEntity(name: String) = SavingsGoalEntity(
        id = "goal-$name",
        name = name,
        targetMinor = 1_000,
        currency = "EUR",
        targetDateEpochDay = null,
        lifecycle = "OPEN",
        createdAtEpochMs = NOW_MS,
    )

    private suspend fun receiveOrNull(channel: ReceiveChannel<*>) =
        withContext(Dispatchers.Default) { withTimeoutOrNull(NO_EMISSION_TIMEOUT_MS) { channel.receive() } }

    private data class Fixture(
        val db: AppDatabase,
        val seedingGate: SeedingGate,
        val clock: CountingClock,
    ) {
        val repository: SavingsGoalRepository = repository(db.savingsGoalDao())

        fun repository(source: SavingsGoalDataSource): SavingsGoalRepository =
            SavingsGoalRepositoryImpl(db, source, db.assetDao(), seedingGate, clock)

        suspend fun seedAsset(id: String, currency: String, amountMinor: Long = 50_000, group: String = "BANK") {
            db.assetDao().insert(AssetEntity(id, group, id, null, amountMinor, currency))
        }

        suspend fun seedGroup(id: String) {
            db.accountGroupDao().insertGroup(AccountGroupEntity(id, id, true, 0))
        }

        fun close() = db.close()
    }

    companion object {
        private const val NOW = "2026-07-13T12:00:00Z"
        private const val NOW_MS = 1_783_944_000_000L
        private const val NO_EMISSION_TIMEOUT_MS = 250L
    }
}
