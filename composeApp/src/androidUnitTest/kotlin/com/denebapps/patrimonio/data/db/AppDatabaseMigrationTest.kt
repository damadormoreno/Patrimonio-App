package com.denebapps.patrimonio.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room schema migrations against the committed schema baselines in `composeApp/schemas` (exposed to
 * unit tests as Android assets through `build.gradle.kts`). Each test builds a real database file at the
 * old version with [MigrationTestHelper], fills it with data, then migrates it.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile = context.getDatabasePath(DATABASE_NAME)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = databaseFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
        databaseFactory = { AppDatabaseConstructor.initialize() },
    )

    @Test
    fun `migration 1 to 2 keeps goals ledgers and links and leaves the new group links empty`() {
        helper.createDatabase(1).use { connection -> connection.seedVersion1() }

        helper.runMigrationsAndValidate(2, emptyList()).use { connection ->
            connection.prepare(
                "SELECT id, name, linkedAssetId, linkedGroupId, lifecycle FROM savings_goals ORDER BY id",
            ).use { goals ->
                assertEquals(true, goals.step())
                assertEquals(7L, goals.getLong(0))
                assertEquals("Viaje", goals.getText(1))
                assertEquals("a1", goals.getText(2))
                assertEquals(true, goals.isNull(3))
                assertEquals("OPEN", goals.getText(4))
                assertEquals(true, goals.step())
                assertEquals(12L, goals.getLong(0))
                assertEquals(true, goals.isNull(2))
                assertEquals(true, goals.isNull(3))
                assertEquals("CANCELLED", goals.getText(4))
                assertEquals(false, goals.step())
            }
            // Children of the re-created goals table must survive it (their FK is ON DELETE CASCADE).
            assertEquals(2L, connection.count("savings_goal_allocation_events"))
            connection.prepare(
                "SELECT goalId, fromAssetId, toAssetId, fromGroupId, toGroupId, kind FROM savings_goal_link_events",
            ).use { links ->
                assertEquals(true, links.step())
                assertEquals(7L, links.getLong(0))
                assertEquals(true, links.isNull(1))
                assertEquals("a1", links.getText(2))
                assertEquals(true, links.isNull(3))
                assertEquals(true, links.isNull(4))
                assertEquals("LINK", links.getText(5))
                assertEquals(false, links.step())
            }
            assertEquals(1L, connection.count("account_group_members"))
            assertEquals(1L, connection.count("assets"))
        }
    }

    @Test
    fun `a version 1 database opens through the app and keeps its asset link`() = runBlocking {
        helper.createDatabase(1).use { connection -> connection.seedVersion1() }

        val db = Room.databaseBuilder<AppDatabase>(context = context, name = databaseFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .configureAppDatabase()
            .build()
        try {
            val dao = db.savingsGoalDao()
            val goalId = dao.listAllGoals().first { it.name == "Viaje" }.id
            assertEquals(listOf("a1"), dao.listLinkedAssetIds(goalId))
            assertNull(dao.findGoal(goalId)?.linkedGroupId)
            assertEquals(2, dao.listAllocationHistory(goalId).size)
            assertEquals(1, dao.listAllLinkEvents().size)

            dao.deleteLinkedAssets(goalId, listOf("a1"))
            dao.updateLinkedGroup(goalId, "g1")
            assertEquals("g1", dao.findGoal(goalId)?.linkedGroupId)
            assertFails { dao.updateLinkedGroup(goalId, "missing") }

            // The group foreign key is live after migration: deleting the group unlinks the goal.
            db.accountGroupDao().deleteGroup("g1")
            assertNull(dao.findGoal(goalId)?.linkedGroupId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 2 to 3 adds an empty subscriptions table wired to assets`() {
        helper.createDatabase(2).use { connection ->
            connection.execSQL(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR')",
            )
        }

        helper.runMigrationsAndValidate(3, emptyList()).use { connection ->
            assertEquals(1L, connection.count("assets"))
            assertEquals(0L, connection.count("subscriptions"))
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL(
                "INSERT INTO subscriptions " +
                    "(id, name, amountMinor, currency, cycle, firstChargeEpochDay, paidFromAssetId, active) " +
                    "VALUES ('s1', 'Netflix', 1299, 'EUR', 'MONTHLY', 20000, 'a1', 1)",
            )
            connection.execSQL("DELETE FROM assets WHERE id = 'a1'")
            connection.prepare("SELECT paidFromAssetId FROM subscriptions WHERE id = 's1'").use { row ->
                assertEquals(true, row.step())
                assertEquals(true, row.isNull(0))
            }
        }
    }

    @Test
    fun `migration 3 to 4 gives goals UUID ids and keeps their order ledgers and links`() {
        // Version 1 inserts are valid at version 3: the later columns are all nullable.
        helper.createDatabase(3).use { connection -> connection.seedVersion1() }

        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { connection ->
            val goals = connection.prepare(
                "SELECT id, name, createdAtEpochMs FROM savings_goals ORDER BY createdAtEpochMs, id",
            ).use { rows ->
                buildList { while (rows.step()) add(GoalRow(rows.getText(0), rows.getText(1), rows.getLong(2))) }
            }
            assertEquals(listOf("Viaje", "Coche"), goals.map { it.name })
            assertEquals(listOf(7L, 12L), goals.map { it.createdAtEpochMs })
            goals.forEach { assertTrue(UUID_V4.matches(it.id), "not a UUID: ${it.id}") }
            assertNotEquals(goals[0].id, goals[1].id)
            val viajeId = goals[0].id

            connection.prepare("SELECT id, goalId FROM savings_goal_allocation_events ORDER BY id").use { rows ->
                assertEquals(true, rows.step())
                assertEquals(3L, rows.getLong(0))
                assertEquals(viajeId, rows.getText(1))
                assertEquals(true, rows.step())
                assertEquals(4L, rows.getLong(0))
                assertEquals(viajeId, rows.getText(1))
                assertEquals(false, rows.step())
            }
            connection.prepare("SELECT goalId, toAssetId FROM savings_goal_link_events").use { rows ->
                assertEquals(true, rows.step())
                assertEquals(viajeId, rows.getText(0))
                assertEquals("a1", rows.getText(1))
                assertEquals(false, rows.step())
            }
            assertFalse(connection.tableExists("_goal_id_map"))

            // Event ids keep counting from the old maximum, and deleting a goal still cascades.
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL(
                "INSERT INTO savings_goal_allocation_events (goalId, deltaMinor, timestampEpochMs) " +
                    "VALUES ('$viajeId', 100, 3000)",
            )
            connection.prepare("SELECT MAX(id) FROM savings_goal_allocation_events").use { row ->
                row.step()
                assertEquals(5L, row.getLong(0))
            }
            connection.execSQL("DELETE FROM savings_goals WHERE id = '$viajeId'")
            assertEquals(0L, connection.count("savings_goal_allocation_events"))
            assertEquals(0L, connection.count("savings_goal_link_events"))
        }
    }

    @Test
    fun `migration 4 to 5 moves the asset link into the goal accounts table`() {
        helper.createDatabase(4).use { connection ->
            listOf(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR')",
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency) " +
                    "VALUES ('a2', 'BANK', 'Otra', NULL, 1000, 'USD')",
                "INSERT INTO account_groups (id, name, showBalance, sortOrder) VALUES ('g1', 'Día a día', 1, 0)",
                "INSERT INTO savings_goals ($GOAL_COLUMNS_V4) VALUES ('u1', 'Viaje', 300000, 'EUR', 20800, 'a1', " +
                    "'OPEN', NULL, 7)",
                "INSERT INTO savings_goals ($GOAL_COLUMNS_V4) VALUES ('u2', 'Coche', 900000, 'EUR', NULL, NULL, " +
                    "'CANCELLED', 'g1', 12)",
                "INSERT INTO savings_goal_allocation_events (id, goalId, deltaMinor, timestampEpochMs) " +
                    "VALUES (3, 'u1', 50000, 1000)",
                "INSERT INTO savings_goal_link_events (id, goalId, fromAssetId, toAssetId, kind, timestampEpochMs) " +
                    "VALUES (5, 'u1', NULL, 'a1', 'LINK', 1000)",
            ).forEach(connection::execSQL)
        }

        helper.runMigrationsAndValidate(5, listOf(MIGRATION_4_5)).use { connection ->
            connection.prepare("SELECT id, name, linkedGroupId, createdAtEpochMs FROM savings_goals ORDER BY id")
                .use { rows ->
                    assertEquals(true, rows.step())
                    assertEquals("u1", rows.getText(0))
                    assertEquals(true, rows.isNull(2))
                    assertEquals(7L, rows.getLong(3))
                    assertEquals(true, rows.step())
                    assertEquals("u2", rows.getText(0))
                    assertEquals("g1", rows.getText(2))
                    assertEquals(false, rows.step())
                }
            connection.prepare("SELECT goalId, assetId FROM savings_goal_assets").use { rows ->
                assertEquals(true, rows.step())
                assertEquals("u1", rows.getText(0))
                assertEquals("a1", rows.getText(1))
                assertEquals(false, rows.step())
            }
            assertEquals(1L, connection.count("savings_goal_allocation_events"))
            assertEquals(1L, connection.count("savings_goal_link_events"))

            // Both foreign keys of the new table are live: a goal follows a second account, deleting an asset
            // drops only its row, deleting the goal drops the rest along with its history.
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("INSERT INTO savings_goal_assets (goalId, assetId) VALUES ('u1', 'a2')")
            assertFails { connection.execSQL("INSERT INTO savings_goal_assets (goalId, assetId) VALUES ('u1', 'x')") }
            connection.execSQL("DELETE FROM assets WHERE id = 'a1'")
            assertEquals(1L, connection.count("savings_goal_assets"))
            connection.execSQL("DELETE FROM savings_goals WHERE id = 'u1'")
            assertEquals(0L, connection.count("savings_goal_assets"))
            assertEquals(0L, connection.count("savings_goal_link_events"))
        }
    }

    @Test
    fun `migration 5 to 6 adds custom account types and an empty emoji to every account`() {
        helper.createDatabase(5).use { connection ->
            listOf(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR')",
                "INSERT INTO liabilities (id, `group`, name, subtitle, amountMinor, currency) " +
                    "VALUES ('l1', 'CARD', 'Visa', NULL, 30000, 'EUR')",
            ).forEach(connection::execSQL)
        }

        helper.runMigrationsAndValidate(6, emptyList()).use { connection ->
            connection.prepare("SELECT `group`, emoji FROM assets").use { rows ->
                assertTrue(rows.step())
                assertEquals("BANK", rows.getText(0))
                assertTrue(rows.isNull(1))
            }
            connection.prepare("SELECT `group`, emoji FROM liabilities").use { rows ->
                assertTrue(rows.step())
                assertEquals("CARD", rows.getText(0))
                assertTrue(rows.isNull(1))
            }
            assertEquals(0L, connection.count("account_types"))
            connection.execSQL(
                "INSERT INTO account_types (id, kind, name, emoji, color, position) " +
                    "VALUES ('t1', 'ASSET', 'Relojes', '⌚', 'GOLD', 0)",
            )
            connection.execSQL("UPDATE assets SET `group` = 't1', emoji = '🏦' WHERE id = 'a1'")
            assertEquals(1L, connection.count("account_types"))
        }
    }

    @Test
    fun `migration 6 to 7 drops subscriptions and keeps every other table`() {
        helper.createDatabase(6).use { connection ->
            listOf(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency, emoji) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR', NULL)",
                "INSERT INTO liabilities (id, `group`, name, subtitle, amountMinor, currency, emoji) " +
                    "VALUES ('l1', 'CARD', 'Visa', NULL, 30000, 'EUR', NULL)",
                "INSERT INTO account_groups (id, name, showBalance, sortOrder) VALUES ('g1', 'Día a día', 1, 0)",
                "INSERT INTO account_group_members (groupId, assetId) VALUES ('g1', 'a1')",
                "INSERT INTO savings_goals (id, name, targetMinor, currency, targetDateEpochDay, lifecycle, " +
                    "linkedGroupId, createdAtEpochMs) VALUES ('u1', 'Viaje', 300000, 'EUR', NULL, 'OPEN', NULL, 7)",
                "INSERT INTO savings_goal_allocation_events (id, goalId, deltaMinor, timestampEpochMs) " +
                    "VALUES (3, 'u1', 50000, 1000)",
                "INSERT INTO subscriptions " +
                    "(id, name, amountMinor, currency, cycle, firstChargeEpochDay, paidFromAssetId, active) " +
                    "VALUES ('s1', 'Netflix', 1299, 'EUR', 'MONTHLY', 20000, 'a1', 1)",
            ).forEach(connection::execSQL)
        }

        helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7)).use { connection ->
            assertFalse(connection.tableExists("subscriptions"))
            assertEquals(1L, connection.count("assets"))
            assertEquals(1L, connection.count("liabilities"))
            assertEquals(1L, connection.count("account_groups"))
            assertEquals(1L, connection.count("account_group_members"))
            assertEquals(1L, connection.count("savings_goals"))
            assertEquals(1L, connection.count("savings_goal_allocation_events"))
            connection.prepare("SELECT name, amountMinor FROM assets WHERE id = 'a1'").use { row ->
                assertTrue(row.step())
                assertEquals("Cuenta", row.getText(0))
                assertEquals(150_000L, row.getLong(1))
            }
        }
    }

    @Test
    fun `a version 6 database with subscriptions opens through the app and keeps its accounts`() = runBlocking {
        helper.createDatabase(6).use { connection ->
            listOf(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency, emoji) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR', NULL)",
                "INSERT INTO subscriptions " +
                    "(id, name, amountMinor, currency, cycle, firstChargeEpochDay, paidFromAssetId, active) " +
                    "VALUES ('s1', 'Netflix', 1299, 'EUR', 'MONTHLY', 20000, 'a1', 1)",
            ).forEach(connection::execSQL)
        }

        val db = Room.databaseBuilder<AppDatabase>(context = context, name = databaseFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .configureAppDatabase()
            .build()
        try {
            assertEquals(listOf("Cuenta"), db.assetDao().list().map { it.name })
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 7 to 8 adds an empty writable portfolio table and keeps every other table`() {
        helper.createDatabase(7).use { connection ->
            listOf(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency, emoji) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR', NULL)",
                "INSERT INTO liabilities (id, `group`, name, subtitle, amountMinor, currency, emoji) " +
                    "VALUES ('l1', 'CARD', 'Visa', NULL, 30000, 'EUR', NULL)",
                "INSERT INTO account_groups (id, name, showBalance, sortOrder) VALUES ('g1', 'Día a día', 1, 0)",
                "INSERT INTO account_group_members (groupId, assetId) VALUES ('g1', 'a1')",
                "INSERT INTO savings_goals (id, name, targetMinor, currency, targetDateEpochDay, lifecycle, " +
                    "linkedGroupId, createdAtEpochMs) VALUES ('u1', 'Viaje', 300000, 'EUR', NULL, 'OPEN', NULL, 7)",
                "INSERT INTO net_worth_snapshots (yearMonth, assetsMinor, liabsMinor) " +
                    "VALUES ('2026-09', 150000, 30000)",
            ).forEach(connection::execSQL)
        }

        helper.runMigrationsAndValidate(8, emptyList()).use { connection ->
            assertEquals(1L, connection.count("assets"))
            assertEquals(1L, connection.count("liabilities"))
            assertEquals(1L, connection.count("account_groups"))
            assertEquals(1L, connection.count("account_group_members"))
            assertEquals(1L, connection.count("savings_goals"))
            assertEquals(1L, connection.count("net_worth_snapshots"))
            assertEquals(0L, connection.count("portfolio_holdings"))

            connection.execSQL(
                "INSERT INTO portfolio_holdings (id, name, type, isin, ticker, kind, provider, providerCode, " +
                    "currency, unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs) VALUES ('p1', 'Vanguard Global', 'FUND', 'IE00B03HCZ61', NULL, 'QUOTED', " +
                    "'MORNINGSTAR', '0P00012PP6', 'EUR', 8100001, 12345678, 20000, 1000, NULL, NULL)",
            )
            connection.execSQL(
                "INSERT INTO portfolio_holdings (id, name, type, isin, ticker, kind, provider, providerCode, " +
                    "currency, unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs) VALUES ('p2', 'Cuadro', 'OTHER', NULL, NULL, 'MANUAL', NULL, NULL, " +
                    "'EUR', NULL, NULL, NULL, NULL, 250000, 2000)",
            )
            assertEquals(2L, connection.count("portfolio_holdings"))
        }
    }

    @Test
    fun `migration 8 to 9 keeps every holding and gives it no broker`() {
        helper.createDatabase(8).use { connection ->
            connection.execSQL(
                "INSERT INTO portfolio_holdings (id, name, type, isin, ticker, kind, provider, providerCode, " +
                    "currency, unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs) VALUES ('p1', 'Vanguard Global', 'FUND', 'IE00B03HCZ61', NULL, 'QUOTED', " +
                    "'MORNINGSTAR', '0P00012PP6', 'EUR', 8100001, 12345678, 20000, 1000, NULL, NULL)",
            )
            connection.execSQL(
                "INSERT INTO portfolio_holdings (id, name, type, isin, ticker, kind, provider, providerCode, " +
                    "currency, unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs) VALUES ('p2', 'Cuadro', 'OTHER', NULL, NULL, 'MANUAL', NULL, NULL, " +
                    "'EUR', NULL, NULL, NULL, NULL, 250000, 2000)",
            )
        }

        helper.runMigrationsAndValidate(9, emptyList()).use { connection ->
            assertEquals(2L, connection.count("portfolio_holdings"))
            connection.prepare(
                "SELECT id, name, unitsScaled, valueMinor, broker FROM portfolio_holdings ORDER BY id",
            ).use { rows ->
                assertTrue(rows.step())
                assertEquals("p1", rows.getText(0))
                assertEquals("Vanguard Global", rows.getText(1))
                assertEquals(8_100_001L, rows.getLong(2))
                assertTrue(rows.isNull(3))
                assertTrue(rows.isNull(4))
                assertTrue(rows.step())
                assertEquals("p2", rows.getText(0))
                assertTrue(rows.isNull(2))
                assertEquals(250_000L, rows.getLong(3))
                assertTrue(rows.isNull(4))
                assertFalse(rows.step())
            }

            connection.execSQL("UPDATE portfolio_holdings SET broker = 'Openbank' WHERE id = 'p1'")
            connection.prepare("SELECT broker FROM portfolio_holdings WHERE id = 'p1'").use { row ->
                assertTrue(row.step())
                assertEquals("Openbank", row.getText(0))
            }
        }
    }

    @Test
    fun `migration 9 to 10 keeps every holding without fundamentals and adds the empty history caches`() {
        helper.createDatabase(9).use { connection ->
            connection.execSQL(
                "INSERT INTO portfolio_holdings (id, name, type, isin, ticker, kind, provider, providerCode, " +
                    "currency, unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs, broker) VALUES ('p1', 'Vanguard Global', 'FUND', 'IE00B03HCZ61', NULL, " +
                    "'QUOTED', 'MORNINGSTAR', '0P00012PP6', 'EUR', 8100001, 12345678, 20000, 1000, NULL, NULL, " +
                    "'Openbank')",
            )
            connection.execSQL(
                "INSERT INTO portfolio_holdings (id, name, type, isin, ticker, kind, provider, providerCode, " +
                    "currency, unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs, broker) VALUES ('p2', 'Cuadro', 'OTHER', NULL, NULL, 'MANUAL', NULL, NULL, " +
                    "'EUR', NULL, NULL, NULL, NULL, 250000, 2000, NULL)",
            )
        }

        helper.runMigrationsAndValidate(10, emptyList()).use { connection ->
            assertEquals(2L, connection.count("portfolio_holdings"))
            connection.prepare(
                "SELECT id, unitsScaled, broker, ongoingChargePercentScaled, riskLevel, category, " +
                    "managementCompany, fundamentalsFetchedAtEpochMs FROM portfolio_holdings ORDER BY id",
            ).use { rows ->
                assertTrue(rows.step())
                assertEquals("p1", rows.getText(0))
                assertEquals(8_100_001L, rows.getLong(1))
                assertEquals("Openbank", rows.getText(2))
                (3..7).forEach { assertTrue(rows.isNull(it), "column $it of p1 must start null") }
                assertTrue(rows.step())
                assertEquals("p2", rows.getText(0))
                (3..7).forEach { assertTrue(rows.isNull(it), "column $it of p2 must start null") }
                assertFalse(rows.step())
            }
            listOf("instrument_prices", "instrument_price_sync", "fx_history", "fx_history_sync").forEach { table ->
                assertTrue(connection.tableExists(table), "$table missing")
                assertEquals(0L, connection.count(table), "$table must start empty")
            }

            connection.execSQL(
                "UPDATE portfolio_holdings SET ongoingChargePercentScaled = 1800, riskLevel = 6, " +
                    "category = 'RV Global', managementCompany = 'Vanguard' WHERE id = 'p1'",
            )
            connection.execSQL(
                "INSERT INTO instrument_prices (provider, code, epochDay, priceScaled, currency) " +
                    "VALUES ('MORNINGSTAR', '0P00012PP6', 20000, 12345678, 'EUR')",
            )
            assertEquals(1L, connection.count("instrument_prices"))
        }
    }

    @Test
    fun `migration 10 to 11 puts every holding in a new Mi cartera and keeps all their data`() {
        helper.createDatabase(10).use { connection -> connection.seedVersion10Holdings() }

        helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11)).use { connection ->
            val portfolio = connection.prepare("SELECT id, name, sortOrder FROM portfolios").use { rows ->
                assertTrue(rows.step())
                Triple(rows.getText(0), rows.getText(1), rows.getLong(2)).also { assertFalse(rows.step()) }
            }
            assertEquals("Mi cartera", portfolio.second)
            assertEquals(0L, portfolio.third)
            assertTrue(UUID_V4.matches(portfolio.first), "not a lowercase canonical UUID: ${portfolio.first}")

            assertEquals(2L, connection.count("portfolio_holdings"))
            connection.prepare(
                "SELECT id, portfolioId, name, type, isin, ticker, kind, provider, providerCode, currency, " +
                    "unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, " +
                    "valuedAtEpochMs, broker, ongoingChargePercentScaled, riskLevel, category, managementCompany, " +
                    "fundamentalsFetchedAtEpochMs FROM portfolio_holdings ORDER BY id",
            ).use { rows ->
                assertTrue(rows.step())
                assertEquals("p1", rows.getText(0))
                assertEquals(portfolio.first, rows.getText(1))
                assertEquals("Vanguard Global", rows.getText(2))
                assertEquals("FUND", rows.getText(3))
                assertEquals("IE00B03HCZ61", rows.getText(4))
                assertTrue(rows.isNull(5))
                assertEquals("QUOTED", rows.getText(6))
                assertEquals("MORNINGSTAR", rows.getText(7))
                assertEquals("0P00012PP6", rows.getText(8))
                assertEquals("EUR", rows.getText(9))
                assertEquals(8_100_001L, rows.getLong(10))
                assertEquals(12_345_678L, rows.getLong(11))
                assertEquals(20_000L, rows.getLong(12))
                assertEquals(1_000L, rows.getLong(13))
                assertTrue(rows.isNull(14))
                assertTrue(rows.isNull(15))
                assertEquals("Openbank", rows.getText(16))
                assertEquals(1_800L, rows.getLong(17))
                assertEquals(6L, rows.getLong(18))
                assertEquals("RV Global", rows.getText(19))
                assertEquals("Vanguard", rows.getText(20))
                assertEquals(5_000L, rows.getLong(21))

                assertTrue(rows.step())
                assertEquals("p2", rows.getText(0))
                assertEquals(portfolio.first, rows.getText(1))
                assertEquals("Cuadro", rows.getText(2))
                assertEquals("MANUAL", rows.getText(6))
                assertTrue(rows.isNull(7))
                assertTrue(rows.isNull(10))
                assertEquals(250_000L, rows.getLong(14))
                assertEquals(2_000L, rows.getLong(15))
                assertTrue(rows.isNull(16))
                (17..21).forEach { assertTrue(rows.isNull(it), "column $it of p2 must stay null") }
                assertFalse(rows.step())
            }
        }
    }

    @Test
    fun `migration 10 to 11 on a database without holdings still creates exactly one Mi cartera`() {
        helper.createDatabase(10).use { connection ->
            connection.execSQL(
                "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency, emoji) " +
                    "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR', NULL)",
            )
        }

        helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11)).use { connection ->
            assertEquals(1L, connection.count("portfolios"))
            assertEquals(0L, connection.count("portfolio_holdings"))
            assertEquals(1L, connection.count("assets"))
            connection.prepare("SELECT name, sortOrder FROM portfolios").use { row ->
                assertTrue(row.step())
                assertEquals("Mi cartera", row.getText(0))
                assertEquals(0L, row.getLong(1))
            }
        }
    }

    @Test
    fun `after migration 10 to 11 the portfolio foreign key is live and never deletes holdings`() {
        helper.createDatabase(10).use { connection -> connection.seedVersion10Holdings() }

        helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11)).use { connection ->
            val portfolioId = connection.prepare("SELECT id FROM portfolios").use { row ->
                row.step()
                row.getText(0)
            }
            assertTrue(connection.indexExists("index_portfolio_holdings_portfolioId"))

            connection.execSQL("PRAGMA foreign_keys = ON")
            // A holding cannot point at a portfolio that does not exist...
            assertFails {
                connection.execSQL(
                    "INSERT INTO portfolio_holdings (id, portfolioId, name, type, kind, currency) " +
                        "VALUES ('p3', 'missing', 'X', 'OTHER', 'MANUAL', 'EUR')",
                )
            }
            // ...and deleting a portfolio that still has holdings is refused instead of cascading.
            assertFails { connection.execSQL("DELETE FROM portfolios WHERE id = '$portfolioId'") }
            assertEquals(1L, connection.count("portfolios"))
            assertEquals(2L, connection.count("portfolio_holdings"))

            // Once the holdings are moved to another portfolio the first one can go.
            connection.execSQL("INSERT INTO portfolios (id, name, sortOrder) VALUES ('other', 'Hijos', 1)")
            connection.execSQL("UPDATE portfolio_holdings SET portfolioId = 'other'")
            connection.execSQL("DELETE FROM portfolios WHERE id = '$portfolioId'")
            assertEquals(2L, connection.count("portfolio_holdings"))
        }
    }

    @Test
    fun `a version 10 database opens through the app with its holdings in Mi cartera`() = runBlocking {
        helper.createDatabase(10).use { connection -> connection.seedVersion10Holdings() }

        val db = Room.databaseBuilder<AppDatabase>(context = context, name = databaseFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .configureAppDatabase()
            .build()
        try {
            val portfolio = db.portfolioDao().list().single()
            assertEquals("Mi cartera", portfolio.name)
            assertEquals(listOf("p1", "p2"), db.portfolioHoldingDao().list().map { it.id }.sorted())
            assertEquals(2, db.portfolioHoldingDao().countInPortfolio(portfolio.id))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 11 to 12 keeps every holding and gives it no asset class`() {
        helper.createDatabase(11).use { connection -> connection.seedVersion11Holdings() }

        helper.runMigrationsAndValidate(12, emptyList()).use { connection ->
            assertEquals(1L, connection.count("portfolios"))
            assertEquals(2L, connection.count("portfolio_holdings"))
            connection.prepare(
                "SELECT id, portfolioId, name, unitsScaled, valueMinor, broker, category, assetClass " +
                    "FROM portfolio_holdings ORDER BY id",
            ).use { rows ->
                assertTrue(rows.step())
                assertEquals("p1", rows.getText(0))
                assertEquals("pf-1", rows.getText(1))
                assertEquals("Vanguard Global", rows.getText(2))
                assertEquals(8_100_001L, rows.getLong(3))
                assertTrue(rows.isNull(4))
                assertEquals("Openbank", rows.getText(5))
                assertEquals("RV Global", rows.getText(6))
                assertTrue(rows.isNull(7), "assetClass must start null: it is inferred when read")
                assertTrue(rows.step())
                assertEquals("p2", rows.getText(0))
                assertEquals(250_000L, rows.getLong(4))
                assertTrue(rows.isNull(7))
                assertFalse(rows.step())
            }

            connection.execSQL("UPDATE portfolio_holdings SET assetClass = 'CRYPTO' WHERE id = 'p2'")
            connection.prepare("SELECT assetClass FROM portfolio_holdings WHERE id = 'p2'").use { row ->
                assertTrue(row.step())
                assertEquals("CRYPTO", row.getText(0))
            }
        }
    }

    @Test
    fun `a version 11 database opens through the app with its holdings unclassified`() = runBlocking {
        helper.createDatabase(11).use { connection -> connection.seedVersion11Holdings() }

        val db = Room.databaseBuilder<AppDatabase>(context = context, name = databaseFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .configureAppDatabase()
            .build()
        try {
            val holdings = db.portfolioHoldingDao().list()
            assertEquals(listOf("p1", "p2"), holdings.map { it.id }.sorted())
            assertTrue(holdings.all { it.assetClass == null })
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 12 to 13 keeps every holding and adds an empty movements table that follows its holding`() {
        helper.createDatabase(12).use { connection -> connection.seedVersion11Holdings() }

        helper.runMigrationsAndValidate(13, emptyList()).use { connection ->
            assertEquals(2L, connection.count("portfolio_holdings"))
            connection.prepare("SELECT unitsScaled, valueMinor FROM portfolio_holdings ORDER BY id").use { rows ->
                assertTrue(rows.step())
                assertEquals(8_100_001L, rows.getLong(0))
                assertTrue(rows.step())
                assertEquals(250_000L, rows.getLong(1))
            }
            assertTrue(connection.tableExists("movements"))
            assertEquals(0L, connection.count("movements"))
            assertTrue(connection.indexExists("index_movements_holdingId"))
            assertTrue(connection.indexExists("index_movements_transferId"))

            connection.execSQL("PRAGMA foreign_keys = ON")
            val insert = "INSERT INTO movements (id, holdingId, type, epochDay, unitsScaled, amountMinor, currency, " +
                "feeMinor, amountEurMinor, feeEurMinor, transferId, note, sequence) " +
                "VALUES ('%s', '%s', 'BUY', 20000, 1000000, 10000, 'EUR', 0, 10000, 0, NULL, NULL, 1)"
            connection.execSQL(insert.format("m1", "p1"))
            assertEquals(1L, connection.count("movements"))
            assertFails { connection.execSQL(insert.format("m2", "ghost")) }

            connection.execSQL("DELETE FROM portfolio_holdings WHERE id = 'p1'")
            assertEquals(0L, connection.count("movements"), "the movements must go with their holding")
        }
    }

    @Test
    fun `a version 12 database opens through the app with no movements`() = runBlocking {
        helper.createDatabase(12).use { connection -> connection.seedVersion11Holdings() }

        val db = Room.databaseBuilder<AppDatabase>(context = context, name = databaseFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .configureAppDatabase()
            .build()
        try {
            assertEquals(listOf("p1", "p2"), db.portfolioHoldingDao().list().map { it.id }.sorted())
            assertTrue(db.movementDao().list().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 13 to 14 sets the default tolerance and adds an empty targets table`() {
        helper.createDatabase(13).use { connection ->
            connection.seedVersion11Holdings()
            connection.execSQL("INSERT INTO portfolios (id, name, sortOrder) VALUES ('pf-2', 'Hijos', 1)")
        }

        helper.runMigrationsAndValidate(14, emptyList()).use { connection ->
            connection.prepare("SELECT id, name, sortOrder, rebalanceToleranceBp FROM portfolios ORDER BY sortOrder")
                .use { rows ->
                    assertTrue(rows.step())
                    assertEquals("pf-1", rows.getText(0))
                    assertEquals("Mi cartera", rows.getText(1))
                    assertEquals(0L, rows.getLong(2))
                    assertEquals(500L, rows.getLong(3))
                    assertTrue(rows.step())
                    assertEquals("pf-2", rows.getText(0))
                    assertEquals(500L, rows.getLong(3))
                    assertFalse(rows.step())
                }
            assertEquals(2L, connection.count("portfolio_holdings"))
            assertTrue(connection.tableExists("allocation_targets"))
            assertEquals(0L, connection.count("allocation_targets"))
            assertTrue(connection.indexExists("index_allocation_targets_portfolioId"))

            connection.execSQL("PRAGMA foreign_keys = ON")
            val insert = "INSERT INTO allocation_targets (portfolioId, assetClass, targetBp) VALUES ('%s', '%s', %d)"
            connection.execSQL(insert.format("pf-2", "EQUITY", 6_000))
            connection.execSQL(insert.format("pf-2", "FIXED_INCOME", 4_000))
            connection.execSQL(insert.format("pf-1", "EQUITY", 10_000))
            assertEquals(3L, connection.count("allocation_targets"))
            assertFails { connection.execSQL(insert.format("pf-2", "EQUITY", 1)) }
            assertFails { connection.execSQL(insert.format("ghost", "EQUITY", 10_000)) }

            connection.execSQL("DELETE FROM portfolios WHERE id = 'pf-2'")
            assertEquals(1L, connection.count("allocation_targets"), "the targets must go with their portfolio")
        }
    }

    @Test
    fun `a version 13 database opens through the app with the default tolerance and no targets`() = runBlocking {
        helper.createDatabase(13).use { connection -> connection.seedVersion11Holdings() }

        val db = Room.databaseBuilder<AppDatabase>(context = context, name = databaseFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .configureAppDatabase()
            .build()
        try {
            val portfolio = db.portfolioDao().list().single()
            assertEquals("pf-1", portfolio.id)
            assertEquals(500, portfolio.rebalanceToleranceBp)
            assertTrue(db.allocationTargetDao().list().isEmpty())
        } finally {
            db.close()
        }
    }

    /** Version 11 data: one portfolio with a quoted holding with broker and fundamentals, and a manual one. */
    private fun SQLiteConnection.seedVersion11Holdings() {
        execSQL("INSERT INTO portfolios (id, name, sortOrder) VALUES ('pf-1', 'Mi cartera', 0)")
        val columns = "id, portfolioId, name, type, isin, ticker, kind, provider, providerCode, currency, " +
            "unitsScaled, priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, valuedAtEpochMs, " +
            "broker, ongoingChargePercentScaled, riskLevel, category, managementCompany, " +
            "fundamentalsFetchedAtEpochMs"
        execSQL(
            "INSERT INTO portfolio_holdings ($columns) VALUES ('p1', 'pf-1', 'Vanguard Global', 'FUND', " +
                "'IE00B03HCZ61', NULL, 'QUOTED', 'MORNINGSTAR', '0P00012PP6', 'EUR', 8100001, 12345678, 20000, " +
                "1000, NULL, NULL, 'Openbank', 1800, 6, 'RV Global', 'Vanguard', 5000)",
        )
        execSQL(
            "INSERT INTO portfolio_holdings ($columns) VALUES ('p2', 'pf-1', 'Cuadro', 'OTHER', NULL, NULL, " +
                "'MANUAL', NULL, NULL, 'EUR', NULL, NULL, NULL, NULL, 250000, 2000, NULL, NULL, NULL, NULL, NULL, " +
                "NULL)",
        )
    }

    /** Version 10 data: a quoted holding with broker and fundamentals, and a manual one with neither. */
    private fun SQLiteConnection.seedVersion10Holdings() {
        val columns = "id, name, type, isin, ticker, kind, provider, providerCode, currency, unitsScaled, " +
            "priceScaled, priceDateEpochDay, priceFetchedAtEpochMs, valueMinor, valuedAtEpochMs, broker, " +
            "ongoingChargePercentScaled, riskLevel, category, managementCompany, fundamentalsFetchedAtEpochMs"
        execSQL(
            "INSERT INTO portfolio_holdings ($columns) VALUES ('p1', 'Vanguard Global', 'FUND', 'IE00B03HCZ61', " +
                "NULL, 'QUOTED', 'MORNINGSTAR', '0P00012PP6', 'EUR', 8100001, 12345678, 20000, 1000, NULL, NULL, " +
                "'Openbank', 1800, 6, 'RV Global', 'Vanguard', 5000)",
        )
        execSQL(
            "INSERT INTO portfolio_holdings ($columns) VALUES ('p2', 'Cuadro', 'OTHER', NULL, NULL, 'MANUAL', " +
                "NULL, NULL, 'EUR', NULL, NULL, NULL, NULL, 250000, 2000, NULL, NULL, NULL, NULL, NULL, NULL)",
        )
    }

    /** Version 1 data: an asset, two groups (one with a member), two goals (one linked to the asset),
     *  allocation events and one link event. */
    private fun SQLiteConnection.seedVersion1() {
        val goalColumns = "id, name, targetMinor, currency, targetDateEpochDay, linkedAssetId, lifecycle"
        val allocationColumns = "id, goalId, deltaMinor, timestampEpochMs"
        listOf(
            "INSERT INTO assets (id, `group`, name, subtitle, amountMinor, currency) " +
                "VALUES ('a1', 'BANK', 'Cuenta', NULL, 150000, 'EUR')",
            "INSERT INTO account_groups (id, name, showBalance, sortOrder) VALUES ('g1', 'Día a día', 1, 0)",
            "INSERT INTO account_groups (id, name, showBalance, sortOrder) VALUES ('g2', 'Other', 1, 1)",
            "INSERT INTO account_group_members (groupId, assetId) VALUES ('g1', 'a1')",
            "INSERT INTO savings_goals ($goalColumns) VALUES (7, 'Viaje', 300000, 'EUR', 20800, 'a1', 'OPEN')",
            "INSERT INTO savings_goals ($goalColumns) VALUES (12, 'Coche', 900000, 'EUR', NULL, NULL, 'CANCELLED')",
            "INSERT INTO savings_goal_allocation_events ($allocationColumns) VALUES (3, 7, 50000, 1000)",
            "INSERT INTO savings_goal_allocation_events ($allocationColumns) VALUES (4, 7, -20000, 2000)",
            "INSERT INTO savings_goal_link_events (id, goalId, fromAssetId, toAssetId, kind, timestampEpochMs) " +
                "VALUES (5, 7, NULL, 'a1', 'LINK', 1000)",
        ).forEach(::execSQL)
    }

    private fun SQLiteConnection.count(table: String): Long = prepare("SELECT COUNT(*) FROM $table").use { statement ->
        statement.step()
        statement.getLong(0)
    }

    private fun SQLiteConnection.tableExists(name: String): Boolean =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.bindText(1, name)
            statement.step()
        }

    private fun SQLiteConnection.indexExists(name: String): Boolean =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?").use { statement ->
            statement.bindText(1, name)
            statement.step()
        }

    private data class GoalRow(val id: String, val name: String, val createdAtEpochMs: Long)

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
        const val GOAL_COLUMNS_V4 = "id, name, targetMinor, currency, targetDateEpochDay, linkedAssetId, lifecycle, " +
            "linkedGroupId, createdAtEpochMs"
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
