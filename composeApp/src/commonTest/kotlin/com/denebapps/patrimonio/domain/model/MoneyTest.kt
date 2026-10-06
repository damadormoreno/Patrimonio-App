package com.denebapps.patrimonio.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun `JPY has zero decimal places`() {
        assertEquals(0, Currency.JPY.decimals)
    }

    @Test
    fun `EUR USD GBP use two decimal places`() {
        assertEquals(2, Currency.EUR.decimals)
        assertEquals(2, Currency.USD.decimals)
        assertEquals(2, Currency.GBP.decimals)
    }
}
