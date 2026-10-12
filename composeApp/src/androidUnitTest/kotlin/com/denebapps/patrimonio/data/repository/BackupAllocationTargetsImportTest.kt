package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.backup.AllocationTargetBackup
import com.denebapps.patrimonio.data.backup.BackupCodec
import com.denebapps.patrimonio.data.backup.BackupDocument
import com.denebapps.patrimonio.data.backup.PortfolioBackup
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.calc.DEFAULT_REBALANCE_TOLERANCE_BP
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TargetsBackupClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Backup version 14 against a real database: allocation targets and tolerances go out, come back and are replaced. */
@RunWith(RobolectricTestRunner::class)
class BackupAllocationTargetsImportTest {
    private val clock = TargetsBackupClock(Instant.parse("2026-10-12T10:00:00Z"))
    private val sixtyForty = mapOf(AssetClass.EQUITY to 6_000, AssetClass.FIXED_INCOME to 4_000)

    private fun CoroutineScope.repositoryFor(db: AppDatabase) = BackupRepositoryImpl(
        db,
        NetWorthSnapshotUpserter(
            db.assetDao(),
            db.liabilityDao(),
            db.portfolioHoldingDao(),
            db.fxRateDao(),
            db.netWorthDao(),
            clock,
        ) { TimeZone.UTC },
        testSeedingGate(db),
        clock,
    )

    private fun CoroutineScope.targetsFor(db: AppDatabase) = AllocationTargetRepositoryImpl(db, testSeedingGate(db))

    @Test
    fun `targets and tolerances go out in the backup and come back exactly`() = runTest {
        val source = buildInMemoryTestDatabase()
        val sourceTargets = targetsFor(source)
        val hijos = PortfolioCollectionRepositoryImpl(source, testSeedingGate(source)) { "pf-hijos" }
            .createPortfolio("Hijos")
        sourceTargets.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 250)
        sourceTargets.setTargets(hijos.id, mapOf(AssetClass.CRYPTO to 10_000), toleranceBp = 900)
        val json = repositoryFor(source).exportJson()
        source.close()

        val document = BackupCodec.decode(json)
        assertEquals(BackupCodec.VERSION, document.version)
        assertEquals(3, document.allocationTargets.size)
        assertEquals(listOf(250, 900), document.portfolios.map { it.rebalanceToleranceBp })

        val target = buildInMemoryTestDatabase()
        repositoryFor(target).importJson(json)

        val restored = targetsFor(target)
        val portfolioIds = target.portfolioDao().list().map { it.id }
        assertEquals(listOf(TEST_PORTFOLIO_ID, "pf-hijos"), portfolioIds)
        assertEquals(
            AllocationTargets(TEST_PORTFOLIO_ID, sixtyForty, 250),
            restored.observeTargets(TEST_PORTFOLIO_ID).first(),
        )
        assertEquals(
            AllocationTargets("pf-hijos", mapOf(AssetClass.CRYPTO to 10_000), 900),
            restored.observeTargets("pf-hijos").first(),
        )
        target.close()
    }

    @Test
    fun `importing replaces the targets and tolerances that were there`() = runTest {
        val db = buildInMemoryTestDatabase()
        val targets = targetsFor(db)
        targets.setTargets(TEST_PORTFOLIO_ID, mapOf(AssetClass.EQUITY to 10_000), toleranceBp = 100)
        val incoming = BackupDocument(
            exportedAt = "2026-10-12T10:00:00Z",
            portfolios = listOf(PortfolioBackup("pf-new", "Otra", 0, rebalanceToleranceBp = 700)),
            allocationTargets = listOf(
                AllocationTargetBackup("pf-new", "EQUITY", 5_000),
                AllocationTargetBackup("pf-new", "REAL_ESTATE", 5_000),
            ),
        )

        repositoryFor(db).importJson(BackupCodec.encode(incoming))

        assertEquals(listOf("pf-new"), db.portfolioDao().list().map { it.id })
        assertEquals(
            AllocationTargets("pf-new", mapOf(AssetClass.EQUITY to 5_000, AssetClass.REAL_ESTATE to 5_000), 700),
            targets.observeTargets("pf-new").first(),
        )
        assertEquals(2, db.allocationTargetDao().list().size)
        db.close()
    }

    @Test
    fun `a version 13 backup imports with the default tolerance and no targets`() = runTest {
        val db = buildInMemoryTestDatabase()
        val targets = targetsFor(db)
        targets.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 100)
        val v13 = """
            {"format":"patrimonio-backup","version":13,"exportedAt":"2026-10-06T10:00:00Z",
             "portfolios":[{"id":"pf-old","name":"Antigua","sortOrder":0}]}
        """.trimIndent()

        repositoryFor(db).importJson(v13)

        assertEquals(DEFAULT_REBALANCE_TOLERANCE_BP, db.portfolioDao().find("pf-old")?.rebalanceToleranceBp)
        assertNull(targets.observeTargets("pf-old").first())
        assertTrue(db.allocationTargetDao().list().isEmpty())
        db.close()
    }

    @Test
    fun `an invalid targets section is refused before anything is touched`() = runTest {
        val db = buildInMemoryTestDatabase()
        val targets = targetsFor(db)
        targets.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 100)
        val broken = BackupDocument(
            exportedAt = "2026-10-12T10:00:00Z",
            portfolios = listOf(PortfolioBackup("pf-new", "Otra", 0)),
            allocationTargets = listOf(AllocationTargetBackup("pf-new", "EQUITY", 5_000)),
        )

        assertFailsWith<InvalidBackupException> { repositoryFor(db).importJson(BackupCodec.encode(broken)) }

        assertEquals(listOf(TEST_PORTFOLIO_ID), db.portfolioDao().list().map { it.id })
        assertEquals(sixtyForty, targets.observeTargets(TEST_PORTFOLIO_ID).first()?.targetsBp)
        db.close()
    }
}
