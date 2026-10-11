package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.DatedFxRate
import kotlinx.datetime.LocalDate
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

    @Test
    fun `scales a time series per currency with the same inversion as the latest rates`() {
        val series = scaleSeries(
            rates = mapOf(
                "2025-01-02" to mapOf("USD" to 1.0870, "GBP" to 0.85),
                "2025-01-03" to mapOf("USD" to 1.1, "GBP" to 0.0),
                "2025-01-06" to mapOf("USD" to 1.0870, "JPY" to 163.9),
            ),
            requested = listOf(Currency.USD, Currency.GBP),
        )

        assertEquals(
            mapOf(
                Currency.USD to listOf(
                    DatedFxRate(LocalDate(2025, 1, 2), 919_963L),
                    DatedFxRate(LocalDate(2025, 1, 3), 909_091L),
                    DatedFxRate(LocalDate(2025, 1, 6), 919_963L),
                ),
                // 2025-01-03 carries an unusable zero rate and 2025-01-06 no GBP at all: both days are skipped.
                Currency.GBP to listOf(DatedFxRate(LocalDate(2025, 1, 2), 1_176_471L)),
            ),
            series,
        )
        assertEquals(invertAndScale(1.0870), series.getValue(Currency.USD).first().rateToEurScaled)
    }

    @Test
    fun `a time series comes out ascending whatever the order of the answer`() {
        val series = scaleSeries(
            rates = mapOf("2025-01-06" to mapOf("USD" to 1.1), "2025-01-02" to mapOf("USD" to 1.2)),
            requested = listOf(Currency.USD),
        )

        assertEquals(
            listOf(LocalDate(2025, 1, 2), LocalDate(2025, 1, 6)),
            series.getValue(Currency.USD).map { it.date },
        )
    }

    @Test
    fun `a currency without any rate still appears with an empty series and a bad date is rejected`() {
        assertEquals(
            mapOf(Currency.JPY to emptyList()),
            scaleSeries(mapOf("2025-01-02" to mapOf("USD" to 1.1)), listOf(Currency.JPY)),
        )
        assertFailsWith<IllegalArgumentException> {
            scaleSeries(mapOf("not-a-date" to mapOf("USD" to 1.1)), listOf(Currency.USD))
        }
    }
}
