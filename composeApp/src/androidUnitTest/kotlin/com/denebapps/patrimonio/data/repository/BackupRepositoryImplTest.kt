package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.backup.BackupCodec
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class BackupFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Every financial table, read back for whole-state comparisons. */
private data class TablesState(
    val assets: List<AssetEntity>,
    val liabilities: List<LiabilityEntity>,
    val groups: List<AccountGroupEntity>,
    val members: List<AccountGroupMemberEntity>,
    val snapshots: List<NetWorthSnapshotEntity>,
    val goals: List<SavingsGoalEntity>,
    val allocations: List<SavingsGoalAllocationEventEntity>,
    val links: List<SavingsGoalLinkEventEntity>,
)

@RunWith(RobolectricTestRunner::class)
class BackupRepositoryImplTest {
    private val october2026 = BackupFixedClock(Instant.parse("2026-10-06T10:00:00Z"))

    private fun upserterFor(db: AppDatabase) =
        NetWorthSnapshotUpserter(db.assetDao(), db.liabilityDao(), db.fxRateDao(), db.netWorthDao(), october2026) {
            TimeZone.UTC
        }

    private fun CoroutineScope.repositoryFor(db: AppDatabase) =
        BackupRepositoryImpl(db, upserterFor(db), testSeedingGate(db), october2026)

    private suspend fun AppDatabase.tables() = TablesState(
        assets = assetDao().list(),
        liabilities = liabilityDao().list(),
        groups = accountGroupDao().listGroups(),
        members = accountGroupDao().listMembers(),
        snapshots = netWorthDao().list(),
        goals = savingsGoalDao().listAllGoals(),
        allocations = savingsGoalDao().listAllAllocationEvents(),
        links = savingsGoalDao().listAllLinkEvents(),
    )

    /** Rows in every table, with non-trivial goal/event ids, and a current-month snapshot that
     *  matches the assets (as the app maintains it). */
    private suspend fun populate(db: AppDatabase) {
        db.assetDao().insert(AssetEntity("a1", "BANK", "Cuenta", null, 150_000, "EUR"))
        db.assetDao().insert(AssetEntity("a2", "INVEST", "Broker", "IBKR", 9_900, "USD"))
        db.liabilityDao().insert(LiabilityEntity("l1", "MORTGAGE", "Hipoteca", null, 10_000_000, "EUR"))
        db.accountGroupDao().insertGroup(AccountGroupEntity("g1", "Día a día", showBalance = true, sortOrder = 0))
        db.accountGroupDao().insertMember(AccountGroupMemberEntity("g1", "a1"))
        db.netWorthDao().upsert(NetWorthSnapshotEntity("2026-09", 140_000, 10_100_000))
        db.savingsGoalDao().insertGoal(SavingsGoalEntity(7, "Viaje", 300_000, "EUR", 20_800, "a1", "OPEN"))
        db.savingsGoalDao().insertGoal(SavingsGoalEntity(12, "Coche", 900_000, "EUR", null, null, "CANCELLED"))
        db.savingsGoalDao().insertAllocationEvent(SavingsGoalAllocationEventEntity(3, 7, 50_000, 1_000))
        db.savingsGoalDao().insertAllocationEvent(SavingsGoalAllocationEventEntity(4, 7, -20_000, 2_000))
        db.savingsGoalDao().insertLinkEvent(SavingsGoalLinkEventEntity(5, 7, null, "a1", "LINK", 1_000))
        upserterFor(db).refreshCurrentMonth()
    }

    @Test
    fun `export then import into an empty database restores every table exactly`() = runTest {
        val source = buildInMemoryTestDatabase()
        testSeedingGate(source).await()
        populate(source)
        val expected = source.tables()
        val json = repositoryFor(source).exportJson()
        source.close()

        val target = buildInMemoryTestDatabase()
        repositoryFor(target).importJson(json)

        assertEquals(expected, target.tables())
        target.close()
    }

    @Test
    fun `export is a valid versioned document`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)

        val document = BackupCodec.decode(repositoryFor(db).exportJson())

        assertEquals(BackupCodec.VERSION, document.version)
        assertEquals("2026-10-06T10:00:00Z", document.exportedAt)
        assertEquals(2, document.assets.size)
        assertEquals(listOf(7L, 12L), document.savingsGoals.map { it.id })
        db.close()
    }

    @Test
    fun `import replaces existing data instead of merging`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        val repository = repositoryFor(db)
        val emptyBackup = repository.exportJson()
        populate(db)

        repository.importJson(emptyBackup)

        val state = db.tables()
        assertTrue(state.assets.isEmpty() && state.goals.isEmpty() && state.allocations.isEmpty())
        // The current month is recomputed from the (now empty) assets.
        assertEquals(listOf(NetWorthSnapshotEntity("2026-10", 0, 0)), state.snapshots)
        db.close()
    }

    @Test
    fun `an invalid file leaves the current data untouched`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val before = db.tables()
        val repository = repositoryFor(db)

        assertFailsWith<InvalidBackupException> { repository.importJson("""{"format":"otra-app"}""") }
        val dangling = repository.exportJson().replace("\"linkedAssetId\": \"a1\"", "\"linkedAssetId\": \"nope\"")
        assertFailsWith<InvalidBackupException> { repository.importJson(dangling) }

        assertEquals(before, db.tables())
        db.close()
    }

    @Test
    fun `goals created after an import get fresh ids above the imported ones`() = runTest {
        val source = buildInMemoryTestDatabase()
        testSeedingGate(source).await()
        populate(source)
        val json = repositoryFor(source).exportJson()
        source.close()
        val target = buildInMemoryTestDatabase()
        repositoryFor(target).importJson(json)

        val newId = target.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
                name = "Nueva",
                targetMinor = 1,
                currency = "EUR",
                targetDateEpochDay = null,
                linkedAssetId = null,
                lifecycle = "OPEN",
            ),
        )

        assertTrue(newId > 12, "new goal id $newId collides with imported ids")
        assertNotNull(target.savingsGoalDao().findGoal(7))
        target.close()
    }
}
