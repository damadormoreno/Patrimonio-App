package com.denebapps.patrimonio.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 3 -> 4: savings goal ids go from an autoincrement INTEGER to a UUID string, so goals created on
 * different devices can later be synced without colliding. SQLite cannot change a primary key's type,
 * so the goal table and both event tables (whose `goalId` follows) are rebuilt the way Room's own
 * auto-migrations do it: create `_new_*`, copy, drop, rename. Room runs migrations with foreign keys
 * off and turns them on when opening, hence the explicit check at the end.
 *
 * Every existing goal gets a fresh random UUID. Its old id moves to `createdAtEpochMs`, which now
 * orders the goals: old ids were increasing, so migrated goals keep their order and sort before any
 * goal created afterwards, whose value is a real instant. Event ids stay local autoincrement keys.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_3_4_STATEMENTS.forEach(connection::execSQL)
        connection.prepare("PRAGMA foreign_key_check").use { violations ->
            check(!violations.step()) { "Migration 3 -> 4 left foreign key violations" }
        }
    }
}

/** A random (version 4) UUID, evaluated once per row. `random() & 3` picks the variant nibble. */
private const val RANDOM_UUID_SQL =
    "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (random() & 3), 1) || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

private val MIGRATION_3_4_STATEMENTS = listOf(
    "CREATE TABLE `_goal_id_map` (`oldId` INTEGER PRIMARY KEY NOT NULL, `newId` TEXT NOT NULL)",
    "INSERT INTO `_goal_id_map` (`oldId`, `newId`) SELECT `id`, $RANDOM_UUID_SQL FROM `savings_goals`",
    "CREATE TABLE `_new_savings_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
        "`targetMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `targetDateEpochDay` INTEGER, " +
        "`linkedAssetId` TEXT, `lifecycle` TEXT NOT NULL, `linkedGroupId` TEXT, " +
        "`createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`linkedAssetId`) REFERENCES `assets`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
        "FOREIGN KEY(`linkedGroupId`) REFERENCES `account_groups`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "INSERT INTO `_new_savings_goals` (`id`, `name`, `targetMinor`, `currency`, `targetDateEpochDay`, " +
        "`linkedAssetId`, `lifecycle`, `linkedGroupId`, `createdAtEpochMs`) " +
        "SELECT m.`newId`, g.`name`, g.`targetMinor`, g.`currency`, g.`targetDateEpochDay`, " +
        "g.`linkedAssetId`, g.`lifecycle`, g.`linkedGroupId`, g.`id` " +
        "FROM `savings_goals` g JOIN `_goal_id_map` m ON m.`oldId` = g.`id`",
    "CREATE TABLE `_new_savings_goal_allocation_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`goalId` TEXT NOT NULL, `deltaMinor` INTEGER NOT NULL, `timestampEpochMs` INTEGER NOT NULL, " +
        "FOREIGN KEY(`goalId`) REFERENCES `savings_goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "INSERT INTO `_new_savings_goal_allocation_events` (`id`, `goalId`, `deltaMinor`, `timestampEpochMs`) " +
        "SELECT e.`id`, m.`newId`, e.`deltaMinor`, e.`timestampEpochMs` " +
        "FROM `savings_goal_allocation_events` e JOIN `_goal_id_map` m ON m.`oldId` = e.`goalId`",
    "CREATE TABLE `_new_savings_goal_link_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`goalId` TEXT NOT NULL, `fromAssetId` TEXT, `toAssetId` TEXT, `kind` TEXT NOT NULL, " +
        "`timestampEpochMs` INTEGER NOT NULL, `fromGroupId` TEXT, `toGroupId` TEXT, " +
        "FOREIGN KEY(`goalId`) REFERENCES `savings_goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "INSERT INTO `_new_savings_goal_link_events` (`id`, `goalId`, `fromAssetId`, `toAssetId`, `kind`, " +
        "`timestampEpochMs`, `fromGroupId`, `toGroupId`) " +
        "SELECT e.`id`, m.`newId`, e.`fromAssetId`, e.`toAssetId`, e.`kind`, e.`timestampEpochMs`, " +
        "e.`fromGroupId`, e.`toGroupId` " +
        "FROM `savings_goal_link_events` e JOIN `_goal_id_map` m ON m.`oldId` = e.`goalId`",
    "DROP TABLE `savings_goal_allocation_events`",
    "DROP TABLE `savings_goal_link_events`",
    "DROP TABLE `savings_goals`",
    "DROP TABLE `_goal_id_map`",
    "ALTER TABLE `_new_savings_goals` RENAME TO `savings_goals`",
    "ALTER TABLE `_new_savings_goal_allocation_events` RENAME TO `savings_goal_allocation_events`",
    "ALTER TABLE `_new_savings_goal_link_events` RENAME TO `savings_goal_link_events`",
    "CREATE INDEX IF NOT EXISTS `index_savings_goals_linkedAssetId` ON `savings_goals` (`linkedAssetId`)",
    "CREATE INDEX IF NOT EXISTS `index_savings_goals_linkedGroupId` ON `savings_goals` (`linkedGroupId`)",
    "CREATE INDEX IF NOT EXISTS `index_savings_goal_allocation_events_goalId_timestampEpochMs_id` " +
        "ON `savings_goal_allocation_events` (`goalId`, `timestampEpochMs`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_savings_goal_link_events_goalId_timestampEpochMs_id` " +
        "ON `savings_goal_link_events` (`goalId`, `timestampEpochMs`, `id`)",
)
