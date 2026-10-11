package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.testSeedingGate
import com.denebapps.patrimonio.domain.model.InvalidPortfolioNameException
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioNameProblem
import com.denebapps.patrimonio.domain.repository.DuplicatePortfolioNameException
import com.denebapps.patrimonio.domain.repository.LastPortfolioException
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotEmptyException
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

@RunWith(RobolectricTestRunner::class)
class PortfolioCollectionRepositoryTest {
    private val miCartera = Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0)

    private fun CoroutineScope.repositoryFor(db: AppDatabase): PortfolioCollectionRepository {
        var generated = 0
        return PortfolioCollectionRepositoryImpl(db, testSeedingGate(db)) { "portfolio-${++generated}" }
    }

    private fun holding(id: String, portfolioId: String, name: String = id) = PortfolioHoldingEntity(
        id = id,
        portfolioId = portfolioId,
        name = name,
        type = "FUND",
        isin = "IE00B03HCZ61",
        ticker = null,
        kind = "QUOTED",
        provider = "MORNINGSTAR",
        providerCode = "0P00012PP6",
        currency = "EUR",
        unitsScaled = 8_100_001,
        priceScaled = 12_345_678,
        priceDateEpochDay = 20_000,
        priceFetchedAtEpochMs = 1_000,
        valueMinor = null,
        valuedAtEpochMs = null,
        broker = "Openbank",
        ongoingChargePercentScaled = 1_800,
        riskLevel = 6,
        category = "RV Global",
        managementCompany = "Vanguard",
        fundamentalsFetchedAtEpochMs = 5_000,
    )

    private suspend fun PortfolioCollectionRepository.names() = observePortfolios().first().map { it.name }

    @Test
    fun `a new database observes only the seeded Mi cartera`() = runTest {
        val db = buildInMemoryTestDatabase()

        assertEquals(listOf(miCartera), repositoryFor(db).observePortfolios().first())
        db.close()
    }

    @Test
    fun `portfolios are ordered by sort order and then by name ignoring case`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.observePortfolios().first()
        db.portfolioDao().insert(PortfolioEntity("b", "bolsa", 1))
        db.portfolioDao().insert(PortfolioEntity("a", "Ahorro", 1))
        db.portfolioDao().insert(PortfolioEntity("z", "Zeta", 0))

        assertEquals(listOf("Mi cartera", "Zeta", "Ahorro", "bolsa"), repository.names())
        db.close()
    }

    @Test
    fun `creating a portfolio trims its name and puts it after the last one`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        val jubilacion = repository.createPortfolio("  Jubilación ")
        val hijos = repository.createPortfolio("Hijos")

        assertEquals(Portfolio("portfolio-1", "Jubilación", 1), jubilacion)
        assertEquals(Portfolio("portfolio-2", "Hijos", 2), hijos)
        assertEquals(listOf(miCartera, jubilacion, hijos), repository.observePortfolios().first())
        db.close()
    }

    @Test
    fun `a portfolio created after others were deleted still goes last`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val first = repository.createPortfolio("Uno")
        val second = repository.createPortfolio("Dos")
        repository.deletePortfolio(first.id, moveHoldingsTo = null)

        val third = repository.createPortfolio("Tres")

        assertEquals(second.sortOrder + 1, third.sortOrder)
        assertEquals(listOf("Mi cartera", "Dos", "Tres"), repository.names())
        db.close()
    }

    @Test
    fun `creating a portfolio rejects blank and too long names`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        val blank = assertFailsWith<InvalidPortfolioNameException> { repository.createPortfolio("   ") }
        val long = assertFailsWith<InvalidPortfolioNameException> { repository.createPortfolio("x".repeat(41)) }

        assertEquals(PortfolioNameProblem.BLANK, blank.problem)
        assertEquals(PortfolioNameProblem.TOO_LONG, long.problem)
        assertEquals(listOf("Mi cartera"), repository.names())
        db.close()
    }

    @Test
    fun `creating a portfolio rejects a name that exists ignoring case and surrounding spaces`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.createPortfolio("Jubilación")

        assertFailsWith<DuplicatePortfolioNameException> { repository.createPortfolio("JUBILACIÓN") }
        assertFailsWith<DuplicatePortfolioNameException> { repository.createPortfolio("  jubilación  ") }
        assertFailsWith<DuplicatePortfolioNameException> { repository.createPortfolio("mi CARTERA") }

        assertEquals(listOf("Mi cartera", "Jubilación"), repository.names())
        db.close()
    }

    @Test
    fun `the duplicate name error is explained in Spanish`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        val error = assertFailsWith<DuplicatePortfolioNameException> { repository.createPortfolio("mi cartera") }

        assertEquals("Ya existe una cartera con el nombre «mi cartera».", error.message)
        db.close()
    }

    @Test
    fun `renaming changes only the name and keeps the order and the holdings`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")
        db.portfolioHoldingDao().insert(holding("h1", hijos.id))

        repository.renamePortfolio(hijos.id, "  Para los niños ")

        val renamed = repository.observePortfolios().first().last()
        assertEquals(Portfolio(hijos.id, "Para los niños", hijos.sortOrder), renamed)
        assertEquals(hijos.id, db.portfolioHoldingDao().find("h1")?.portfolioId)
        db.close()
    }

    @Test
    fun `a portfolio can change the casing of its own name`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        repository.renamePortfolio(TEST_PORTFOLIO_ID, "MI CARTERA")
        repository.renamePortfolio(TEST_PORTFOLIO_ID, "Mi cartera ")

        assertEquals(listOf("Mi cartera"), repository.names())
        db.close()
    }

    @Test
    fun `renaming rejects blank too long and taken names and unknown portfolios`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")

        assertFailsWith<InvalidPortfolioNameException> { repository.renamePortfolio(hijos.id, " ") }
        assertFailsWith<InvalidPortfolioNameException> { repository.renamePortfolio(hijos.id, "x".repeat(41)) }
        assertFailsWith<DuplicatePortfolioNameException> { repository.renamePortfolio(hijos.id, "mi cartera") }
        assertFailsWith<PortfolioNotFoundException> { repository.renamePortfolio("nope", "Otra") }

        assertEquals(listOf("Mi cartera", "Hijos"), repository.names())
        db.close()
    }

    @Test
    fun `deleting an empty portfolio removes it`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")

        repository.deletePortfolio(hijos.id, moveHoldingsTo = null)

        assertEquals(listOf(miCartera), repository.observePortfolios().first())
        db.close()
    }

    @Test
    fun `deleting an unknown portfolio is reported`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        assertFailsWith<PortfolioNotFoundException> { repository.deletePortfolio("nope", moveHoldingsTo = null) }
        db.close()
    }

    @Test
    fun `deleting a portfolio moves its holdings and loses none of their data`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")
        db.portfolioHoldingDao().insert(holding("h1", hijos.id, name = "Fondo A"))
        db.portfolioHoldingDao().insert(holding("h2", hijos.id, name = "Fondo B"))
        db.portfolioHoldingDao().insert(holding("h3", TEST_PORTFOLIO_ID, name = "Fondo C"))
        val before = db.portfolioHoldingDao().list()

        repository.deletePortfolio(hijos.id, moveHoldingsTo = TEST_PORTFOLIO_ID)

        assertEquals(listOf(miCartera), repository.observePortfolios().first())
        val expected = before.map { it.copy(portfolioId = TEST_PORTFOLIO_ID) }
        assertEquals(expected, db.portfolioHoldingDao().list())
        db.close()
    }

    @Test
    fun `deleting a portfolio with holdings needs a target and changes nothing without one`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")
        db.portfolioHoldingDao().insert(holding("h1", hijos.id))
        val before = db.portfolioHoldingDao().list()

        val error = assertFailsWith<PortfolioNotEmptyException> {
            repository.deletePortfolio(hijos.id, moveHoldingsTo = null)
        }

        assertEquals(1, error.holdingCount)
        assertEquals(listOf("Mi cartera", "Hijos"), repository.names())
        assertEquals(before, db.portfolioHoldingDao().list())
        db.close()
    }

    @Test
    fun `the target of a delete must be another portfolio that exists and nothing changes otherwise`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")
        db.portfolioHoldingDao().insert(holding("h1", hijos.id))
        val before = db.portfolioHoldingDao().list()

        assertFailsWith<IllegalArgumentException> { repository.deletePortfolio(hijos.id, moveHoldingsTo = hijos.id) }
        assertFailsWith<PortfolioNotFoundException> { repository.deletePortfolio(hijos.id, moveHoldingsTo = "nope") }

        assertEquals(listOf("Mi cartera", "Hijos"), repository.names())
        assertEquals(before, db.portfolioHoldingDao().list())
        db.close()
    }

    @Test
    fun `the last portfolio cannot be deleted even if empty or with a target`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)

        assertFailsWith<LastPortfolioException> {
            repository.deletePortfolio(TEST_PORTFOLIO_ID, moveHoldingsTo = null)
        }
        db.portfolioHoldingDao().insert(holding("h1", TEST_PORTFOLIO_ID))
        assertFailsWith<LastPortfolioException> {
            repository.deletePortfolio(TEST_PORTFOLIO_ID, moveHoldingsTo = TEST_PORTFOLIO_ID)
        }

        assertEquals(listOf(miCartera), repository.observePortfolios().first())
        assertEquals(1, db.portfolioHoldingDao().list().size)
        db.close()
    }

    @Test
    fun `the first portfolio can be deleted once there is another one`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")
        db.portfolioHoldingDao().insert(holding("h1", TEST_PORTFOLIO_ID))

        repository.deletePortfolio(TEST_PORTFOLIO_ID, moveHoldingsTo = hijos.id)

        assertEquals(listOf(hijos), repository.observePortfolios().first())
        assertEquals(hijos.id, db.portfolioHoldingDao().find("h1")?.portfolioId)
        db.close()
    }

    @Test
    fun `moving a holding changes only its portfolio`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        val hijos = repository.createPortfolio("Hijos")
        db.portfolioHoldingDao().insert(holding("h1", TEST_PORTFOLIO_ID))
        db.portfolioHoldingDao().insert(holding("h2", TEST_PORTFOLIO_ID))
        val before = db.portfolioHoldingDao().find("h1")

        repository.moveHolding("h1", hijos.id)

        assertEquals(before?.copy(portfolioId = hijos.id), db.portfolioHoldingDao().find("h1"))
        assertEquals(TEST_PORTFOLIO_ID, db.portfolioHoldingDao().find("h2")?.portfolioId)
        repository.moveHolding("h1", hijos.id)
        assertEquals(hijos.id, db.portfolioHoldingDao().find("h1")?.portfolioId)
        db.close()
    }

    @Test
    fun `moving rejects unknown holdings and unknown portfolios`() = runTest {
        val db = buildInMemoryTestDatabase()
        val repository = repositoryFor(db)
        repository.observePortfolios().first()
        db.portfolioHoldingDao().insert(holding("h1", TEST_PORTFOLIO_ID))

        assertFailsWith<PortfolioHoldingNotFoundException> { repository.moveHolding("nope", TEST_PORTFOLIO_ID) }
        assertFailsWith<PortfolioNotFoundException> { repository.moveHolding("h1", "nope") }

        assertEquals(TEST_PORTFOLIO_ID, db.portfolioHoldingDao().find("h1")?.portfolioId)
        assertNull(db.portfolioHoldingDao().find("nope"))
        db.close()
    }
}
