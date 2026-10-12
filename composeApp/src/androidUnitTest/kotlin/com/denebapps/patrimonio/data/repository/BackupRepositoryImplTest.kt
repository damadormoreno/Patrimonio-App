package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.backup.AccountGroupHoldingMemberBackup
import com.denebapps.patrimonio.data.backup.BackupCodec
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupHoldingMemberEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BackupFixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private const val VIAJE = "goal-viaje"
private const val COCHE = "goal-coche"
private const val COLCHON = "goal-colchon"

/** Every financial table, read back for whole-state comparisons. */
private data class TablesState(
    val assets: List<AssetEntity>,
    val liabilities: List<LiabilityEntity>,
    val groups: List<AccountGroupEntity>,
    val members: List<AccountGroupMemberEntity>,
    val holdingMembers: List<AccountGroupHoldingMemberEntity>,
    val snapshots: List<NetWorthSnapshotEntity>,
    val goals: List<SavingsGoalEntity>,
    val allocations: List<SavingsGoalAllocationEventEntity>,
    val links: List<SavingsGoalLinkEventEntity>,
    val goalAssets: List<SavingsGoalAssetEntity>,
    val holdings: List<PortfolioHoldingEntity>,
    val portfolios: List<PortfolioEntity>,
)

@RunWith(RobolectricTestRunner::class)
class BackupRepositoryImplTest {
    private val october2026 = BackupFixedClock(Instant.parse("2026-10-06T10:00:00Z"))

    private fun upserterFor(db: AppDatabase) = NetWorthSnapshotUpserter(
        db.assetDao(),
        db.liabilityDao(),
        db.portfolioHoldingDao(),
        db.fxRateDao(),
        db.netWorthDao(),
        october2026,
    ) { TimeZone.UTC }

    private fun CoroutineScope.repositoryFor(db: AppDatabase) =
        BackupRepositoryImpl(db, upserterFor(db), testSeedingGate(db), october2026)

