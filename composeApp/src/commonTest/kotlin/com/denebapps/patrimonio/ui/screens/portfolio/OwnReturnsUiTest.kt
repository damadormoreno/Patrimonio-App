package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.PositionReturns
import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.calc.positionReturns
import com.denebapps.patrimonio.domain.calc.scopeReturns
import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.FUND_B
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.dividend
import com.denebapps.patrimonio.domain.model.feeMovement
import com.denebapps.patrimonio.domain.model.opening
import com.denebapps.patrimonio.domain.model.sell
import com.denebapps.patrimonio.domain.model.transferIn
import com.denebapps.patrimonio.domain.model.transferOut
import com.denebapps.patrimonio.domain.model.units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The "Mi posición" card and the "Tu rentabilidad" block as texts. `today` is a year after the first flow of most
// fixtures, so a plain ratio is also the annual return.
class OwnReturnsUiTest {
    private val today = date(2026, 1, 1)

    private fun position(holding: String, movements: List<Movement>, value: Long?): PositionReturns {
        val ledger = computeLedgers(movements).getValue(holding)
        return positionReturns(
            movements = movements.filter { it.holdingId == holding },
            ledger = ledger,
            today = today,
            valueEur = value?.let { Money(it) },
        )
    }

    private fun card(holding: String, movements: List<Movement>, value: Long?): MyPositionUi =
        myPositionCard(position(holding, movements, value), movements.filter { it.holdingId == holding })

    private fun MyPositionUi.figure(label: String): OwnFigureUi = figures.first { it.label == label }

    // ---- a known cost ------------------------------------------------------------------------------------------

    @Test
    fun `a position with a known cost shows cost, value, gains and annual return`() {
        // 10 units for 1000.00 + 5.00 fee = cost 1005.00 a year ago; worth 1200.00 now.
        val card = card(FUND_A, listOf(buy("b", date(2025, 1, 1), units(10), 100_000, feeCents = 500)), 120_000)

        assertEquals(
            listOf(
                OwnFigureUi("Aportado", "1.005,00 €"),
                OwnFigureUi("Valor actual", "1.200,00 €"),
                OwnFigureUi("Plusvalía latente", "+195,00 € (+19,4 %)", PerformanceTone.GAIN),
                OwnFigureUi("Plusvalía realizada", "0,00 €"),
                OwnFigureUi("Comisiones", "5,00 €"),
                OwnFigureUi("Rentabilidad anual (XIRR)", "+19,40 %", PerformanceTone.GAIN),
            ),
            card.figures,
        )
        assertNull(card.hint)
    }

    @Test
    fun `a loss is shown with a minus sign and the expense tone`() {
        val card = card(FUND_A, listOf(buy("b", date(2025, 1, 1), units(10), 100_000)), 90_000)

        assertEquals(
            OwnFigureUi("Plusvalía latente", "−100,00 € (−10,0 %)", PerformanceTone.LOSS),
            card.figure("Plusvalía latente"),
        )
        assertEquals(OwnFigureUi("Rentabilidad anual (XIRR)", "−10,00 %", PerformanceTone.LOSS), card.figures.last())
    }

    @Test
    fun `dividends and fees are listed only when there are some`() {
        val card = card(
            FUND_A,
            listOf(
                buy("b", date(2025, 1, 1), units(10), 100_000),
                dividend("d", date(2025, 6, 1), 2_000),
                feeMovement("f", date(2025, 7, 1), 300),
            ),
            100_000,
        )

        assertEquals("20,00 €", card.figure("Dividendos cobrados").valueText)
        assertEquals("3,00 €", card.figure("Comisiones").valueText)
    }

    // ---- an unknown cost ---------------------------------------------------------------------------------------

    @Test
    fun `an unknown cost is never shown as zero`() {
        val card = card(FUND_A, listOf(opening("o", date(2025, 1, 1), units(200), costCents = null)), 150_000)

        assertEquals(
            OwnFigureUi("Aportado", NO_FIGURE_TEXT, detailText = "Coste desconocido de 200,00 part."),
            card.figure("Aportado"),
        )
        assertEquals("1.500,00 €", card.figure("Valor actual").valueText)
        assertEquals(OwnFigureUi("Plusvalía latente", NO_FIGURE_TEXT), card.figure("Plusvalía latente"))
        assertEquals(NO_FIGURE_TEXT, card.figure("Rentabilidad anual (XIRR)").valueText)
        assertEquals("Añade el coste del saldo inicial para calcularla.", card.hint)
    }

    @Test
    fun `the part with a known cost is shown as partial next to the unknown one`() {
        // 4 units of unknown cost + 6 units bought for 600.00, all worth 1100.00: the known 6 are worth 660.00.
        val card = card(
            FUND_A,
            listOf(
                opening("o", date(2025, 1, 1), units(4), costCents = null),
                buy("b", date(2025, 6, 1), units(6), 60_000),
            ),
            110_000,
        )

        assertEquals(
            OwnFigureUi("Aportado (parte conocida)", "600,00 €", detailText = "Coste desconocido de 4,00 part."),
            card.figures.first(),
        )
        assertEquals(
            OwnFigureUi(
                "Plusvalía latente",
                NO_FIGURE_TEXT,
                detailText = "Solo la parte con coste conocido: +60,00 € (+10,0 %)",
            ),
            card.figure("Plusvalía latente"),
        )
        assertEquals(NO_FIGURE_TEXT, card.figure("Rentabilidad anual (XIRR)").valueText)
    }

    @Test
    fun `the realized gain is unknown when units of unknown cost were sold`() {
        val card = card(
            FUND_A,
            listOf(
                opening("o", date(2025, 1, 1), units(10), costCents = null),
                sell("s", date(2025, 6, 1), units(4), 40_000),
            ),
            60_000,
        )

        assertEquals(NO_FIGURE_TEXT, card.figure("Plusvalía realizada").valueText)
        assertEquals("Añade el coste del saldo inicial para calcularla.", card.hint)
    }

