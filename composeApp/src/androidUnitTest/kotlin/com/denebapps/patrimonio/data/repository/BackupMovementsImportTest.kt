package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.backup.BackupCodec
import com.denebapps.patrimonio.data.backup.BackupDocument
import com.denebapps.patrimonio.data.backup.MovementBackup
import com.denebapps.patrimonio.data.backup.PortfolioBackup
import com.denebapps.patrimonio.data.backup.PortfolioHoldingBackup
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.MovementEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
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
import kotlin.test.assertTrue

private class MovementsBackupClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private const val ONE_UNIT = 1_000_000L

/** Backup version 13 against a real database: movements go out, come back, and decide the units of their holdings. */
@RunWith(RobolectricTestRunner::class)
class BackupMovementsImportTest {
    private val clock = MovementsBackupClock(Instant.parse("2026-10-06T10:00:00Z"))

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

    private fun holdingRow(id: String, units: Long) = PortfolioHoldingEntity(
        id, TEST_PORTFOLIO_ID, "Fondo $id", "FUND", null, null, "QUOTED", "MORNINGSTAR", "code-$id", "EUR",
        units, 10_000_000, 20_000, 1_000, null, null,
    )

    private fun buyRow(id: String, holding: String, day: Long, units: Long, sequence: Long) = MovementEntity(
        id, holding, "BUY", day, units, units / ONE_UNIT * 10_000, "EUR", 0, units / ONE_UNIT * 10_000, 0, null, null,
        sequence,
    )

    /** Fund `fa` bought 10 units and moved 4 of them to fund `fb`, whose units are the 4 received. */
    private suspend fun populate(db: AppDatabase) {
        db.portfolioHoldingDao().insert(holdingRow("fa", 6 * ONE_UNIT))
        db.portfolioHoldingDao().insert(holdingRow("fb", 4 * ONE_UNIT))
        db.movementDao().insert(buyRow("m1", "fa", 20_000, 10 * ONE_UNIT, 1))
        db.movementDao().insert(
            MovementEntity("m2", "fa", "TRANSFER_OUT", 20_010, 4 * ONE_UNIT, null, null, 0, null, 0, "t1", "Cambio", 2),
        )
        db.movementDao().insert(
            MovementEntity("m3", "fb", "TRANSFER_IN", 20_010, 4 * ONE_UNIT, null, null, 0, null, 0, "t1", "Cambio", 3),
        )
    }

    @Test
    fun `movements go out in the backup and come back with their holdings exactly`() = runTest {
        val source = buildInMemoryTestDatabase()
        testSeedingGate(source).await()
        populate(source)
        val json = repositoryFor(source).exportJson()
        val movements = source.movementDao().list()
        val holdings = source.portfolioHoldingDao().list()
        source.close()

        val document = BackupCodec.decode(json)
        assertEquals(listOf("m1", "m2", "m3"), document.movements.map { it.id })

        val target = buildInMemoryTestDatabase()
        repositoryFor(target).importJson(json)

        assertEquals(movements, target.movementDao().list())
        assertEquals(holdings, target.portfolioHoldingDao().list())
        target.close()
    }

    @Test
    fun `the units of a holding come from its movements whatever the file says`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        val file = BackupDocument(
            exportedAt = "2026-10-06T10:00:00Z",
            portfolios = listOf(PortfolioBackup(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
            portfolioHoldings = listOf(
                holdingBackup("fa", units = 999 * ONE_UNIT),
                holdingBackup("fb", units = 1),
                holdingBackup("fc", units = 7 * ONE_UNIT),
            ),
            movements = listOf(
                MovementBackup("m1", "fa", "BUY", 20_000, 10 * ONE_UNIT, 100_000, "EUR", 0, 100_000, 0, sequence = 1),
                MovementBackup("m2", "fa", "TRANSFER_OUT", 20_010, 4 * ONE_UNIT, transferId = "t1", sequence = 2),
                MovementBackup("m3", "fb", "TRANSFER_IN", 20_010, 4 * ONE_UNIT, transferId = "t1", sequence = 3),
                MovementBackup("m4", "fb", "SELL", 20_020, 4 * ONE_UNIT, 50_000, "EUR", 0, 50_000, 0, sequence = 4),
            ),
        )

        repositoryFor(db).importJson(BackupCodec.encode(file))

        val units = db.portfolioHoldingDao().list().associate { it.id to it.unitsScaled }
        assertEquals(mapOf("fa" to 6 * ONE_UNIT, "fb" to 0L, "fc" to 7 * ONE_UNIT), units)
        // 6 units of fa at 10 EUR: the closed fb and the untouched fc (7 units, no movements) count as they are.
        assertEquals(6_000L + 7_000L, db.netWorthDao().find("2026-10")?.assetsMinor)
        db.close()
    }

    @Test
    fun `importing a file without movements replaces the ones on the device`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val v12 = """{"format": "patrimonio-backup", "version": 12, "exportedAt": "2026-01-01T00:00:00Z"}"""

        repositoryFor(db).importJson(v12)

        assertTrue(db.movementDao().list().isEmpty())
        assertTrue(db.portfolioHoldingDao().list().isEmpty())
        db.close()
    }

    @Test
    fun `a file whose history is impossible leaves the device untouched`() = runTest {
        val db = buildInMemoryTestDatabase()
        testSeedingGate(db).await()
        populate(db)
        val holdingsBefore = db.portfolioHoldingDao().list()
        val movementsBefore = db.movementDao().list()
        val oversell = BackupDocument(
            exportedAt = "2026-10-06T10:00:00Z",
            portfolios = listOf(PortfolioBackup(TEST_PORTFOLIO_ID, "Mi cartera", 0)),
            portfolioHoldings = listOf(holdingBackup("fa", units = ONE_UNIT)),
            movements = listOf(
                MovementBackup("m1", "fa", "BUY", 20_000, ONE_UNIT, 10_000, "EUR", 0, 10_000, 0, sequence = 1),
                MovementBackup("m2", "fa", "SELL", 20_001, 2 * ONE_UNIT, 20_000, "EUR", 0, 20_000, 0, sequence = 2),
            ),
        )

        val error = assertFailsWith<InvalidBackupException> {
            repositoryFor(db).importJson(BackupCodec.encode(oversell))
        }

        assertTrue("intentas dar salida" in error.message.orEmpty(), error.message)
        assertEquals(holdingsBefore, db.portfolioHoldingDao().list())
        assertEquals(movementsBefore, db.movementDao().list())
        db.close()
    }

    private fun holdingBackup(id: String, units: Long) = PortfolioHoldingBackup(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = "Fondo $id",
        type = "FUND",
        kind = "QUOTED",
        provider = "MORNINGSTAR",
        providerCode = "code-$id",
        currency = "EUR",
        unitsScaled = units,
        priceScaled = 10_000_000,
        priceDateEpochDay = 20_000,
        priceFetchedAtEpochMs = 1_000,
    )
}