    private suspend fun AppDatabase.tables() = TablesState(
        assets = assetDao().list(),
        liabilities = liabilityDao().list(),
        groups = accountGroupDao().listGroups(),
        members = accountGroupDao().listMembers(),
        holdingMembers = accountGroupDao().listHoldingMembers(),
        snapshots = netWorthDao().list(),
        goals = savingsGoalDao().listAllGoals(),
        allocations = savingsGoalDao().listAllAllocationEvents(),
        links = savingsGoalDao().listAllLinkEvents(),
        goalAssets = savingsGoalDao().listAllLinkedAssets(),
        holdings = portfolioHoldingDao().list(),
        portfolios = portfolioDao().list(),
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
        db.savingsGoalDao().insertGoal(
            SavingsGoalEntity(VIAJE, "Viaje", 300_000, "EUR", 20_800, "OPEN", createdAtEpochMs = 7),
        )
        db.savingsGoalDao().insertLinkedAssets(listOf("a1", "a2").map { SavingsGoalAssetEntity(VIAJE, it) })
        db.savingsGoalDao().insertGoal(
            SavingsGoalEntity(COCHE, "Coche", 900_000, "EUR", null, "CANCELLED", createdAtEpochMs = 12),
        )
        db.savingsGoalDao().insertGoal(
            SavingsGoalEntity(COLCHON, "Colchón", 100_000, "EUR", null, "OPEN", "g1", createdAtEpochMs = 13),
        )
        db.savingsGoalDao().insertAllocationEvent(SavingsGoalAllocationEventEntity(3, VIAJE, 50_000, 1_000))
        db.savingsGoalDao().insertAllocationEvent(SavingsGoalAllocationEventEntity(4, VIAJE, -20_000, 2_000))
        db.savingsGoalDao().insertLinkEvent(SavingsGoalLinkEventEntity(5, VIAJE, null, "a1", "LINK", 1_000))
        db.savingsGoalDao().insertLinkEvent(SavingsGoalLinkEventEntity(7, VIAJE, null, "a2", "LINK", 1_000))
        db.savingsGoalDao().insertLinkEvent(
            SavingsGoalLinkEventEntity(6, COLCHON, null, null, "LINK", 1_000, toGroupId = "g1"),
        )
        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                "p1", TEST_PORTFOLIO_ID, "Vanguard Global", "FUND", "IE00B03HCZ61", null, "QUOTED", "MORNINGSTAR",
                "0P00012PP6", "EUR",
                8_100_001, 12_345_678, 20_000, 1_000, null, null,
                ongoingChargePercentScaled = 1_800,
                riskLevel = 6,
                category = "RV Global",
                managementCompany = "Vanguard",
            ),
        )
        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                "p2", TEST_PORTFOLIO_ID, "iShares MSCI World", "ETF", null, "IWDA.AS", "QUOTED", "YAHOO", "IWDA.AS",
                "EUR",
                2_500_000, null, null, null, null, null,
            ),
        )
        db.portfolioDao().insert(PortfolioEntity("pf-hijos", "Hijos", 1))
        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                "p3", "pf-hijos", "Cuadro", "OTHER", null, null, "MANUAL", null, null, "USD",
                null, null, null, null, 250_000, 7,
            ),
        )
        db.accountGroupDao().insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "p1"))
        db.accountGroupDao().insertHoldingMember(AccountGroupHoldingMemberEntity("g1", "p3"))
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
        assertEquals(listOf(VIAJE, COCHE, COLCHON), document.savingsGoals.map { it.id })
        assertEquals(listOf("p3", "p2", "p1"), document.portfolioHoldings.map { it.id })
        assertEquals(listOf("Mi cartera" to 0, "Hijos" to 1), document.portfolios.map { it.name to it.sortOrder })
        assertEquals(
            mapOf("p1" to TEST_PORTFOLIO_ID, "p2" to TEST_PORTFOLIO_ID, "p3" to "pf-hijos"),
            document.portfolioHoldings.associate { it.id to it.portfolioId },
        )
        assertEquals("g1", document.savingsGoals.last().linkedGroupId)
        assertEquals(listOf("a1", "a2"), document.savingsGoals.first().linkedAssetIds)
        assertEquals(
            listOf(AccountGroupHoldingMemberBackup("g1", "p1"), AccountGroupHoldingMemberBackup("g1", "p3")),
            document.accountGroupHoldingMembers,
        )
        db.close()
    }

    @Test
    fun `the holding members of the groups come back after the holdings they point at`() = runTest {
        val source = buildInMemoryTestDatabase()
        testSeedingGate(source).await()
        populate(source)
        val json = repositoryFor(source).exportJson()
        source.close()
        val target = buildInMemoryTestDatabase()

        repositoryFor(target).importJson(json)

        assertEquals(
            listOf(AccountGroupHoldingMemberEntity("g1", "p1"), AccountGroupHoldingMemberEntity("g1", "p3")),
            target.accountGroupDao().listHoldingMembers(),
        )
        assertEquals(listOf(AccountGroupMemberEntity("g1", "a1")), target.accountGroupDao().listMembers())
        target.close()
    }

    @Test
    fun `a version 14 backup replaces the memberships and leaves the groups without holdings`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        assertEquals(2, db.accountGroupDao().listHoldingMembers().size)
        val v14 = """
            {
              "format": "patrimonio-backup",
              "version": 14,
              "exportedAt": "2026-01-01T00:00:00Z",
              "assets": [{"id": "a1", "group": "BANK", "name": "Cuenta", "amountMinor": 150000, "currency": "EUR"}],
              "accountGroups": [{"id": "g1", "name": "Casa", "showBalance": true, "sortOrder": 0}],
              "accountGroupMembers": [{"groupId": "g1", "assetId": "a1"}],
              "portfolios": [{"id": "pf-1", "name": "Mi cartera", "sortOrder": 0}],
              "portfolioHoldings": [
                {"id": "h1", "portfolioId": "pf-1", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL",
                 "currency": "EUR", "valueMinor": 250000, "valuedAtEpochMs": 7}
              ]
            }
        """.trimIndent()

        repositoryFor(db).importJson(v14)

        assertEquals(listOf("g1"), db.accountGroupDao().listGroups().map { it.id })
        assertEquals(listOf(AccountGroupMemberEntity("g1", "a1")), db.accountGroupDao().listMembers())
        assertTrue(db.accountGroupDao().listHoldingMembers().isEmpty())
        assertEquals(listOf("h1"), db.portfolioHoldingDao().list().map { it.id })
        db.close()
    }

    @Test
    fun `a backup with a holding member of an unknown group or holding is refused and changes nothing`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val before = db.tables()
        val repository = repositoryFor(db)
        val exported = BackupCodec.decode(repository.exportJson())

        fun withHoldingMembers(vararg members: AccountGroupHoldingMemberBackup) =
            BackupCodec.encode(exported.copy(accountGroupHoldingMembers = members.toList()))

        val unknownGroup = withHoldingMembers(AccountGroupHoldingMemberBackup("nope", "p1"))
        val unknownHolding = withHoldingMembers(AccountGroupHoldingMemberBackup("g1", "nope"))
        val duplicate = withHoldingMembers(
            AccountGroupHoldingMemberBackup("g1", "p1"),
            AccountGroupHoldingMemberBackup("g1", "p1"),
        )

        assertFailsWith<InvalidBackupException> { repository.importJson(unknownGroup) }
        assertFailsWith<InvalidBackupException> { repository.importJson(unknownHolding) }
        assertFailsWith<InvalidBackupException> { repository.importJson(duplicate) }

        assertEquals(before, db.tables())
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
        assertTrue(state.holdings.isEmpty())
        assertEquals(listOf("Mi cartera"), state.portfolios.map { it.name })
        // The current month is recomputed from the (now empty) assets.
        assertEquals(listOf(NetWorthSnapshotEntity("2026-10", 0, 0)), state.snapshots)
        db.close()
    }

    @Test
    fun `importing a version 7 backup without holdings clears the current portfolio`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val v7 = """{"format": "patrimonio-backup", "version": 7, "exportedAt": "2026-01-01T00:00:00Z"}"""

        repositoryFor(db).importJson(v7)

        assertTrue(db.portfolioHoldingDao().list().isEmpty())
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
        // The last account a goal follows, pointing at an asset that is not in the file.
        val dangling = repository.exportJson().replace("\"a2\"\n", "\"nope\"\n")
        assertTrue("\"nope\"" in dangling)
        assertFailsWith<InvalidBackupException> { repository.importJson(dangling) }

        assertEquals(before, db.tables())
        db.close()
    }

    @Test
    fun `a version 1 backup imports its single asset link into the goal accounts`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        val v1 = """
            {
              "format": "patrimonio-backup",
              "version": 1,
              "exportedAt": "2026-01-01T00:00:00Z",
              "assets": [{"id": "a1", "group": "BANK", "name": "Cuenta", "amountMinor": 150000, "currency": "EUR"}],
              "savingsGoals": [
                {"id": 1, "name": "Viaje", "targetMinor": 300000, "currency": "EUR",
                 "linkedAssetId": "a1", "lifecycle": "OPEN"}
              ],
              "savingsGoalLinkEvents": [
                {"id": 1, "goalId": 1, "toAssetId": "a1", "kind": "LINK", "timestampEpochMs": 1000}
              ]
            }
        """.trimIndent()

        repositoryFor(db).importJson(v1)

        val goal = db.savingsGoalDao().listAllGoals().single()
        assertEquals(listOf("a1"), db.savingsGoalDao().listLinkedAssetIds(goal.id))
        assertNull(goal.linkedGroupId)
        val link = db.savingsGoalDao().listAllLinkEvents().single()
        assertEquals(goal.id, link.goalId)
        assertNull(link.fromGroupId)
        assertNull(link.toGroupId)
        db.close()
    }

    @Test
    fun `a version 10 backup imports its holdings into a single new Mi cartera`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val v10 = """
            {
              "format": "patrimonio-backup",
              "version": 10,
              "exportedAt": "2026-01-01T00:00:00Z",
              "portfolioHoldings": [
                {"id": "h1", "name": "Cuadro", "type": "OTHER", "kind": "MANUAL", "currency": "EUR",
                 "valueMinor": 250000, "valuedAtEpochMs": 7, "broker": "Openbank", "riskLevel": 4},
                {"id": "h2", "name": "Fondo", "type": "FUND", "kind": "QUOTED", "currency": "EUR",
                 "provider": "MORNINGSTAR", "providerCode": "0P00012PP6", "unitsScaled": 8100001}
              ]
            }
        """.trimIndent()

        repositoryFor(db).importJson(v10)

        val portfolio = db.portfolioDao().list().single()
        assertEquals("Mi cartera", portfolio.name)
        assertEquals(0, portfolio.sortOrder)
        assertTrue(portfolio.id != TEST_PORTFOLIO_ID && portfolio.id.length == 36, portfolio.id)
        val holdings = db.portfolioHoldingDao().list()
        assertEquals(listOf("h1", "h2"), holdings.map { it.id }.sorted())
        assertTrue(holdings.all { it.portfolioId == portfolio.id })
        assertEquals("Openbank", holdings.first { it.id == "h1" }.broker)
        assertEquals(4, holdings.first { it.id == "h1" }.riskLevel)
        db.close()
    }

    @Test
    fun `a version 1 backup without holdings still leaves exactly one Mi cartera`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        db.portfolioDao().insert(PortfolioEntity("extra", "Hijos", 1))

        repositoryFor(db).importJson("""{"format":"patrimonio-backup","version":1,"exportedAt":"x"}""")

        assertEquals(listOf("Mi cartera"), db.portfolioDao().list().map { it.name })
        db.close()
    }

    @Test
    fun `a version 11 backup without portfolios or holdings leaves one Mi cartera`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)

        repositoryFor(db).importJson("""{"format":"patrimonio-backup","version":11,"exportedAt":"x"}""")

        val state = db.tables()
        assertEquals(listOf("Mi cartera"), state.portfolios.map { it.name })
        assertTrue(state.holdings.isEmpty())
        db.close()
    }

    @Test
    fun `a version 11 backup replaces the portfolios of the device and keeps its own ids and order`() = runTest {
        val source = buildInMemoryTestDatabase()
        testSeedingGate(source).await()
        populate(source)
        source.portfolioDao().insert(PortfolioEntity("pf-vacia", "Vacía", 2))
        val json = repositoryFor(source).exportJson()
        source.close()
        val target = buildInMemoryTestDatabase()
        testSeedingGate(target).await()
        target.portfolioDao().insert(PortfolioEntity("pf-local", "Local", 1))

        repositoryFor(target).importJson(json)

        assertEquals(
            listOf(
                PortfolioEntity(TEST_PORTFOLIO_ID, "Mi cartera", 0),
                PortfolioEntity("pf-hijos", "Hijos", 1),
                PortfolioEntity("pf-vacia", "Vacía", 2),
            ),
            target.portfolioDao().list(),
        )
        assertEquals("pf-hijos", target.portfolioHoldingDao().find("p3")?.portfolioId)
        target.close()
    }

    @Test
    fun `a backup whose holding points at a missing portfolio is refused and changes nothing`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val before = db.tables()
        val repository = repositoryFor(db)
        val dangling = repository.exportJson().replace("\"portfolioId\": \"pf-hijos\"", "\"portfolioId\": \"nope\"")
        assertTrue("\"nope\"" in dangling)

        assertFailsWith<InvalidBackupException> { repository.importJson(dangling) }

        assertEquals(before, db.tables())
        db.close()
    }

    @Test
    fun `events appended after an import get fresh ids above the imported ones`() = runTest {
        val source = buildInMemoryTestDatabase()
        testSeedingGate(source).await()
        populate(source)
        val json = repositoryFor(source).exportJson()
        source.close()
        val target = buildInMemoryTestDatabase()
        repositoryFor(target).importJson(json)

        val newId = target.savingsGoalDao().insertAllocationEvent(
            SavingsGoalAllocationEventEntity(goalId = VIAJE, deltaMinor = 1, timestampEpochMs = 3_000),
        )

        assertTrue(newId > 4, "new event id $newId collides with imported ids")
        assertNotNull(target.savingsGoalDao().findGoal(VIAJE))
        target.close()
    }
}
