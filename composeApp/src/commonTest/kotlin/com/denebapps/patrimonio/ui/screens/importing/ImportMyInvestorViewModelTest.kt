package com.denebapps.patrimonio.ui.screens.importing

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.testing.FakeMovementRepository
import com.denebapps.patrimonio.testing.FakePortfolioCollectionRepository
import com.denebapps.patrimonio.testing.FakePortfolioRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.FakeQuoteSource
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import com.denebapps.patrimonio.testing.settle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISIN = "IE00BD0NCM55"
private const val OTHER_ISIN = "LU0996182563"
private const val OTHER_PORTFOLIO_ID = "portfolio-2"
private const val HEADER = "Fecha fiscal;Inversión;Valor de mercado;Resultado fiscal\r\n"

/** Two open lots: 1.000,00 € now worth 1.100,00 € and 500,00 € now worth 520,00 €. */
private const val TWO_LOTS = HEADER + "01/02/2024;1.000,00;1.100,00;100,00\r\n15/06/2025;500,00;520,00;20,00\r\n"

private val FUND_REF = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P0000NEW1")
private val FUND_CANDIDATE =
    InstrumentCandidate(FUND_REF, "Fondo Nuevo", ISIN, null, InstrumentType.FUND, Currency.EUR, null)
private val TEN_EUR = Price(10_000_000, Currency.EUR)
private val QUOTE_DATE = LocalDate(2026, 10, 9)

private fun fileName(isin: String) = "Plusvalías y minusvalías fondo $isin.csv"

private fun pickedFile(isin: String = ISIN, csv: String = TWO_LOTS, name: String = fileName(isin)) =
    PickedFile(name) { csv.encodeToByteArray() }

private fun holding(
    id: String = "h1",
    isin: String? = ISIN,
    portfolioId: String = TEST_PORTFOLIO_ID,
    currency: Currency = Currency.EUR,
    units: Long = 160 * UNITS_SCALE,
    name: String = "Fondo Uno",
) = PortfolioHolding(
    id = id,
    portfolioId = portfolioId,
    name = name,
    type = InstrumentType.FUND,
    isin = isin,
    ticker = null,
    valuation = HoldingValuation.Quoted(
        ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P-$id"),
        currency = currency,
        units = UnitsBalance(units),
        lastQuote = LastQuote(Price(10_000_000, currency), QUOTE_DATE, 0L),
    ),
)

private fun buy(day: Int, units: Long, euros: Long) = MovementDraft(
    type = MovementType.BUY,
    date = LocalDate(2025, 1, day),
    units = Units(units * UNITS_SCALE),
    amount = CurrencyAmount(Money(euros * 100), Currency.EUR),
)

