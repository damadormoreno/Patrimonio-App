package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.calc.TargetsError
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.InvalidAllocationTargetsException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeAllocationTargetRepositoryTest {
    private val sixtyForty = mapOf(AssetClass.EQUITY to 6_000, AssetClass.FIXED_INCOME to 4_000)

    @Test
    fun `targets set are observed and cleared`() = runTest {
        val fake = FakeAllocationTargetRepository()

        fake.setTargets(TEST_PORTFOLIO_ID, sixtyForty + (AssetClass.CRYPTO to 0), toleranceBp = 300)

        assertEquals(
            AllocationTargets(TEST_PORTFOLIO_ID, sixtyForty, 300),
            fake.observeTargets(TEST_PORTFOLIO_ID).first(),
        )
        fake.clearTargets(TEST_PORTFOLIO_ID)
        assertNull(fake.observeTargets(TEST_PORTFOLIO_ID).first())
        assertTrue(fake.observeAllTargets().first().isEmpty())
    }

    @Test
    fun `invalid targets and unknown portfolios are refused like the real one`() = runTest {
        val fake = FakeAllocationTargetRepository()

        val error = assertFailsWith<InvalidAllocationTargetsException> {
            fake.setTargets(TEST_PORTFOLIO_ID, mapOf(AssetClass.EQUITY to 5_000), toleranceBp = 500)
        }
        assertEquals(TargetsError.WrongSum(5_000), error.error)
        assertFailsWith<PortfolioNotFoundException> { fake.setTargets("ghost", sixtyForty, toleranceBp = 500) }
        assertFailsWith<PortfolioNotFoundException> { fake.clearTargets("ghost") }
        assertTrue(fake.setCalls.isEmpty())
    }

    @Test
    fun `the targets of a deleted portfolio are no longer observed`() = runTest {
        val portfolios = FakePortfolioCollectionRepository()
        val fake = FakeAllocationTargetRepository(portfolios)
        val second = portfolios.createPortfolio("Hijos")
        fake.seed(second.id, sixtyForty)

        portfolios.deletePortfolio(second.id, moveHoldingsTo = null)

        assertTrue(fake.observeAllTargets().first().isEmpty())
    }
}
