package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.entity.LiabilityEntity
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.domain.repository.AccountTypeNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class AccountTypeRepositoryImplTest {
    @Test
    fun `types are created per kind in order, trimmed, and can be edited`() = runTest {
        val db = buildInMemoryTestDatabase()
        var next = 0
        val repository = AccountTypeRepositoryImpl(db) { "t${++next}" }

        repository.create(AccountKind.ASSET, " Relojes ", "⌚", TypeColor.GOLD)
        repository.create(AccountKind.LIABILITY, "Deuda familiar", "👪", TypeColor.TERRACOTTA)
        repository.create(AccountKind.ASSET, "Arte", "🖼️", TypeColor.PURPLE)
        repository.update("t3", "Obras de arte", "🎨", TypeColor.SAGE)

        // Positions count per kind; the list is ordered by position, then name.
        assertEquals(
            listOf(
                CustomAccountType("t2", AccountKind.LIABILITY, "Deuda familiar", "👪", TypeColor.TERRACOTTA, 0),
                CustomAccountType("t1", AccountKind.ASSET, "Relojes", "⌚", TypeColor.GOLD, 0),
                CustomAccountType("t3", AccountKind.ASSET, "Obras de arte", "🎨", TypeColor.SAGE, 1),
            ),
            repository.observeAll().first(),
        )
        db.close()
    }

    @Test
    fun `deleting a type moves its accounts to Otros of the same kind`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = AccountTypeRepositoryImpl(db) { "watch" }
        repository.create(AccountKind.ASSET, "Relojes", "⌚", TypeColor.GOLD)
        db.assetDao().insert(AssetEntity("a1", "watch", "Rolex", null, 900_000, "EUR", emoji = "⌚"))
        db.assetDao().insert(AssetEntity("a2", Asset.AssetGroup.BANK, "Cuenta", null, 1_000, "EUR"))

        repository.delete("watch")

        assertEquals(
            listOf(Asset.AssetGroup.OTHER to "⌚", Asset.AssetGroup.BANK to null),
            db.assetDao().list().sortedBy { it.id }.map { it.group to it.emoji },
        )
        assertEquals(emptyList(), repository.observeAll().first())
        assertFailsWith<AccountTypeNotFoundException> { repository.delete("watch") }
        db.close()
    }

    @Test
    fun `a liability type falls back to Otras deudas`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = AccountTypeRepositoryImpl(db) { "family" }
        repository.create(AccountKind.LIABILITY, "Familia", "👪", TypeColor.BLUE)
        db.liabilityDao().insert(LiabilityEntity("l1", "family", "Préstamo de mamá", null, 50_000, "EUR"))

        repository.delete("family")

        assertEquals(Liability.LiabilityGroup.OTHER, db.liabilityDao().list().single().group)
        db.close()
    }

    @Test
    fun `editing a missing type fails`() = runTest {
        val db = buildInMemoryTestDatabase()

        assertFailsWith<AccountTypeNotFoundException> {
            AccountTypeRepositoryImpl(db).update("x", "Nombre", "🙂", TypeColor.GREY)
        }
        db.close()
    }
}