/** Lets one holding's replacement fail, to see how the failure shows. */
private class FailingMovements(
    private val delegate: MovementRepository,
    private val failures: Map<String, Exception>,
) : MovementRepository by delegate {
    override suspend fun replaceHoldingMovements(holdingId: String, drafts: List<MovementDraft>) =
        failures[holdingId]?.let { throw it } ?: delegate.replaceHoldingMovements(holdingId, drafts)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImportMyInvestorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-11T10:00:00Z")

    private val source = FakeQuoteSource().apply {
        searchResults = listOf(FUND_CANDIDATE)
        quotes[FUND_REF] = Quote(FUND_REF, TEN_EUR, QUOTE_DATE)
    }
    private var holdings = FakePortfolioRepository()
    private var movements = FakeMovementRepository(holdings)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun seed(vararg initial: PortfolioHolding) {
        holdings = FakePortfolioRepository(initial.toList())
        movements = FakeMovementRepository(holdings)
    }

    private fun viewModel(selectedPortfolioId: String? = null, repository: MovementRepository = movements) =
        ImportMyInvestorViewModel(
            quoteSource = source,
            portfolioRepository = holdings,
            portfolios = FakePortfolioCollectionRepository(
                listOf(Portfolio(TEST_PORTFOLIO_ID, "Mi cartera", 0), Portfolio(OTHER_PORTFOLIO_ID, "Jubilación", 1)),
            ),
            preferences = FakePreferencesRepository(selectedPortfolioId = selectedPortfolioId),
            movementRepository = repository,
            clock = clock,
            idProvider = { "new-id" },
        )

    private fun ImportMyInvestorViewModel.card(index: Int = 0) = state.value.cards[index]

    // ---- Matching an existing fund ------------------------------------------------------------------------------

    @Test
    fun `a file for an existing fund shows its summary, its units and what the import replaces`() =
        runTest(dispatcher) {
            seed(holding(units = 1, portfolioId = OTHER_PORTFOLIO_ID))
            movements.addMovement("h1", buy(day = 2, units = 100, euros = 900))
            movements.addMovement("h1", buy(day = 3, units = 60, euros = 600))
            val vm = viewModel()

            vm.onFilesPicked(listOf(pickedFile()))
            settle()

            val card = vm.card()
            assertEquals(ImportCardStatus.READY, card.status)
            assertEquals("Fondo Uno", card.title)
            assertEquals("Jubilación", card.subtitle)
            assertFalse(card.isNewFund)
            assertEquals("2 lotes · Invertido 1.500,00 € · Valor de mercado 1.620,00 €", card.summaryText)
            assertEquals("160", card.unitsText)
            assertEquals("Sustituye los 2 movimientos de este fondo", card.notice)
            assertNull(card.unitsNote)
            assertTrue(card.warnings.isEmpty())
            assertTrue(vm.state.value.canImport)
        }

    @Test
    fun `one movement is replaced in the singular`() = runTest(dispatcher) {
        seed(holding())
        movements.addMovement("h1", buy(day = 2, units = 160, euros = 1_500))
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals("Sustituye el movimiento de este fondo", vm.card().notice)
    }

    @Test
    fun `a fund with units typed by hand says they will be calculated from the purchases`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals("Sus participaciones pasarán a calcularse con estas compras", vm.card().notice)
        assertEquals("160", vm.card().unitsText)
    }

    @Test
    fun `the fund is matched by its ISIN in any case and in any portfolio`() = runTest(dispatcher) {
        seed(holding(isin = ISIN.lowercase(), portfolioId = OTHER_PORTFOLIO_ID))
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile(name = "ie00bd0ncm55.csv")))
        settle()

        assertFalse(vm.card().isNewFund)
        assertEquals("Fondo Uno", vm.card().title)
        assertTrue(source.searches.isEmpty())
    }

    @Test
    fun `a fund with transfers is blocked`() = runTest(dispatcher) {
        seed(holding(), holding(id = "h2", isin = OTHER_ISIN, name = "Fondo Dos"))
        movements.addMovement("h1", buy(day = 2, units = 160, euros = 1_500))
        movements.addTransfer(
            "h1",
            "h2",
            LocalDate(2025, 2, 1),
            LocalDate(2025, 2, 1),
            Units(10 * UNITS_SCALE),
            Units(10 * UNITS_SCALE),
        )
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(ImportCardStatus.ERROR, vm.card().status)
        assertEquals("Este producto tiene traspasos con otros productos; elimínalos antes.", vm.card().errorText)
        assertFalse(vm.state.value.canImport)
    }

    @Test
    fun `a fund that is not in euros is refused`() = runTest(dispatcher) {
        seed(holding(currency = Currency.USD))
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(ImportCardStatus.ERROR, vm.card().status)
        assertEquals("Solo se pueden importar fondos en euros.", vm.card().errorText)
    }

    // ---- A new fund -----------------------------------------------------------------------------------------------

    @Test
    fun `an unknown fund is looked up by its ISIN and offered as new with estimated units`() = runTest(dispatcher) {
        seed()
        val vm = viewModel(selectedPortfolioId = OTHER_PORTFOLIO_ID)

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(listOf(ISIN), source.searches)
        val card = vm.card()
        assertEquals(ImportCardStatus.READY, card.status)
        assertTrue(card.isNewFund)
        assertEquals("Fondo nuevo: Fondo Nuevo", card.title)
        assertEquals(OTHER_PORTFOLIO_ID, card.portfolioId)
        assertEquals("Jubilación", card.subtitle)
        assertEquals("162", card.unitsText)
        assertEquals("Estimadas con el último precio; cópialas de MyInvestor para que sean exactas", card.unitsNote)
        assertNull(card.notice)
        assertEquals("2 lotes · Invertido 1.500,00 € · Valor de mercado 1.620,00 €", card.summaryText)
    }

    @Test
    fun `a new fund goes to the first portfolio when none is selected`() = runTest(dispatcher) {
        seed()
        val vm = viewModel(selectedPortfolioId = null)

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(TEST_PORTFOLIO_ID, vm.card().portfolioId)
    }

    @Test
    fun `editing the estimated units drops the note about the estimate`() = runTest(dispatcher) {
        seed()
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onUnitsChange(vm.card().id, "161,5")

        assertNull(vm.card().unitsNote)
    }

    @Test
    fun `a fund the search does not know is an error`() = runTest(dispatcher) {
        seed()
        source.searchResults = emptyList()
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(ImportCardStatus.ERROR, vm.card().status)
        assertEquals("No hemos encontrado este fondo (ISIN $ISIN) en Morningstar.", vm.card().errorText)
    }

    @Test
    fun `a result with another ISIN is not the fund`() = runTest(dispatcher) {
        seed()
        source.searchResults = listOf(FUND_CANDIDATE.copy(isin = OTHER_ISIN))
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(ImportCardStatus.ERROR, vm.card().status)
    }

    @Test
    fun `a search that fails says so`() = runTest(dispatcher) {
        seed()
        source.searchFailure = QuoteUnavailableException("offline")
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals("No se pudo buscar el fondo. Revisa tu conexión e inténtalo de nuevo.", vm.card().errorText)
    }

    @Test
    fun `a price that cannot be fetched says so`() = runTest(dispatcher) {
        seed()
        source.quoteFailure = QuoteUnavailableException("offline")
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals(
            "No se pudo obtener el precio del fondo. Inténtalo de nuevo más tarde.",
            vm.card().errorText,
        )
    }

    @Test
    fun `a new fund quoted in another currency is refused`() = runTest(dispatcher) {
        seed()
        source.quotes[FUND_REF] = Quote(FUND_REF, Price(10_000_000, Currency.USD), QUOTE_DATE)
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        assertEquals("Solo se pueden importar fondos en euros.", vm.card().errorText)
    }

    // ---- Files that cannot be read --------------------------------------------------------------------------------

    @Test
    fun `files are shown as loading until they are read`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile()))

        assertEquals(ImportCardStatus.LOADING, vm.card().status)
        assertEquals(fileName(ISIN), vm.card().fileName)
        assertFalse(vm.state.value.canImport)
    }

    @Test
    fun `a file that is not a MyInvestor gains file is an error`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile(csv = "Concepto;Importe\r\nCafé;2,00\r\n")))
        settle()

        assertEquals(
            "Este archivo no es una exportación de «Plusvalías y minusvalías» de MyInvestor.",
            vm.card().errorText,
        )
    }

    @Test
    fun `a malformed row names its line`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(
            listOf(pickedFile(csv = HEADER + "01/02/2024;1.000,00;1.100,00;100,00\r\nayer;5,00;5,00;0,00")),
        )
        settle()

        assertEquals("Línea 3: «Fecha fiscal» no tiene una fecha válida.", vm.card().errorText)
    }

    @Test
    fun `a file without open lots is an error`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile(csv = HEADER + "01/02/2024;0,00;0,00;-3,00\r\n")))
        settle()

        assertEquals(
            "Este fondo no tiene compras abiertas que importar: todo está reembolsado.",
            vm.card().errorText,
        )
    }

    @Test
    fun `a file name without an ISIN is an error`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile(name = "export.csv")))
        settle()

        assertEquals(
            "No encontramos el ISIN en el nombre del archivo. No le cambies el nombre: lleva el ISIN.",
            vm.card().errorText,
        )
    }

    @Test
    fun `a file that cannot be read is an error`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onFilesPicked(listOf(PickedFile(fileName(ISIN)) { error("gone") }))
        settle()

        assertEquals("No se pudo leer el archivo.", vm.card().errorText)
    }

    @Test
    fun `a second file of the same fund is an error and is fine again once the first is removed`() =
        runTest(dispatcher) {
            seed(holding())
            val vm = viewModel()

            vm.onFilesPicked(listOf(pickedFile(), pickedFile(name = "Copia de ${fileName(ISIN)}")))
            settle()

            assertEquals(ImportCardStatus.READY, vm.card(0).status)
            assertEquals(ImportCardStatus.ERROR, vm.card(1).status)
            assertEquals("Ya hay otro archivo de este fondo en la lista.", vm.card(1).errorText)

            vm.onRemove(vm.card(0).id)

            assertEquals(1, vm.state.value.cards.size)
            assertEquals(ImportCardStatus.READY, vm.card(0).status)
        }

    @Test
    fun `a card can be removed`() = runTest(dispatcher) {
        seed(holding(), holding(id = "h2", isin = OTHER_ISIN, name = "Fondo Dos"))
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile(), pickedFile(OTHER_ISIN)))
        settle()

        vm.onRemove(vm.card(0).id)

        assertEquals(listOf("Fondo Dos"), vm.state.value.cards.map { it.title })
    }

    // ---- Units and warnings ---------------------------------------------------------------------------------------

    @Test
    fun `units far from the market value of the file are flagged`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onUnitsChange(vm.card().id, "100")

        assertEquals(
            listOf("Las participaciones no cuadran con el valor de mercado del archivo; revísalas en MyInvestor."),
            vm.card().warnings,
        )
        assertNull(vm.card().unitsError)
        assertTrue(vm.state.value.canImport)
    }

    @Test
    fun `units too few for the lots are an error that blocks the import`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onUnitsChange(vm.card().id, "0,000001")

        assertEquals("Son muy pocas participaciones para repartirlas entre 2 lotes.", vm.card().unitsError)
        assertFalse(vm.state.value.canImport)
    }

    @Test
    fun `blank or invalid units are an error that blocks the import`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onUnitsChange(vm.card().id, "")
        assertEquals("Indica las participaciones actuales.", vm.card().unitsError)
        assertFalse(vm.state.value.canImport)

        vm.onUnitsChange(vm.card().id, "0")
        assertEquals("Indica un número de participaciones válido.", vm.card().unitsError)
        assertFalse(vm.state.value.canImport)

        vm.onUnitsChange(vm.card().id, "12,5")
        assertNull(vm.card().unitsError)
        assertTrue(vm.state.value.canImport)
    }

    @Test
    fun `redeemed rows are reported with their result`() = runTest(dispatcher) {
        seed(holding(), holding(id = "h2", isin = OTHER_ISIN, name = "Fondo Dos"))
        val loss = TWO_LOTS + "01/01/2023;0,00;0,00;-12,50\r\n"
        val gains = TWO_LOTS + "01/01/2023;0,00;0,00;4,00\r\n01/01/2022;0,00;0,00;3,50\r\n"
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile(csv = loss), pickedFile(OTHER_ISIN, csv = gains)))
        settle()

        assertEquals(
            listOf("1 reembolso no se importa (MyInvestor solo da su plusvalía: −12,50 €)"),
            vm.card(0).warnings,
        )
        assertEquals(
            listOf("2 reembolsos no se importan (MyInvestor solo da su plusvalía: +7,50 €)"),
            vm.card(1).warnings,
        )
    }

    // ---- Importing ------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is written until the import is confirmed`() = runTest(dispatcher) {
        seed(holding(id = "h2", isin = OTHER_ISIN))
        val vm = viewModel()

        vm.onFilesPicked(listOf(pickedFile(), pickedFile(OTHER_ISIN)))
        settle()

        assertEquals(listOf("h2"), holdings.current.map { it.id })
        assertTrue(movements.current.isEmpty())
    }

    @Test
    fun `an existing fund has its movements replaced by the lots`() = runTest(dispatcher) {
        seed(holding(units = 1))
        movements.addMovement("h1", buy(day = 2, units = 5, euros = 50))
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onUnitsChange(vm.card().id, "160")
        vm.onImport()
        settle()

        val bought = movements.current.filter { it.holdingId == "h1" }.sortedBy { it.date }
        assertEquals(listOf(LocalDate(2024, 2, 1), LocalDate(2025, 6, 15)), bought.map { it.date })
        assertTrue(bought.all { it.type == MovementType.BUY && it.note == "MyInvestor" })
        assertEquals(listOf(Money(100_000), Money(50_000)), bought.map { it.amountEur })
        assertEquals(160 * UNITS_SCALE, bought.sumOf { checkNotNull(it.units).scaled })
        assertTrue(checkNotNull(bought[0].units).scaled > checkNotNull(bought[1].units).scaled)
        assertEquals(1, holdings.current.size)
        assertEquals(
            UnitsBalance(160 * UNITS_SCALE),
            (holdings.current.single().valuation as HoldingValuation.Quoted).units,
        )
        assertEquals(ImportCardResultUi(success = true, text = "Importado: 2 compras"), vm.card().result)
        assertTrue(vm.state.value.finished)
        assertFalse(vm.state.value.importing)
    }

    @Test
    fun `a new fund is created with the chosen portfolio, the MyInvestor broker and its purchases`() =
        runTest(dispatcher) {
            seed()
            val vm = viewModel()
            vm.onFilesPicked(listOf(pickedFile()))
            settle()
            vm.onPortfolioChange(vm.card().id, OTHER_PORTFOLIO_ID)

            vm.onImport()
            settle()

            val created = holdings.current.single()
            assertEquals("new-id", created.id)
            assertEquals(OTHER_PORTFOLIO_ID, created.portfolioId)
            assertEquals("MyInvestor", created.broker)
            assertEquals("Fondo Nuevo", created.name)
            assertEquals(InstrumentType.FUND, created.type)
            assertEquals(ISIN, created.isin)
            val valuation = created.valuation as HoldingValuation.Quoted
            assertEquals(FUND_REF, valuation.ref)
            assertEquals(Currency.EUR, valuation.currency)
            assertEquals(UnitsBalance(162 * UNITS_SCALE), valuation.units)
            assertEquals(TEN_EUR, valuation.lastQuote?.price)
            assertEquals(QUOTE_DATE, valuation.lastQuote?.date)
            assertEquals(clock.now().toEpochMilliseconds(), valuation.lastQuote?.fetchedAtEpochMs)

            assertEquals(2, movements.current.size)
            assertTrue(movements.current.all { it.holdingId == "new-id" && it.type == MovementType.BUY })
            assertEquals(162 * UNITS_SCALE, movements.current.sumOf { checkNotNull(it.units).scaled })
            assertEquals(ImportCardResultUi(success = true, text = "Importado: 2 compras"), vm.card().result)
        }

    @Test
    fun `a refused replacement shows the reason in the card`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel(repository = FailingMovements(movements, mapOf("h1" to HoldingHasTransfersException("h1"))))
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onImport()
        settle()

        assertEquals(
            ImportCardResultUi(
                success = false,
                text = "Este producto tiene traspasos con otros productos; elimínalos antes.",
            ),
            vm.card().result,
        )
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `an unexpected failure shows a generic message`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel(repository = FailingMovements(movements, mapOf("h1" to IllegalStateException("boom"))))
        vm.onFilesPicked(listOf(pickedFile()))
        settle()

        vm.onImport()
        settle()

        assertEquals(ImportCardResultUi(false, "No se pudo guardar el cambio."), vm.card().result)
    }

    @Test
    fun `one file failing does not stop the next one`() = runTest(dispatcher) {
        seed(holding(), holding(id = "h2", isin = OTHER_ISIN, name = "Fondo Dos"))
        val vm = viewModel(repository = FailingMovements(movements, mapOf("h1" to IllegalStateException("boom"))))
        vm.onFilesPicked(listOf(pickedFile(), pickedFile(OTHER_ISIN)))
        settle()

        vm.onImport()
        settle()

        assertFalse(assertNotNull(vm.card(0).result).success)
        assertEquals(ImportCardResultUi(true, "Importado: 2 compras"), vm.card(1).result)
        assertEquals(setOf("h2"), movements.current.map { it.holdingId }.toSet())
    }

    @Test
    fun `a card that is not valid is left out of the import`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile(), pickedFile(OTHER_ISIN, csv = "nada")))
        settle()

        assertTrue(vm.state.value.canImport)
        vm.onImport()
        settle()

        assertNotNull(vm.card(0).result)
        assertNull(vm.card(1).result)
        assertEquals(setOf("h1"), movements.current.map { it.holdingId }.toSet())
    }

    @Test
    fun `nothing can be imported without a valid card, while loading or twice`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()

        vm.onImport()
        settle()
        assertFalse(vm.state.value.canImport)
        assertFalse(vm.state.value.finished)

        vm.onFilesPicked(listOf(pickedFile()))
        vm.onImport()
        settle()
        assertTrue(movements.current.isEmpty())

        vm.onImport()
        settle()
        val written = movements.current.map { it.id }
        vm.onImport()
        settle()
        assertEquals(written, movements.current.map { it.id })
        assertFalse(vm.state.value.canImport)
    }

    @Test
    fun `files cannot be edited once imported`() = runTest(dispatcher) {
        seed(holding())
        val vm = viewModel()
        vm.onFilesPicked(listOf(pickedFile()))
        settle()
        vm.onImport()
        settle()

        vm.onUnitsChange(vm.card().id, "5")
        vm.onRemove(vm.card().id)

        assertEquals("160", vm.card().unitsText)
        assertEquals(1, vm.state.value.cards.size)
    }

    @Test
    fun `the portfolios to choose from are offered`() = runTest(dispatcher) {
        val vm = viewModel()
        settle()

        assertEquals(listOf("Mi cartera", "Jubilación"), vm.state.value.portfolios.map { it.name })
        assertTrue(vm.state.value.canPickPortfolio)
    }
}
