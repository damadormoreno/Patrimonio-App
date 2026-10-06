package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.domain.model.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FxMappingTest {
    @Test
    fun `inverts a USD rate to a scaled currency-to-EUR Long`() {
        assertEquals(919_963L, invertAndScale(1.0870))
    }

    @Test
    fun `inverts a JPY rate with its much larger magnitude precisely`() {
        assertEquals(6_101L, invertAndScale(163.9))
    }

    @Test
    fun `rounds an exact half-tie to the nearest even scaled value (half-even)`() {
        assertEquals(2L, invertAndScale(400_000.0))
    }

    @Test
    fun `rejects a zero rate`() {
        assertFailsWith<IllegalArgumentException> { invertAndScale(0.0) }
    }

    @Test
    fun `rejects a negative rate`() {
        assertFailsWith<IllegalArgumentException> { invertAndScale(-1.5) }
    }

    @Test
    fun `rejects a NaN rate`() {
        assertFailsWith<IllegalArgumentException> { invertAndScale(Double.NaN) }
    }

    @Test
    fun `rejects an infinite rate`() {
        assertFailsWith<IllegalArgumentException> { invertAndScale(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `validateRates inverts every requested currency present in the response`() {
        val result = validateRates(
            mapOf("USD" to 1.0870, "GBP" to 1.17, "JPY" to 163.9),
            listOf(Currency.USD, Currency.GBP, Currency.JPY),
        )

        assertEquals(919_963L, result.getValue(Currency.USD))
        assertEquals(854_701L, result.getValue(Currency.GBP))
        assertEquals(6_101L, result.getValue(Currency.JPY))
    }

    @Test
    fun `validateRates rejects a response missing a requested symbol`() {
        assertFailsWith<IllegalStateException> {
            validateRates(mapOf("USD" to 1.0870, "GBP" to 1.17), listOf(Currency.USD, Currency.GBP, Currency.JPY))
        }
    }

    @Test
    fun `validateRates rejects a response containing an invalid rate for one symbol`() {
        assertFailsWith<IllegalArgumentException> {
            validateRates(
                mapOf("USD" to 1.0870, "GBP" to 0.0, "JPY" to 163.9),
                listOf(Currency.USD, Currency.GBP, Currency.JPY),
            )
        }
    }
}
