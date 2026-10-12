package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrencyAmountInputTest {
    private val usdRate = 920_000L

    // ---- the two typed texts -------------------------------------------------------------------------------------

    @Test
    fun `typing a field keeps what the other one holds`() {
        val both = TypedAmounts().withAmount("100").withEur("95")

        assertEquals(TypedAmounts(amount = "100", eur = "95"), both)
        assertEquals(TypedAmounts(amount = "200", eur = "95"), both.withAmount("200"))
    }

    @Test
    fun `a field cleared to blank stays blank until another input changes, then it follows the other one again`() {
        val cleared = TypedAmounts(amount = "100", eur = "95").withEur("")

        assertEquals("", cleared.eur)
        assertEquals(TypedAmounts(amount = "100", eur = null), cleared.settled())
        // Typing in the other field settles the blank one too.
        assertEquals(TypedAmounts(amount = "5", eur = null), cleared.withAmount("5"))
    }

    // ---- resolving the pair --------------------------------------------------------------------------------------

    @Test
    fun `the holding amount is derived from the euros by dividing by the rate`() {
        val amounts = resolveCurrencyAmounts(Currency.USD, amountText = "", eurText = "50", rateScaled = usdRate)

        // 5.000 cents x 1.000.000 / 920.000 = 5.434,78 -> 5.435
        assertEquals(5_435L, amounts.amountMinor)
        assertEquals(5_000L, amounts.eurMinor)
        assertEquals("54,35", amounts.amountPrefillText)
        assertNull(amounts.eurPrefillText)
        assertFalse(amounts.invalid)
        assertFalse(amounts.bothTyped)
    }

    @Test
    fun `the euros are derived from the holding amount by multiplying by the rate`() {
        val amounts = resolveCurrencyAmounts(Currency.USD, amountText = "100", eurText = null, rateScaled = usdRate)

        assertEquals(10_000L, amounts.amountMinor)
        assertEquals(9_200L, amounts.eurMinor)
        assertEquals("92,00", amounts.eurPrefillText)
        assertNull(amounts.amountPrefillText)
    }

    @Test
    fun `the division rounds half to even`() {
        val rate = 800_000L // 1 USD = 0,80 EUR: 1 euro cent is 1,25 cents

        // 2 x 1,25 = 2,5 -> 2 (even); 6 x 1,25 = 7,5 -> 8 (even)
        assertEquals(2L, resolveCurrencyAmounts(Currency.USD, "", "0,02", rate).amountMinor)
        assertEquals(8L, resolveCurrencyAmounts(Currency.USD, "", "0,06", rate).amountMinor)
    }

    @Test
    fun `a currency without decimals divides the euro cents down to whole units`() {
        // 1 JPY = 0,0061 EUR: 10,00 EUR = 1.000 cents -> 1.000 x 1.000.000 / (6.100 x 100) = 1.639,34 yen
        val amounts = resolveCurrencyAmounts(Currency.JPY, amountText = "", eurText = "10", rateScaled = 6_100L)

        assertEquals(1_639L, amounts.amountMinor)
        assertEquals("1639", amounts.amountPrefillText)
    }

    @Test
    fun `when both are typed both are kept and nothing is derived`() {
        val amounts = resolveCurrencyAmounts(Currency.USD, amountText = "100", eurText = "86,12", rateScaled = usdRate)

        assertEquals(10_000L, amounts.amountMinor)
        assertEquals(8_612L, amounts.eurMinor)
        assertNull(amounts.amountPrefillText)
        assertNull(amounts.eurPrefillText)
        assertTrue(amounts.bothTyped)
    }

    @Test
    fun `a blank text counts as not typed`() {
        val amounts = resolveCurrencyAmounts(Currency.USD, amountText = "  ", eurText = "50", rateScaled = usdRate)

        assertEquals(5_435L, amounts.amountMinor)
        assertFalse(amounts.bothTyped)
    }

    @Test
    fun `without a rate only what was typed is known`() {
        val euros = resolveCurrencyAmounts(Currency.USD, amountText = "", eurText = "50", rateScaled = null)
        val holding = resolveCurrencyAmounts(Currency.USD, amountText = "100", eurText = null, rateScaled = null)
        val both = resolveCurrencyAmounts(Currency.USD, amountText = "100", eurText = "90", rateScaled = null)

        assertNull(euros.amountMinor)
        assertEquals(5_000L, euros.eurMinor)
        assertEquals(10_000L, holding.amountMinor)
        assertNull(holding.eurMinor)
        assertEquals(10_000L to 9_000L, both.amountMinor to both.eurMinor)
    }

    @Test
    fun `a typed text that is not an amount makes the pair invalid and derives nothing`() {
        val badAmount = resolveCurrencyAmounts(Currency.USD, amountText = "x", eurText = "50", rateScaled = usdRate)
        val badEur = resolveCurrencyAmounts(Currency.USD, amountText = "100", eurText = "x", rateScaled = usdRate)

        assertTrue(badAmount.invalid)
        assertNull(badAmount.amountMinor)
        assertTrue(badEur.invalid)
        assertNull(badEur.eurMinor)
    }

    @Test
    fun `euros the rate turns into nothing are not an amount`() {
        // At 100 EUR per yen, 0,01 EUR is a ten-thousandth of a yen: it rounds to 0, which is no amount.
        assertNull(resolveCurrencyAmounts(Currency.JPY, "", "0,01", 100_000_000L).amountMinor)
    }

    @Test
    fun `an amount in euros has nothing to convert`() {
        val amounts = resolveCurrencyAmounts(Currency.EUR, amountText = "100", eurText = "50", rateScaled = null)

        assertEquals(10_000L, amounts.amountMinor)
        assertNull(amounts.eurMinor)
    }

    // ---- the implied rate ----------------------------------------------------------------------------------------

    @Test
    fun `the implied rate is the euros over the holding amount with four decimals`() {
        assertEquals("1 USD = 0,8612 €", impliedRateText(Currency.USD, amountMinor = 10_000, eurMinor = 8_612))
        assertEquals("1 GBP = 1,1700 €", impliedRateText(Currency.GBP, amountMinor = 10_000, eurMinor = 11_700))
        // 1.639 yen are 10,00 EUR: 0,006101 rounds to 0,0061
        assertEquals("1 JPY = 0,0061 €", impliedRateText(Currency.JPY, amountMinor = 1_639, eurMinor = 1_000))
    }

    @Test
    fun `there is no implied rate while an amount is missing or the holding is in euros`() {
        assertNull(impliedRateText(Currency.USD, amountMinor = null, eurMinor = 8_612))
        assertNull(impliedRateText(Currency.USD, amountMinor = 10_000, eurMinor = null))
        assertNull(impliedRateText(Currency.USD, amountMinor = 0, eurMinor = 8_612))
        assertNull(impliedRateText(Currency.EUR, amountMinor = 10_000, eurMinor = 10_000))
    }

    // ---- the missing-rate hints ----------------------------------------------------------------------------------

    @Test
    fun `without a rate the hint asks for whichever field is missing`() {
        val noEuros = missingRateHints(Currency.USD, null, amountText = "100", eurText = null, costOptional = false)
        val noAmount = missingRateHints(Currency.USD, null, amountText = "", eurText = "50", costOptional = false)
        val neither = missingRateHints(Currency.USD, null, amountText = "", eurText = null, costOptional = false)
        val both = missingRateHints(Currency.USD, null, amountText = "100", eurText = "90", costOptional = false)

        assertEquals(MISSING_EUR_HINT, noEuros.eurHint)
        assertNull(noEuros.amountHint)
        assertEquals("No hay cambio de esa fecha: escribe el importe en USD.", noAmount.amountHint)
        assertNull(noAmount.eurHint)
        assertEquals(MISSING_EUR_HINT, neither.eurHint)
        assertNull(both.eurHint)
        assertNull(both.amountHint)
    }

    @Test
    fun `there is no hint with a rate, in euros or for a cost that may stay blank`() {
        assertNull(missingRateHints(Currency.USD, usdRate, "100", null, costOptional = false).eurHint)
        assertNull(missingRateHints(Currency.EUR, null, "100", null, costOptional = false).eurHint)
        assertNull(missingRateHints(Currency.USD, null, "", null, costOptional = true).eurHint)
        assertEquals(MISSING_EUR_HINT, missingRateHints(Currency.USD, null, "100", null, costOptional = true).eurHint)
    }
}
