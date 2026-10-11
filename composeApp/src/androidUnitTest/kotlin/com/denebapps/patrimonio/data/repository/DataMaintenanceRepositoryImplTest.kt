package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.datastore.PreferencesRepositoryImpl
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.FxHistoryEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceSyncEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAllocationEventEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalAssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DataMaintenanceRepositoryImplTest {
    private fun asset(id: String) = AssetEntity(
        id = id,
        group = "BANK",
        name = "Bank $id",
        subtitle = null,
        amountMinor = 10_000,
        currency = "EUR",
    )

    /** Populates EVERY financial table with at least one FK-linked row. */
    private suspend fun populateAllTables(db: AppDatabase) {
        db.assetDao().insert(asset("asset-1"))
        db.liabilityDao().insert(
            LiabilityEntity("liab-1", "LOAN", "Mortgage", null, 50_000, "EUR"),
        )
        db.accountGroupDao().insertGroup(AccountGroupEntity("group-1", "Daily", showBalance = true, sortOrder = 0))
        db.accountGroupDao().insertMember(AccountGroupMemberEntity("group-1", "asset-1"))
        val goalId = "goal-1"
        db.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
                id = goalId,
                name = "Trip",
                targetMinor = 25_000,
                currency = "EUR",
                targetDateEpochDay = null,
                lifecycle = "OPEN",
                createdAtEpochMs = 1L,
            ),
        )
        db.savingsGoalDao().insertLinkedAssets(listOf(SavingsGoalAssetEntity(goalId, "asset-1")))
        db.savingsGoalDao().insertAllocationEvent(
            SavingsGoalAllocationEventEntity(goalId = goalId, deltaMinor = 5_000, timestampEpochMs = 1L),
        )
        db.savingsGoalDao().insertLinkEvent(
            SavingsGoalLinkEventEntity(
                goalId = goalId,
                fromAssetId = null,
                toAssetId = "asset-1",
                kind = "LINK",
                timestampEpochMs = 1L,
            ),
        )
        db.netWorthDao().upsert(NetWorthSnapshotEntity("2026-06", 10_000, 50_000))
        db.insertTestPortfolio(id = "portfolio-2", name = "Hijos", sortOrder = 1)
        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                "holding-1", TEST_PORTFOLIO_ID, "Cuadro", "OTHER", null, null, "MANUAL", null, null, "EUR",
                null, null, null, null, 250_000, 1L,
            ),
        )
        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                "holding-2", "portfolio-2", "Fondo", "OTHER", null, null, "MANUAL", null, null, "EUR",
                null, null, null, null, 100_000, 1L,
            ),
        )
        val price = InstrumentPriceEntity("MORNINGSTAR", "0P00012PP6", 20_000L, 5L, "EUR")
        db.instrumentPriceDao().upsertAll(listOf(price))
        db.instrumentPriceDao().upsertSync(InstrumentPriceSyncEntity("MORNINGSTAR", "0P00012PP6", 1L))
        db.fxHistoryDao().upsertAll(listOf(FxHistoryEntity("USD", 20_000L, 920_000L)))
    }

    private suspend fun assertAllFinancialTablesEmpty(db: AppDatabase) {
        assertTrue(db.assetDao().observeAll().first().isEmpty(), "assets not cleared")
        assertTrue(db.liabilityDao().observeAll().first().isEmpty(), "liabilities not cleared")
        assertTrue(db.accountGroupDao().observeAll().first().isEmpty(), "account groups not cleared")
        assertTrue(db.savingsGoalDao().observeAll().first().isEmpty(), "savings goals not cleared")
        assertTrue(db.savingsGoalDao().listAllLinkedAssets().isEmpty(), "goal accounts not cleared")
        assertTrue(db.netWorthDao().observeAll().first().isEmpty(), "net-worth snapshots not cleared")
        assertTrue(db.portfolioHoldingDao().list().isEmpty(), "portfolio holdings not cleared")
        assertEquals(listOf("Mi cartera"), db.portfolioDao().list().map { it.name }, "portfolios not reset")
        assertEquals(0, db.instrumentPriceDao().count(), "price history not cleared")
        assertNull(db.instrumentPriceDao().findSync("MORNINGSTAR", "0P00012PP6"), "price sync state not cleared")
    }

    @Test
    fun `clear empties every financial table`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populateAllTables(db)
        val repository: DataMaintenanceRepository = DataMaintenanceRepositoryImpl(db)

        repository.clearAllFinancialData()

        assertAllFinancialTablesEmpty(db)
        db.close()
    }

    @Test
    fun `clear leaves exactly one empty Mi cartera whatever portfolios there were`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populateAllTables(db)
        assertEquals(2, db.portfolioDao().count())

        DataMaintenanceRepositoryImpl(db).clearAllFinancialData()

        val portfolio = db.portfolioDao().list().single()
        assertEquals("Mi cartera", portfolio.name)
        assertEquals(0, portfolio.sortOrder)
        assertEquals(0, db.portfolioHoldingDao().countInPortfolio(portfolio.id))
        db.close()
    }

    @Test
    fun `clearing twice still leaves exactly one Mi cartera`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        val repository = DataMaintenanceRepositoryImpl(db)

        repository.clearAllFinancialData()
        repository.clearAllFinancialData()

        assertEquals(listOf("Mi cartera"), db.portfolioDao().list().map { it.name })
        db.close()
    }

    @Test
    fun `clear preserves fx rates and DataStore preferences`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populateAllTables(db)
        val fxCountBefore = db.fxRateDao().count()
        assertTrue(fxCountBefore > 0, "fx seed missing before clear")
        val prefsPath = File.createTempFile("patrimonio_prefs", ".preferences_pb").absolutePath
        val prefs = PreferencesRepositoryImpl(prefsPath)
        prefs.setThemeMode(ThemeMode.DARK)
        prefs.setFirstName("Ana")
        prefs.setLastName("Gil")
        val repository: DataMaintenanceRepository = DataMaintenanceRepositoryImpl(db)

        repository.clearAllFinancialData()

        assertEquals(fxCountBefore, db.fxRateDao().count(), "fx_rates must be preserved")
        assertEquals(ThemeMode.DARK, prefs.observeThemeMode().first())
        assertEquals("Ana", prefs.observeFirstName().first())
        assertEquals("Gil", prefs.observeLastName().first())
        db.close()
    }

    @Test
    fun `clear keeps the historical fx rates because they are not tied to any holding`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populateAllTables(db)

        DataMaintenanceRepositoryImpl(db).clearAllFinancialData()

        assertEquals(1, db.fxHistoryDao().list().size)
        db.close()
    }

    @Test
    fun `active asset collector observes the cleared state without restart`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populateAllTables(db)
        val emissions = db.assetDao().observeAll().produceIn(backgroundScope)
        assertEquals(1, emissions.receive().size)
        val repository: DataMaintenanceRepository = DataMaintenanceRepositoryImpl(db)

        repository.clearAllFinancialData()

        assertEquals(0, emissions.receive().size, "collector never observed the cleared state")
        emissions.cancel()
        db.close()
    }
}
