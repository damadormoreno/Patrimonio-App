package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.FxRateEntity
import com.denebapps.patrimonio.data.db.entity.HoldingKind
import com.denebapps.patrimonio.data.db.entity.NetWorthSnapshotEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.insertTestPortfolio
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
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

    private fun upserterFor(db: AppDatabase) = NetWorthSnapshotUpserter(
        db.assetDao(),
        db.liabilityDao(),
        db.portfolioHoldingDao(),
        db.fxRateDao(),
        db.netWorthDao(),
        may2026,
        utc,
    )

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

    private fun quotedHolding(id: String, currency: String, unitsScaled: Long, priceScaled: Long?) =
        PortfolioHoldingEntity(
            id = id,
            portfolioId = TEST_PORTFOLIO_ID,
            name = id,
            type = "FUND",
            isin = null,
            ticker = null,
            kind = HoldingKind.QUOTED.name,
            provider = "MORNINGSTAR",
            providerCode = id,
            currency = currency,
            unitsScaled = unitsScaled,
            priceScaled = priceScaled,
            priceDateEpochDay = priceScaled?.let { 20_000L },
            priceFetchedAtEpochMs = priceScaled?.let { 1L },
            valueMinor = null,
            valuedAtEpochMs = null,
        )

    private fun manualHolding(id: String, valueMinor: Long) = PortfolioHoldingEntity(
        id = id,
        portfolioId = TEST_PORTFOLIO_ID,
        name = id,
        type = "OTHER",
        isin = null,
        ticker = null,
        kind = HoldingKind.MANUAL.name,
        provider = null,
        providerCode = null,
        currency = "EUR",
        unitsScaled = null,
        priceScaled = null,
        priceDateEpochDay = null,
        priceFetchedAtEpochMs = null,
        valueMinor = valueMinor,
        valuedAtEpochMs = 1L,
    )

    @Test
    fun `the portfolio total counts as assets next to the accounts`() = runTest {
        val db = buildInMemoryTestDatabase()
        val upserter = upserterFor(db)
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        // 2.5 units at 10 EUR = 25.00 EUR; a manual holding of 2,500.00 EUR.
        db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(quotedHolding("fund", "EUR", 2_500_000, 10_000_000))
        db.portfolioHoldingDao().insert(manualHolding("painting", 250_000))

        upserter.refreshCurrentMonth()

        assertEquals(100_00L + 25_00L + 250_000L, db.netWorthDao().find("2026-05")?.assetsMinor)
        db.close()
    }

    @Test
    fun `a holding quoted in another currency is converted to EUR with the cached rates`() = runTest {
        val db = buildInMemoryTestDatabase()
        val upserter = upserterFor(db)
        db.fxRateDao().upsertAll(listOf(FxRateEntity("USD", 900_000, 0)))
        // 2 units at 10 USD = 20.00 USD = 18.00 EUR at 0.9.
        db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(quotedHolding("etf", "USD", 2_000_000, 10_000_000))

        upserter.refreshCurrentMonth()

        assertEquals(18_00L, db.netWorthDao().find("2026-05")?.assetsMinor)
        db.close()
    }

    @Test
    fun `a quoted holding without a price yet adds nothing to the snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        val upserter = upserterFor(db)
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(quotedHolding("unpriced", "EUR", 2_500_000, null))

        upserter.refreshCurrentMonth()

        assertEquals(100_00L, db.netWorthDao().find("2026-05")?.assetsMinor)
        db.close()
    }

    @Test
    fun `the portfolio never touches a past month snapshot`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.netWorthDao().upsert(NetWorthSnapshotEntity("2026-04", 50_00, 0))
        db.insertTestPortfolio()
        db.portfolioHoldingDao().insert(manualHolding("painting", 250_000))

        upserterFor(db).refreshCurrentMonth()

        assertEquals(50_00L, db.netWorthDao().find("2026-04")?.assetsMinor)
        assertEquals(250_000L, db.netWorthDao().find("2026-05")?.assetsMinor)
        db.close()
    }
}
