package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.dao.AssetDao
import com.denebapps.patrimonio.data.db.dao.SavingsGoalDataSource
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.AssetNotFoundException
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
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
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AssetCountingClock(private val instant: Instant) : Clock {
    var calls: Int = 0
        private set

    override fun now(): Instant {
        calls += 1
        return instant
    }
}

private class AssetFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private class DeleteFailingAssetDao(
    private val delegate: AssetDao,
) : AssetDao by delegate {
    override suspend fun deleteIfUnlinked(id: String): Int = error("simulated asset-delete failure")
}

private class DeleteFailingGoalSource(
    private val delegate: SavingsGoalDataSource,
    private val fail: DeleteFailure,
) : SavingsGoalDataSource by delegate {
    override suspend fun insertLinkEvent(event: SavingsGoalLinkEventEntity): Long {
        if (fail == DeleteFailure.LINK_EVENT) error("simulated link-event failure")
        return delegate.insertLinkEvent(event)
    }

    override suspend fun clearLinkedAsset(assetId: String): Int {
        if (fail == DeleteFailure.CLEAR_LINKS) error("simulated clear-links failure")
        return delegate.clearLinkedAsset(assetId)
    }
}

private enum class DeleteFailure { LINK_EVENT, CLEAR_LINKS, DELETE, AFTER_SNAPSHOT }

@RunWith(RobolectricTestRunner::class)
class AssetRepositoryTest {
    @Test
    fun `a linked asset can change currency and stays linked`() = runTest {
        val fixture = fixture()
        fixture.assetRepository.insert(asset(name = "Original", amountMinor = 50_000))
        val goalId = fixture.goalRepository.create(goal(ASSET_ID))
        val linksBefore = fixture.goalRepository.observeLinkHistory(goalId).first()

        fixture.assetRepository.update(asset(name = "Dollars", amountMinor = 60_000, currency = Currency.USD))

        assertEquals("USD", fixture.db.assetDao().find(ASSET_ID)?.currency)
        assertEquals(setOf(ASSET_ID), fixture.goalRepository.observeAll().first().single().linkedAssetIds)
        assertEquals(linksBefore, fixture.goalRepository.observeLinkHistory(goalId).first())
        fixture.close()
    }

    @Test
    fun `currency change succeeds and missing asset has a distinct typed failure`() = runTest {
        val fixture = fixture()
        fixture.assetRepository.insert(asset())

        fixture.assetRepository.update(asset(currency = Currency.USD))

        assertEquals("USD", fixture.db.assetDao().find(ASSET_ID)?.currency)
        assertEquals(46_000, fixture.db.netWorthDao().find(CURRENT_MONTH)?.assetsMinor)
        assertFailsWith<AssetNotFoundException> { fixture.assetRepository.update(asset(id = "missing")) }
        assertFailsWith<AssetNotFoundException> { fixture.assetRepository.deleteById("missing") }
        fixture.close()
    }

    @Test
    fun `delete audits every open and closed current link while preserving goals ledgers and valid group cascade`() =
        runTest {
            val fixture = fixture()
            fixture.seedDeletionScenario()
            val clockCallsBefore = fixture.deletionClock.calls

            fixture.assetRepository.deleteById(ASSET_ID)

            assertNull(fixture.db.assetDao().find(ASSET_ID))
            val goals = fixture.goalRepository.observeAll().first().associateBy { it.name }
            assertEquals(setOf(SURVIVOR_ID), goals.getValue("Open").linkedAssetIds)
            assertEquals(emptySet(), goals.getValue("Closed").linkedAssetIds)
            assertEquals(Money(12_000), goals.getValue("Open").progress)
            assertEquals(Money(8_000), goals.getValue("Closed").progress)
            assertEquals(SavingsGoalLifecycle.CLOSED, goals.getValue("Closed").lifecycle)
            assertEquals(
                listOf(12_000L),
                fixture.goalRepository.observeAllocationHistory(goals.getValue("Open").id).first()
                    .map { it.delta.minorUnits },
            )
            assertEquals(
                listOf(8_000L),
                fixture.goalRepository.observeAllocationHistory(goals.getValue("Closed").id).first()
                    .map { it.delta.minorUnits },
            )
            val link = SavingsGoalLinkEventKind.LINK
            val deleted = SavingsGoalLinkEventKind.ASSET_DELETED
            val expectedKinds = mapOf("Open" to listOf(link, link, deleted), "Closed" to listOf(link, deleted))
            goals.values.forEach { goal ->
                val links = fixture.goalRepository.observeLinkHistory(goal.id).first()
                assertEquals(expectedKinds.getValue(goal.name), links.map { it.kind })
                assertEquals(ASSET_ID, links.last().fromAssetId)
                assertEquals(DELETION_MS, links.last().timestampEpochMs)
            }
            val group = fixture.db.accountGroupDao().observeAll().first().single()
            assertEquals(listOf(SURVIVOR_ID), group.members.map { it.assetId })
            assertEquals(10_000, fixture.db.netWorthDao().find(CURRENT_MONTH)?.assetsMinor)
            assertEquals(clockCallsBefore + 1, fixture.deletionClock.calls)
            fixture.close()
        }

