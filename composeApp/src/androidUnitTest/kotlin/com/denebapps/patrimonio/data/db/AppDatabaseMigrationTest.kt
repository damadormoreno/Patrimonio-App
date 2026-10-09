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

    private data class GoalRow(val id: String, val name: String, val createdAtEpochMs: Long)

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
        const val GOAL_COLUMNS_V4 = "id, name, targetMinor, currency, targetDateEpochDay, linkedAssetId, lifecycle, " +
            "linkedGroupId, createdAtEpochMs"
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
