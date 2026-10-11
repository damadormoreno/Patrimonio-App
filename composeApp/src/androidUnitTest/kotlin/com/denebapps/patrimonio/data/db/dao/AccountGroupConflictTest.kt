package com.denebapps.patrimonio.data.db.dao

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AccountGroupEntity
import com.denebapps.patrimonio.data.db.entity.AccountGroupMemberEntity
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * A group row is never deleted and re-inserted: SQLite's REPLACE does exactly that, which fires the
 * `ON DELETE SET NULL` of `savings_goals.linkedGroupId` and the CASCADE of the members, unlinking goals silently.
 */
@RunWith(RobolectricTestRunner::class)
class AccountGroupConflictTest {
    @Test
    fun `inserting a group whose id exists fails and leaves its goals and members untouched`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Savings", true, 0))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))
        db.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
                id = "goal-1",
                name = "Trip",
                targetMinor = 100_000,
                currency = "EUR",
                targetDateEpochDay = null,
                lifecycle = "OPEN",
                linkedGroupId = "g1",
                createdAtEpochMs = 0,
            ),
        )

        assertFails { dao.insertGroup(AccountGroupEntity("g1", "Renamed", false, 5)) }

        assertEquals(AccountGroupEntity("g1", "Savings", true, 0), dao.findGroup("g1"))
        assertEquals(listOf(AccountGroupMemberEntity("g1", "a1")), dao.listMembers())
        assertEquals("g1", db.savingsGoalDao().findGoal("goal-1")?.linkedGroupId)
        db.close()
    }

    @Test
    fun `updating a group in place keeps its goals and members`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val dao = db.accountGroupDao()
        dao.insertGroup(AccountGroupEntity("g1", "Savings", true, 2))
        dao.insertMember(AccountGroupMemberEntity("g1", "a1"))
        db.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
                id = "goal-1",
                name = "Trip",
                targetMinor = 100_000,
                currency = "EUR",
                targetDateEpochDay = null,
                lifecycle = "OPEN",
                linkedGroupId = "g1",
                createdAtEpochMs = 0,
            ),
        )

        assertEquals(1, dao.updateGroup("g1", "Colchón", showBalance = false))

        assertEquals(AccountGroupEntity("g1", "Colchón", false, 2), dao.findGroup("g1"))
        assertEquals(listOf(AccountGroupMemberEntity("g1", "a1")), dao.listMembers())
        assertEquals("g1", db.savingsGoalDao().findGoal("goal-1")?.linkedGroupId)
        db.close()
    }
}
