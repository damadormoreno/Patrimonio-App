package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.AccountGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AccountGroupRepositoryTest {
    @Test
    fun `the builtin all-accounts group is synthesized even when account_groups is empty`() = runTest {
        val db = buildInMemoryTestDatabase()
        val seedingGate = testSeedingGate(db)
        val repo = AccountGroupRepositoryImpl(db.accountGroupDao(), seedingGate)

        val groups = repo.observeAll().first()

        assertEquals(1, groups.size)
        assertEquals(AccountGroup.ALL_ACCOUNTS_ID, groups.first().id)
        assertEquals(null, groups.first().memberAssetIds)
        db.close()
    }

    @Test
    fun `overlapping membership across two explicit groups is preserved`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        val seedingGate = testSeedingGate(db)
        val repo = AccountGroupRepositoryImpl(db.accountGroupDao(), seedingGate)

        repo.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = setOf("a1")))
        repo.insertGroup(AccountGroup("g2", "Emergency", true, 1, memberAssetIds = setOf("a1")))
        val groups = repo.observeAll().first().filter { it.id != AccountGroup.ALL_ACCOUNTS_ID }

        assertEquals(2, groups.size)
        assertTrue(groups.all { it.memberAssetIds == setOf("a1") })
        db.close()
    }

    @Test
    fun `removing a member from a group keeps the other members`() = runTest {
        val db = buildInMemoryTestDatabase()
        db.assetDao().insert(AssetEntity("a1", "BANK", "Checking", null, 100_00, "EUR"))
        db.assetDao().insert(AssetEntity("a2", "CASH", "Wallet", null, 20_00, "EUR"))
        val seedingGate = testSeedingGate(db)
        val repo = AccountGroupRepositoryImpl(db.accountGroupDao(), seedingGate)
        repo.insertGroup(AccountGroup("g1", "Savings", true, 0, memberAssetIds = setOf("a1", "a2")))

        repo.setMembers("g1", setOf("a2"))
        val group = repo.observeAll().first().first { it.id == "g1" }

        assertEquals(setOf("a2"), group.memberAssetIds)
        db.close()
    }
}