    @Test
    fun `a position without a price has no value and no gain`() {
        val card = card(FUND_A, listOf(buy("b", date(2025, 1, 1), units(10), 100_000)), value = null)

        assertEquals(NO_PRICE_TEXT, card.figure("Valor actual").valueText)
        assertEquals(NO_FIGURE_TEXT, card.figure("Plusvalía latente").valueText)
        assertEquals(NO_FIGURE_TEXT, card.figure("Rentabilidad anual (XIRR)").valueText)
        assertNull(card.hint)
    }

    // ---- a closed position -------------------------------------------------------------------------------------

    @Test
    fun `a closed position shows zero value, its realized gain and the annual return of its life`() {
        val card = card(
            FUND_A,
            listOf(
                buy("b", date(2025, 1, 1), units(10), 100_000),
                sell("s", date(2026, 1, 1), units(10), 110_000),
            ),
            value = 0,
        )

        assertEquals(
            listOf(
                OwnFigureUi("Valor actual", "0,00 €"),
                OwnFigureUi("Plusvalía realizada", "+100,00 €", PerformanceTone.GAIN),
                OwnFigureUi("Rentabilidad anual (XIRR)", "+10,00 %", PerformanceTone.GAIN),
            ),
            card.figures,
        )
        assertTrue(card.lots.isEmpty())
    }

    // ---- lots --------------------------------------------------------------------------------------------------

    @Test
    fun `the open lots are listed oldest first with their date, units and cost`() {
        val card = card(
            FUND_A,
            listOf(
                opening("o", date(2024, 12, 31), units(4), costCents = null),
                buy("b1", date(2025, 3, 1), units(10), 100_000),
                buy("b2", date(2025, 9, 15), units(2), 25_050),
            ),
            200_000,
        )

        assertEquals(
            listOf(
                LotUi("31/12/2024", "4,00 part.", UNKNOWN_COST_TEXT),
                LotUi("01/03/2025", "10,00 part.", "1.000,00 €"),
                LotUi("15/09/2025", "2,00 part.", "250,50 €"),
            ),
            card.lots,
        )
        assertNull(card.lotsNote)
    }

    @Test
    fun `a lot received in a transfer keeps the date it was bought on`() {
        val movements = listOf(
            buy("b", date(2025, 1, 15), units(10), 100_000),
            transferOut("o", date(2025, 7, 1), units(10), "t"),
            transferIn("i", date(2025, 7, 1), units(8), "t"),
        )

        val card = card(FUND_B, movements, 90_000)

        assertEquals(listOf(LotUi("15/01/2025", "8,00 part.", "1.000,00 €")), card.lots)
        assertEquals("Los lotes recibidos por traspaso conservan su fecha de compra original.", card.lotsNote)
    }

    // ---- a scope -----------------------------------------------------------------------------------------------

    @Test
    fun `the block of a scope shows the totals and says how many products it covers`() {
        val movements = listOf(
            buy("b", date(2025, 1, 1), units(10), 100_000),
            transferOut("o", date(2025, 7, 1), units(10), "t"),
            transferIn("i", date(2025, 7, 1), units(10), "t"),
            dividend("d", date(2025, 9, 1), 1_500, holding = FUND_B),
        )
        val a = position(FUND_A, movements, 0)
        val b = position(FUND_B, movements, 130_000)

        val block = assertNotNullBlock(yourReturnsUi(scopeReturns(productCount = 2, positions = listOf(a, b))))

        assertEquals("Calculado con los 2 productos.", block.coverageText)
        assertEquals("1.000,00 €", block.figures.first { it.label == "Aportado" }.valueText)
        assertEquals("1.300,00 €", block.figures.first { it.label == "Valor actual" }.valueText)
        assertEquals("+300,00 € (+30,0 %)", block.figures.first { it.label == "Plusvalía latente" }.valueText)
        assertEquals("0,00 €", block.figures.first { it.label == "Plusvalía realizada" }.valueText)
        assertEquals("15,00 €", block.figures.first { it.label == "Dividendos cobrados" }.valueText)
    }

    @Test
    fun `the coverage counts the products left out`() {
        val a = position(FUND_A, listOf(buy("b", date(2025, 1, 1), units(10), 100_000)), 120_000)
        val c = position(FUND_B, listOf(opening("o", date(2025, 1, 1), units(1), null, holding = FUND_B)), 5_000)

        val block = assertNotNullBlock(yourReturnsUi(scopeReturns(productCount = 5, positions = listOf(a, c))))

        assertEquals(
            "Calculado con 1 de 5 productos (4 sin movimientos o con coste desconocido).",
            block.coverageText,
        )
    }

    @Test
    fun `a scope where nothing can be calculated shows dashes`() {
        val c = position(FUND_B, listOf(opening("o", date(2025, 1, 1), units(1), null, holding = FUND_B)), 5_000)

        val block = assertNotNullBlock(yourReturnsUi(scopeReturns(productCount = 1, positions = listOf(c))))

        assertEquals("Calculado con 0 de 1 producto (1 sin movimientos o con coste desconocido).", block.coverageText)
        assertTrue(block.figures.all { it.valueText == NO_FIGURE_TEXT })
    }

    @Test
    fun `the block is hidden when no product has movements`() {
        assertNull(yourReturnsUi(scopeReturns(productCount = 3, positions = emptyList())))
    }

    private fun assertNotNullBlock(block: YourReturnsUi?): YourReturnsUi {
        kotlin.test.assertNotNull(block)
        return block
    }
}
