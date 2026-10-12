package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.calc.DEFAULT_REBALANCE_TOLERANCE_BP
import com.denebapps.patrimonio.domain.calc.TargetsError
import com.denebapps.patrimonio.domain.model.AllocationTargets
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.repository.AllocationTargetRepository
import com.denebapps.patrimonio.domain.repository.InvalidAllocationTargetsException
import com.denebapps.patrimonio.domain.repository.InvalidRebalanceToleranceException
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AllocationTargetRepositoryTest {
    private val sixtyForty = mapOf(AssetClass.EQUITY to 6_000, AssetClass.FIXED_INCOME to 4_000)

    private fun CoroutineScope.repositoryFor(db: AppDatabase): AllocationTargetRepository =
        AllocationTargetRepositoryImpl(db, testSeedingGate(db))

    private fun CoroutineScope.portfoliosFor(db: AppDatabase): PortfolioCollectionRepository {
        var generated = 0
        return PortfolioCollectionRepositoryImpl(db, testSeedingGate(db)) { "portfolio-${++generated}" }
    }

    private fun holding(id: String, portfolioId: String) = PortfolioHoldingEntity(
        id, portfolioId, "Cuadro $id", "OTHER", null, null, "MANUAL", null, null, "EUR",
        null, null, null, null, 250_000, 1L,
    )

    @Test
    fun `a portfolio has no targets until some are set`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        assertNull(repository.observeTargets(TEST_PORTFOLIO_ID).first())
        assertTrue(repository.observeAllTargets().first().isEmpty())
        db.close()
    }

    @Test
    fun `set targets are observed with their tolerance`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 300)

        assertEquals(
            AllocationTargets(TEST_PORTFOLIO_ID, sixtyForty, 300),
            repository.observeTargets(TEST_PORTFOLIO_ID).first(),
        )
        db.close()
    }

    @Test
    fun `only the classes above zero are stored`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        repository.setTargets(
            TEST_PORTFOLIO_ID,
            sixtyForty + (AssetClass.CRYPTO to 0) + (AssetClass.OTHER to 0),
            toleranceBp = 500,
        )

        assertEquals(sixtyForty, repository.observeTargets(TEST_PORTFOLIO_ID).first()?.targetsBp)
        assertEquals(2, db.allocationTargetDao().list().size)
        db.close()
    }

    @Test
    fun `setting targets again replaces the previous ones and the tolerance`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 300)

        val threeWay = mapOf(
            AssetClass.EQUITY to 5_000,
            AssetClass.MONEY_MARKET to 2_500,
            AssetClass.REAL_ESTATE to 2_500,
        )
        repository.setTargets(TEST_PORTFOLIO_ID, threeWay, toleranceBp = 0)

        assertEquals(
            AllocationTargets(TEST_PORTFOLIO_ID, threeWay, 0),
            repository.observeTargets(TEST_PORTFOLIO_ID).first(),
        )
        db.close()
    }

    @Test
    fun `the tolerance is also on the portfolio`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val portfolios = portfoliosFor(db)
        assertEquals(
            DEFAULT_REBALANCE_TOLERANCE_BP,
            portfolios.observePortfolios().first().single().rebalanceToleranceBp,
        )

        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 250)

        assertEquals(250, portfolios.observePortfolios().first().single().rebalanceToleranceBp)
        db.close()
    }

    @Test
    fun `the targets of each portfolio are kept apart and all of them are observed together`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = portfoliosFor(db).createPortfolio("Hijos")
        val hijosTargets = mapOf(AssetClass.EQUITY to 10_000)

        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 500)
        repository.setTargets(hijos.id, hijosTargets, toleranceBp = 100)

        assertEquals(sixtyForty, repository.observeTargets(TEST_PORTFOLIO_ID).first()?.targetsBp)
        assertEquals(hijosTargets, repository.observeTargets(hijos.id).first()?.targetsBp)
        assertEquals(
            mapOf(
                TEST_PORTFOLIO_ID to AllocationTargets(TEST_PORTFOLIO_ID, sixtyForty, 500),
                hijos.id to AllocationTargets(hijos.id, hijosTargets, 100),
            ),
            repository.observeAllTargets().first(),
        )
        db.close()
    }

    @Test
    fun `clearing removes the targets and keeps the tolerance`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val portfolios = portfoliosFor(db)
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 250)

        repository.clearTargets(TEST_PORTFOLIO_ID)

        assertNull(repository.observeTargets(TEST_PORTFOLIO_ID).first())
        assertTrue(repository.observeAllTargets().first().isEmpty())
        assertEquals(250, portfolios.observePortfolios().first().single().rebalanceToleranceBp)
        db.close()
    }

    @Test
    fun `clearing only touches the portfolio asked`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = portfoliosFor(db).createPortfolio("Hijos")
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 500)
        repository.setTargets(hijos.id, sixtyForty, toleranceBp = 500)

        repository.clearTargets(hijos.id)

        assertEquals(sixtyForty, repository.observeTargets(TEST_PORTFOLIO_ID).first()?.targetsBp)
        assertNull(repository.observeTargets(hijos.id).first())
        db.close()
    }

    @Test
    fun `clearing a portfolio with no targets is not an error`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        repository.clearTargets(TEST_PORTFOLIO_ID)

        assertNull(repository.observeTargets(TEST_PORTFOLIO_ID).first())
        db.close()
    }

    @Test
    fun `targets that do not add up to 100 percent are refused and nothing is written`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 300)

        val error = assertFailsWith<InvalidAllocationTargetsException> {
            repository.setTargets(TEST_PORTFOLIO_ID, mapOf(AssetClass.EQUITY to 9_000), toleranceBp = 100)
        }

        assertEquals(TargetsError.WrongSum(9_000), error.error)
        assertEquals(
            AllocationTargets(TEST_PORTFOLIO_ID, sixtyForty, 300),
            repository.observeTargets(TEST_PORTFOLIO_ID).first(),
        )
        db.close()
    }

    @Test
    fun `an empty set of targets is refused`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        val error = assertFailsWith<InvalidAllocationTargetsException> {
            repository.setTargets(TEST_PORTFOLIO_ID, emptyMap(), toleranceBp = 500)
        }

        assertEquals(TargetsError.WrongSum(0), error.error)
        assertNull(repository.observeTargets(TEST_PORTFOLIO_ID).first())
        db.close()
    }

    @Test
    fun `a target outside zero to 100 percent is refused and nothing is written`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        val error = assertFailsWith<InvalidAllocationTargetsException> {
            repository.setTargets(
                TEST_PORTFOLIO_ID,
                mapOf(AssetClass.EQUITY to 11_000, AssetClass.FIXED_INCOME to -1_000),
                toleranceBp = 500,
            )
        }

        assertEquals(TargetsError.OutOfRange(AssetClass.EQUITY, 11_000), error.error)
        assertTrue(db.allocationTargetDao().list().isEmpty())
        db.close()
    }

    @Test
    fun `a tolerance outside zero to 100 percent is refused and nothing is written`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val portfolios = portfoliosFor(db)

        for (bad in listOf(-1, 10_001)) {
            val error = assertFailsWith<InvalidRebalanceToleranceException> {
                repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = bad)
            }
            assertEquals(bad, error.toleranceBp)
        }

        assertTrue(db.allocationTargetDao().list().isEmpty())
        assertEquals(
            DEFAULT_REBALANCE_TOLERANCE_BP,
            portfolios.observePortfolios().first().single().rebalanceToleranceBp,
        )
        db.close()
    }

    @Test
    fun `the tolerance limits themselves are accepted`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 0)
        assertEquals(0, repository.observeTargets(TEST_PORTFOLIO_ID).first()?.toleranceBp)
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 10_000)
        assertEquals(10_000, repository.observeTargets(TEST_PORTFOLIO_ID).first()?.toleranceBp)
        db.close()
    }

    @Test
    fun `an unknown portfolio is not found for setting and clearing`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        assertFailsWith<PortfolioNotFoundException> { repository.setTargets("ghost", sixtyForty, toleranceBp = 500) }
        assertFailsWith<PortfolioNotFoundException> { repository.clearTargets("ghost") }

        assertTrue(db.allocationTargetDao().list().isEmpty())
        db.close()
    }

    @Test
    fun `an unknown portfolio observes no targets`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        assertNull(repository.observeTargets("ghost").first())
        db.close()
    }

    @Test
    fun `deleting a portfolio deletes its targets`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val portfolios = portfoliosFor(db)
        val hijos = portfolios.createPortfolio("Hijos")
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 500)
        repository.setTargets(hijos.id, sixtyForty, toleranceBp = 500)

        portfolios.deletePortfolio(hijos.id, moveHoldingsTo = null)

        assertNull(repository.observeTargets(hijos.id).first())
        assertEquals(setOf(TEST_PORTFOLIO_ID), repository.observeAllTargets().first().keys)
        assertEquals(2, db.allocationTargetDao().list().size)
        db.close()
    }

    @Test
    fun `deleting a portfolio with a move drops its targets and the destination keeps its own`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val portfolios = portfoliosFor(db)
        val hijos = portfolios.createPortfolio("Hijos")
        testSeedingGate(db).await()
        db.portfolioHoldingDao().insert(holding("h1", hijos.id))
        val destination = mapOf(AssetClass.EQUITY to 10_000)
        repository.setTargets(TEST_PORTFOLIO_ID, destination, toleranceBp = 200)
        repository.setTargets(hijos.id, sixtyForty, toleranceBp = 900)

        portfolios.deletePortfolio(hijos.id, moveHoldingsTo = TEST_PORTFOLIO_ID)

        assertNull(repository.observeTargets(hijos.id).first())
        assertEquals(
            AllocationTargets(TEST_PORTFOLIO_ID, destination, 200),
            repository.observeTargets(TEST_PORTFOLIO_ID).first(),
        )
        assertEquals(listOf(TEST_PORTFOLIO_ID), db.allocationTargetDao().list().map { it.portfolioId }.distinct())
        db.close()
    }

    @Test
    fun `renaming a portfolio keeps its targets`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val portfolios = portfoliosFor(db)
        repository.setTargets(TEST_PORTFOLIO_ID, sixtyForty, toleranceBp = 500)

        portfolios.renamePortfolio(TEST_PORTFOLIO_ID, "Jubilación")

        assertEquals(sixtyForty, repository.observeTargets(TEST_PORTFOLIO_ID).first()?.targetsBp)
        db.close()
    }

    @Test
    fun `the default tolerance of the entity is the domain default`() {
        assertEquals(DEFAULT_REBALANCE_TOLERANCE_BP, PortfolioEntity("id", "name", 0).rebalanceToleranceBp)
    }
}
