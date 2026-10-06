package com.denebapps.patrimonio.ui.components

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {
    @Test
    fun `money is formatted es-ES in its own currency`() {
        assertEquals("1.234,56 €", formatMoney(Money(123_456), Currency.EUR))
        assertEquals("0,05 $", formatMoney(Money(5), Currency.USD))
        assertEquals("1.200 ¥", formatMoney(Money(1_200), Currency.JPY))
        assertEquals("-12,30 €", formatEur(Money(-1_230)))
    }

    @Test
    fun `amount input text round-trips with the parser format`() {
        assertEquals("1234,50", amountInputText(Money(123_450), Currency.EUR))
        assertEquals("1200", amountInputText(Money(1_200), Currency.JPY))
    }

    @Test
    fun `amount input keeps digits and one decimal comma`() {
        assertEquals("12,99", filterAmountInput("12.99"))
        assertEquals("12,995", filterAmountInput("1a2,9,9.5"))
    }

    @Test
    fun `dates use Spanish month names`() {
        assertEquals("7 oct", formatDayMonth(LocalDate(2026, 10, 7)))
        assertEquals("31 de enero de 2026", formatDateLong(LocalDate(2026, 1, 31)))
    }
}
