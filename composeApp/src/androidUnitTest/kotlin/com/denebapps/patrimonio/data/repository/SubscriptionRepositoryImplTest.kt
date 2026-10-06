package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.AssetEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.repository.InvalidSubscriptionException
import com.denebapps.patrimonio.domain.repository.SubscriptionNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SubscriptionRepositoryImplTest {
    private val netflix = Subscription(
        id = "sub-1",
        name = "Netflix",
        amount = CurrencyAmount(Money(1_299), Currency.EUR),
        cycle = BillingCycle.MONTHLY,
        firstChargeDate = LocalDate(2026, 1, 31),
        paidFromAssetId = "a1",
        active = true,
    )

    private suspend fun CoroutineScope.repositoryWithAsset(db: AppDatabase): SubscriptionRepositoryImpl {
        db.assetDao().insert(AssetEntity("a1", "BANK", "Cuenta", null, 150_000, "EUR"))
        return SubscriptionRepositoryImpl(db.subscriptionDao(), testSeedingGate(db))
    }

    @Test
    fun `insert then read back maps every field`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)

        repository.insert(netflix)

        assertEquals(netflix, repository.find("sub-1"))
        assertEquals(listOf(netflix), repository.observeAll().first())
        db.close()
    }

    @Test
    fun `update replaces the row and observers see it`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)
        repository.insert(netflix)
        val emissions = repository.observeAll().produceIn(backgroundScope)
        assertEquals(listOf(netflix), emissions.receive())

        val paused = netflix.copy(
            amount = CurrencyAmount(Money(1_799), Currency.USD),
            cycle = BillingCycle.YEARLY,
            paidFromAssetId = null,
            active = false,
        )
        repository.update(paused)

        assertEquals(listOf(paused), emissions.receive())
        emissions.cancel()
        db.close()
    }

    @Test
    fun `updating a missing subscription fails`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)

        assertFailsWith<SubscriptionNotFoundException> { repository.update(netflix) }
        db.close()
    }

    @Test
    fun `invalid names and amounts are rejected before touching the database`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)

        assertFailsWith<InvalidSubscriptionException> { repository.insert(netflix.copy(name = "")) }
        assertFailsWith<InvalidSubscriptionException> { repository.insert(netflix.copy(name = " Netflix")) }
        assertFailsWith<InvalidSubscriptionException> {
            repository.insert(netflix.copy(amount = CurrencyAmount(Money.ZERO, Currency.EUR)))
        }
        assertTrue(repository.observeAll().first().isEmpty())
        db.close()
    }

    @Test
    fun `a dangling paying asset is rejected by the foreign key`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)

        assertFailsWith<Exception> { repository.insert(netflix.copy(paidFromAssetId = "missing")) }
        db.close()
    }

    @Test
    fun `deleting the paying asset keeps the subscription and clears the link`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)
        repository.insert(netflix)

        db.assetDao().deleteIfUnlinked("a1")

        assertNull(repository.find("sub-1")?.paidFromAssetId)
        db.close()
    }

    @Test
    fun `delete removes the subscription`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryWithAsset(db)
        repository.insert(netflix)

        repository.deleteById("sub-1")

        assertNull(repository.find("sub-1"))
        db.close()
    }
}
