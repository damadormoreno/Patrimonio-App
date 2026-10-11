package com.denebapps.patrimonio.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FxHistoryTest {
    private val history = FxHistory(
        mapOf(
            Currency.USD to listOf(
                DatedFxRate(LocalDate(2025, 1, 2), 920_000L),
                DatedFxRate(LocalDate(2025, 1, 3), 925_000L),
                DatedFxRate(LocalDate(2025, 1, 10), 930_000L),
            ),
        ),
    )

    @Test
    fun `the rate of an exact date is that date's`() {
        assertEquals(925_000L, history.rateOn(Currency.USD, LocalDate(2025, 1, 3)))
        assertEquals(930_000L, history.rateOn(Currency.USD, LocalDate(2025, 1, 10)))
    }

    @Test
    fun `a date without a rate takes the nearest previous one`() {
        // Saturday 4th and Monday 6th have none: the Friday 3rd rate answers up to a week back.
        assertEquals(925_000L, history.rateOn(Currency.USD, LocalDate(2025, 1, 4)))
        assertEquals(925_000L, history.rateOn(Currency.USD, LocalDate(2025, 1, 6)))
        assertEquals(925_000L, history.rateOn(Currency.USD, LocalDate(2025, 1, 9)))
    }

    @Test
    fun `a rate older than seven days is not used`() {
        assertEquals(930_000L, history.rateOn(Currency.USD, LocalDate(2025, 1, 17)))
        assertNull(history.rateOn(Currency.USD, LocalDate(2025, 1, 18)))
    }

    @Test
    fun `a date before the first rate has none`() {
        assertNull(history.rateOn(Currency.USD, LocalDate(2025, 1, 1)))
    }

    @Test
    fun `a later rate is never borrowed`() {
        assertNull(history.rateOn(Currency.USD, LocalDate(2024, 12, 30)))
    }

    @Test
    fun `EUR is always one and a currency without a series has no rate`() {
        assertEquals(RATE_SCALE, history.rateOn(Currency.EUR, LocalDate(1990, 1, 1)))
        assertNull(history.rateOn(Currency.GBP, LocalDate(2025, 1, 3)))
        assertNull(FxHistory(emptyMap()).rateOn(Currency.USD, LocalDate(2025, 1, 3)))
    }
}
