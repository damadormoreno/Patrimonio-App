package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

@RunWith(RobolectricTestRunner::class)
class NetWorthSnapshotUpserterTest {
    private val may2026 = FixedClock(Instant.parse("2026-05-15T12:00:00Z"))
    private val utc: () -> TimeZone = { TimeZone.UTC }

    private fun upserterFor(db: AppDatabase) =
        NetWorthSnapshotUpserter(db.assetDao(), db.liabilityDao(), db.fxRateDao(), db.netWorthDao(), may2026, utc)

    @Test
    fun `an asset change upserts the current-month snapshot to reflect the new totals`() = runTest {
        val db = buildInMemoryTestDatabase()
        val upserter = upserterFor(db)

        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        upserter.refreshCurrentMonth()

        val snapshot = db.netWorthDao().find("2026-05")
        assertEquals(100_00L, snapshot?.assetsMinor)
        db.close()
    }

    @Test
    fun `mutating an asset in the current month does not modify a past month snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.netWorthDao().upsert(NetWorthSnapshotEntity("2026-04", 50_00, 0))
        val upserter = upserterFor(db)

        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        upserter.refreshCurrentMonth()

        val aprilSnapshot = db.netWorthDao().find("2026-04")
        assertEquals(50_00L, aprilSnapshot?.assetsMinor)
        db.close()
    }

    @Test
    fun `refreshCurrentMonth runs inside the caller's write transaction`() = runTest {
        val db = buildInMemoryTestDatabase()
        val upserter = upserterFor(db)

        db.writeTransaction {
            db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
            upserter.refreshCurrentMonth()
        }

        assertEquals(100_00L, db.netWorthDao().find("2026-05")?.assetsMinor)
        db.close()
    }
}
