package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovementFormatTest {
    private fun units(whole: Long, millionths: Long = 0) = Units(whole * UNITS_SCALE + millionths)

    private fun movement(
        id: String,
        type: MovementType,
        day: Int,
        units: Units? = null,
        cents: Long? = null,
        feeCents: Long = 0,
        transferId: String? = null,
        note: String? = null,
        currency: Currency = Currency.EUR,
        sequence: Long = 0,
    ) = Movement(
        id = id,
        holdingId = "h",
        type = type,
        date = LocalDate(2026, 3, day),
        units = units,
        amount = cents?.let { CurrencyAmount(Money(it), currency) },
        fee = Money(feeCents),
        amountEur = cents?.let { Money(it) },
        feeEur = Money(feeCents),
        transferId = transferId,
        note = note,
        sequence = sequence,
    )

    private fun rows(movements: List<Movement>, currency: Currency = Currency.EUR) =
        movementRows(movements, currency) { "Fondo B" }

    @Test
    fun `every type has its Spanish label`() {
        assertEquals("Compra", movementTypeLabel(MovementType.BUY))
        assertEquals("Venta", movementTypeLabel(MovementType.SELL))
        assertEquals("Saldo inicial", movementTypeLabel(MovementType.OPENING_BALANCE))
        assertEquals("Dividendo", movementTypeLabel(MovementType.DIVIDEND))
        assertEquals("Comisión", movementTypeLabel(MovementType.FEE))
        assertEquals("Traspaso", movementTypeLabel(MovementType.TRANSFER_OUT))
        assertEquals("Traspaso", movementTypeLabel(MovementType.TRANSFER_IN))
    }

    @Test
    fun `rows come newest first with date, signed units and amount`() {
        val list = rows(
            listOf(
                movement("b1", MovementType.BUY, 1, units(10), 123_456),
                movement("s1", MovementType.SELL, 5, units(5), 70_000),
            ),
        )

        assertEquals(listOf("s1", "b1"), list.map { it.id })
        val buy = list[1]
        assertEquals("Compra", buy.title)
        assertEquals("01/03/2026", buy.dateText)
        assertEquals("+10,00 part.", buy.unitsText)
        assertEquals("1.234,56 €", buy.amountText)
        assertEquals("−5,00 part.", list[0].unitsText)
        assertEquals("Venta", list[0].title)
    }

    @Test
    fun `a fee on a trade is shown after the amount`() {
        val row = rows(listOf(movement("b1", MovementType.BUY, 1, units(1), 10_000, feeCents = 200))).single()

        assertEquals("100,00 € · comisión 2,00 €", row.amountText)
    }

    @Test
    fun `a dividend and a fee have an amount and no units`() {
        val list = rows(
            listOf(
                movement("d", MovementType.DIVIDEND, 2, cents = 1_250),
                movement("f", MovementType.FEE, 3, cents = 300),
            ),
        )

        assertEquals(listOf("f", "d"), list.map { it.id })
        assertNull(list[0].unitsText)
        assertEquals("3,00 €", list[0].amountText)
        assertEquals("Dividendo", list[1].title)
        assertEquals("12,50 €", list[1].amountText)
    }

    @Test
    fun `an opening balance shows its cost or says the cost is unknown`() {
        val list = rows(
            listOf(
                movement("o1", MovementType.OPENING_BALANCE, 1, units(200), 100_000),
                movement("o2", MovementType.OPENING_BALANCE, 2, units(3)),
            ),
        )

        assertEquals("+200,00 part.", list[1].unitsText)
        assertEquals("1.000,00 €", list[1].amountText)
        assertEquals("coste desconocido", list[0].amountText)
        assertEquals("Saldo inicial", list[0].title)
    }

    @Test
    fun `transfer legs name the other fund and show their units without amount`() {
        val list = movementRows(
            listOf(
                movement("out", MovementType.TRANSFER_OUT, 1, units(4), transferId = "t1"),
                movement("in", MovementType.TRANSFER_IN, 2, units(40), transferId = "t2"),
            ),
            Currency.EUR,
        ) { leg -> if (leg.id == "out") "Fondo B" else "Fondo A" }

        assertEquals("Traspaso desde «Fondo A»", list[0].title)
        assertEquals("+40,00 part.", list[0].unitsText)
        assertNull(list[0].amountText)
        assertEquals("Traspaso a «Fondo B»", list[1].title)
        assertEquals("−4,00 part.", list[1].unitsText)
    }

    @Test
    fun `a transfer whose other leg is unknown just says transfer`() {
        val list = movementRows(
            listOf(movement("out", MovementType.TRANSFER_OUT, 1, units(4), transferId = "t1")),
            Currency.EUR,
        ) { null }

        assertEquals("Traspaso", list.single().title)
    }

    @Test
    fun `amounts are shown in the currency of the holding and the note is carried`() {
        val row = rows(
            listOf(movement("b", MovementType.BUY, 1, units(1), 10_000, currency = Currency.USD, note = "Regalo")),
            Currency.USD,
        ).single()

        assertEquals("100,00 $", row.amountText)
        assertEquals("Regalo", row.noteText)
    }
}