    @Test
    fun `delete failures roll back audit links current links asset group cascades and snapshot effects`() = runTest {
        DeleteFailure.entries.forEach { failure ->
            val fixture = fixture()
            fixture.seedDeletionScenario()
            val before = fixture.captureDeletionState()
            val repository = fixture.assetRepository(failure)

            assertFails { repository.deleteById(ASSET_ID) }

            assertEquals(before, fixture.captureDeletionState(), "rollback failed at $failure")
            fixture.close()
        }
    }

    @Test
    fun `failed deletion has no partial reactive emission while success emits complete unlink state`() = runTest {
        val fixture = fixture()
        fixture.seedDeletionScenario()
        val goals = fixture.goalRepository.observeAll().produceIn(backgroundScope)
        val initial = goals.receive()
        val closedGoalId = initial.single { it.name == "Closed" }.id
        val links = fixture.goalRepository.observeLinkHistory(closedGoalId).produceIn(backgroundScope)
        assertTrue(initial.all { ASSET_ID in it.linkedAssetIds })
        assertEquals(listOf(SavingsGoalLinkEventKind.LINK), links.receive().map { it.kind })

        assertFails { fixture.assetRepository(DeleteFailure.CLEAR_LINKS).deleteById(ASSET_ID) }
        assertNull(receiveOrNull(goals))
        assertNull(receiveOrNull(links))

        fixture.assetRepository.deleteById(ASSET_ID)
        val committed = goals.receive()
        assertTrue(committed.none { ASSET_ID in it.linkedAssetIds })
        assertEquals(listOf(12_000L, 8_000L), committed.map { it.progress.minorUnits }.sortedDescending())
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.ASSET_DELETED),
            links.receive().map { it.kind },
        )
        fixture.close()
    }

    @Test
    fun `duplicate insert cannot bypass audited deletion`() = runTest {
        val fixture = fixture()
        fixture.assetRepository.insert(asset())
        val goalId = fixture.goalRepository.create(goal(ASSET_ID))

        assertFails { fixture.assetRepository.insert(asset(currency = Currency.USD)) }

        assertEquals("EUR", fixture.db.assetDao().find(ASSET_ID)?.currency)
        assertEquals(setOf(ASSET_ID), fixture.goalRepository.observeAll().first().single().linkedAssetIds)
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK),
            fixture.goalRepository.observeLinkHistory(goalId).first().map { it.kind },
        )

        fixture.assetRepository.deleteById(ASSET_ID)

        assertNull(fixture.db.assetDao().find(ASSET_ID))
        assertEquals(
            listOf(SavingsGoalLinkEventKind.LINK, SavingsGoalLinkEventKind.ASSET_DELETED),
            fixture.goalRepository.observeLinkHistory(goalId).first().map { it.kind },
        )
        fixture.close()
    }

    private fun TestScope.fixture(): Fixture {
        val db = buildInMemoryTestDatabase()
        val gate = testSeedingGate(db)
        val deletionClock = AssetCountingClock(Instant.parse(DELETION_TIME))
        val snapshotUpserter = NetWorthSnapshotUpserter(
            db.assetDao(),
            db.liabilityDao(),
            db.fxRateDao(),
            db.netWorthDao(),
            AssetFixedClock(Instant.parse(DELETION_TIME)),
            { TimeZone.UTC },
        )
        return Fixture(db, gate, deletionClock, snapshotUpserter)
    }

    private suspend fun receiveOrNull(channel: ReceiveChannel<*>) =
        withContext(Dispatchers.Default) { withTimeoutOrNull(NO_EMISSION_TIMEOUT_MS) { channel.receive() } }

    private data class DeletionState(
        val assets: List<AssetEntity>,
        val goals: List<com.denebapps.patrimonio.domain.model.SavingsGoal>,
        val linkEvents: Map<String, List<SavingsGoalLinkEventKind>>,
        val groupMembers: List<String>,
        val snapshotAssetsMinor: Long?,
    )

    private data class Fixture(
        val db: AppDatabase,
        val seedingGate: SeedingGate,
        val deletionClock: AssetCountingClock,
        val snapshotUpserter: NetWorthSnapshotUpserter,
    ) {
        val goalRepository: SavingsGoalRepository = SavingsGoalRepositoryImpl(
            db,
            db.savingsGoalDao(),
            db.assetDao(),
            seedingGate,
            AssetFixedClock(Instant.parse(GOAL_TIME)),
        )
        val assetRepository: AssetRepository = assetRepository()

        fun assetRepository(failure: DeleteFailure? = null): AssetRepository {
            val assetDao =
                if (failure == DeleteFailure.DELETE) DeleteFailingAssetDao(db.assetDao()) else db.assetDao()
            val goalSource = failure?.let { DeleteFailingGoalSource(db.savingsGoalDao(), it) } ?: db.savingsGoalDao()
            val refreshSnapshot: suspend () -> Unit = {
                snapshotUpserter.refreshCurrentMonth()
                if (failure == DeleteFailure.AFTER_SNAPSHOT) error("simulated post-snapshot failure")
            }
            return AssetRepositoryImpl(
                db,
                assetDao,
                snapshotUpserter,
                seedingGate,
                goalSource,
                deletionClock,
                refreshSnapshot,
            )
        }

        suspend fun seedDeletionScenario() {
            assetRepository.insert(asset())
            assetRepository.insert(asset(id = SURVIVOR_ID, name = "Savings", amountMinor = 10_000))
            db.accountGroupDao().insertGroup(AccountGroupEntity(GROUP_ID, "Primary", true, 0))
            db.accountGroupDao().insertMember(AccountGroupMemberEntity(GROUP_ID, ASSET_ID))
            db.accountGroupDao().insertMember(AccountGroupMemberEntity(GROUP_ID, SURVIVOR_ID))
            val openId = goalRepository.create(goal(ASSET_ID, SURVIVOR_ID).copy(name = "Open"))
            val closedId = goalRepository.create(goal(ASSET_ID).copy(name = "Closed"))
            goalRepository.allocate(openId, Money(12_000))
            goalRepository.allocate(closedId, Money(8_000))
            goalRepository.close(closedId)
        }

        suspend fun captureDeletionState(): DeletionState {
            val goals = goalRepository.observeAll().first()
            val groupMembers = db.accountGroupDao().observeAll().first().single().members.map { it.assetId }.sorted()
            return DeletionState(
                assets = db.assetDao().list(),
                goals = goals,
                linkEvents = goals.associate { goal ->
                    goal.id to goalRepository.observeLinkHistory(goal.id).first().map { it.kind }
                },
                groupMembers = groupMembers,
                snapshotAssetsMinor = db.netWorthDao().find(CURRENT_MONTH)?.assetsMinor,
            )
        }

        fun close() = db.close()
    }

    companion object {
        private fun asset(
            id: String = ASSET_ID,
            name: String = "Checking",
            amountMinor: Long = 50_000,
            currency: Currency = Currency.EUR,
        ) = Asset(id, Asset.AssetGroup.BANK, name, null, CurrencyAmount(Money(amountMinor), currency))

        private fun goal(vararg linkedAssetIds: String) = CreateSavingsGoal(
            name = "Goal",
            target = CurrencyAmount(Money(100_000), Currency.EUR),
            linkedAssetIds = linkedAssetIds.toSet(),
        )

        private const val ASSET_ID = "asset-main"
        private const val SURVIVOR_ID = "asset-survivor"
        private const val GROUP_ID = "group-primary"
        private const val GOAL_TIME = "2026-07-13T11:00:00Z"
        private const val DELETION_TIME = "2026-07-13T12:00:00Z"
        private const val DELETION_MS = 1_783_944_000_000L
        private const val CURRENT_MONTH = "2026-07"
        private const val NO_EMISSION_TIMEOUT_MS = 250L
    }
}
