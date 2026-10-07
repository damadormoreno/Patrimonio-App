package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.datastore.PreferencesRepositoryImpl
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
import com.denebapps.patrimonio.data.db.entity.SubscriptionEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
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
                linkedAssetId = "asset-1",
                lifecycle = "OPEN",
                createdAtEpochMs = 1L,
            ),
        )
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
        db.subscriptionDao().insert(
            SubscriptionEntity("sub-1", "Netflix", 1_299, "EUR", "MONTHLY", 20_000, "asset-1", active = true),
        )
    }

    private suspend fun assertAllFinancialTablesEmpty(db: AppDatabase) {
        assertTrue(db.assetDao().observeAll().first().isEmpty(), "assets not cleared")
        assertTrue(db.liabilityDao().observeAll().first().isEmpty(), "liabilities not cleared")
        assertTrue(db.accountGroupDao().observeAll().first().isEmpty(), "account groups not cleared")
        assertTrue(db.savingsGoalDao().observeAll().first().isEmpty(), "savings goals not cleared")
        assertTrue(db.netWorthDao().observeAll().first().isEmpty(), "net-worth snapshots not cleared")
        assertTrue(db.subscriptionDao().list().isEmpty(), "subscriptions not cleared")
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
