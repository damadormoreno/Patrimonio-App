package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.MovementDraft
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovementEntryTest {
    private val day = LocalDate(2026, 10, 9)

    private fun input(
        type: MovementType = MovementType.BUY,
        currency: Currency = Currency.EUR,
        units: String = "",
        amount: String = "",
        fee: String = "",
        eur: String? = null,
        note: String = "",
        rate: Long? = null,
    ) = MovementEntryInput(
        type = type,
        date = day,
        currency = currency,
        unitsText = units,
        amountText = amount,
        feeText = fee,
        eurText = eur,
        noteText = note,
        rateScaled = rate,
    )

    private fun eur(cents: Long) = CurrencyAmount(Money(cents), Currency.EUR)

    private fun usd(cents: Long) = CurrencyAmount(Money(cents), Currency.USD)

    @Test
    fun `a buy in euros becomes a draft with its units, amount and fee`() {
        val entry = resolveMovementEntry(input(units = "10", amount = "647,61", fee = "2"))

        assertEquals(
            MovementDraft(
                type = MovementType.BUY,
                date = day,
                units = Units(10 * UNITS_SCALE),
                amount = eur(64_761),
                fee = Money(200),
            ),
            entry.draft,
        )
        assertFalse(entry.needsEur)
    }

    @Test
    fun `the implied price is the amount over the units`() {
        val entry = resolveMovementEntry(input(units = "10", amount = "647,61"))

        assertEquals("≈ 64,761 €/part.", entry.impliedPriceText)
    }

    @Test
    fun `there is no implied price while the amount or the units are missing`() {
        assertNull(resolveMovementEntry(input(units = "10")).impliedPriceText)
        assertNull(resolveMovementEntry(input(amount = "10")).impliedPriceText)
        assertNull(resolveMovementEntry(input(type = MovementType.DIVIDEND, amount = "10")).impliedPriceText)
    }

    @Test
    fun `a movement that is incomplete or invalid has no draft`() {
        assertNull(resolveMovementEntry(input(units = "", amount = "10")).draft)
        assertNull(resolveMovementEntry(input(units = "0", amount = "10")).draft)
        assertNull(resolveMovementEntry(input(units = "1", amount = "")).draft)
        assertNull(resolveMovementEntry(input(units = "1", amount = "0")).draft)
        assertNull(resolveMovementEntry(input(units = "1", amount = "10", fee = "abc")).draft)
    }

    @Test
    fun `a dividend and a fee carry only an amount`() {
        val dividend = resolveMovementEntry(input(MovementType.DIVIDEND, units = "5", amount = "12,50"))
        val fee = resolveMovementEntry(input(MovementType.FEE, amount = "3"))

        assertEquals(MovementDraft(MovementType.DIVIDEND, day, amount = eur(1_250)), dividend.draft)
        assertEquals(MovementDraft(MovementType.FEE, day, amount = eur(300)), fee.draft)
    }

    @Test
    fun `a sell takes its units and amount like a buy`() {
        val entry = resolveMovementEntry(input(MovementType.SELL, units = "4", amount = "300", fee = "1,50"))

        assertEquals(Units(4 * UNITS_SCALE), entry.draft?.units)
        assertEquals(Money(150), entry.draft?.fee)
        assertEquals(MovementType.SELL, entry.draft?.type)
    }

    @Test
    fun `an opening balance may have no cost`() {
        val unknown = resolveMovementEntry(input(MovementType.OPENING_BALANCE, units = "200"))
        val known = resolveMovementEntry(input(MovementType.OPENING_BALANCE, units = "200", amount = "1.000"))
        val broken = resolveMovementEntry(input(MovementType.OPENING_BALANCE, units = "200", amount = "x"))

        assertEquals(MovementDraft(MovementType.OPENING_BALANCE, day, Units(200 * UNITS_SCALE)), unknown.draft)
        assertEquals(eur(100_000), known.draft?.amount)
        assertEquals("≈ 5,00 €/part.", known.impliedPriceText)
        assertNull(broken.draft)
    }

    @Test
    fun `an amount in another currency is converted at the rate of the day and can be overridden`() {
        val rate = 920_000L
        val followed = resolveMovementEntry(
            input(units = "1", amount = "100", fee = "10", currency = Currency.USD, rate = rate),
        )
        val overridden = resolveMovementEntry(
            input(units = "1", amount = "100", fee = "10", currency = Currency.USD, rate = rate, eur = "95"),
        )

        assertEquals("92,00", followed.eurPrefillText)
        assertEquals(usd(10_000), followed.draft?.amount)
        assertEquals(Money(9_200), followed.draft?.amountEur)
        assertEquals(Money(920), followed.draft?.feeEur)
        // The fee follows the same ratio as the typed equivalent: 10,00 x 95 / 100.
        assertEquals(Money(9_500), overridden.draft?.amountEur)
        assertEquals(Money(950), overridden.draft?.feeEur)
    }

    @Test
    fun `without a rate the euro equivalent is needed and a blank override still follows the rate`() {
        val missing = resolveMovementEntry(input(units = "1", amount = "100", currency = Currency.USD))
        val typed = resolveMovementEntry(input(units = "1", amount = "100", currency = Currency.USD, eur = "90"))
        val blank = resolveMovementEntry(
            input(units = "1", amount = "100", currency = Currency.USD, rate = 920_000, eur = "  "),
        )

        assertNull(missing.draft)
        assertTrue(missing.needsEur)
        assertNull(missing.eurPrefillText)
        assertEquals(Money(9_000), typed.draft?.amountEur)
        assertEquals(Money(9_200), blank.draft?.amountEur)
        assertNull(resolveMovementEntry(input(units = "1", amount = "100", currency = Currency.USD, eur = "x")).draft)
    }

    @Test
    fun `a dividend in another currency also needs its euro equivalent`() {
        val entry = resolveMovementEntry(
            input(MovementType.DIVIDEND, amount = "10", currency = Currency.USD, rate = 920_000),
        )

        assertEquals(Money(920), entry.draft?.amountEur)
    }

    @Test
    fun `the euros typed alone give the amount in the holding currency at the rate of the day`() {
        val entry = resolveMovementEntry(
            input(units = "2", fee = "10", currency = Currency.USD, rate = 920_000, eur = "50"),
        )

        // 5.000 cents x 1.000.000 / 920.000 = 5.434,78 -> 54,35 USD
        assertEquals(usd(5_435), entry.draft?.amount)
        assertEquals(Money(5_000), entry.draft?.amountEur)
        assertEquals("54,35", entry.amountPrefillText)
        assertNull(entry.eurPrefillText)
        assertFalse(entry.needsEur)
        assertFalse(entry.needsAmount)
        // The implied price and rate come from the derived amount.
        assertEquals("≈ 27,175 $/part.", entry.impliedPriceText)
        assertEquals("1 USD = 0,9200 €", entry.impliedRateText)
        // Only the euros were typed, so the fee follows the rate of the day: 10,00 x 0,92.
        assertEquals(Money(920), entry.draft?.feeEur)
    }

    @Test
    fun `the amount derived from the euros rounds half to even`() {
        val rate = 800_000L

        val low = resolveMovementEntry(input(MovementType.DIVIDEND, currency = Currency.USD, rate = rate, eur = "0,02"))
        val high =
            resolveMovementEntry(input(MovementType.DIVIDEND, currency = Currency.USD, rate = rate, eur = "0,06"))

        assertEquals(usd(2), low.draft?.amount) // 2,5 -> 2
        assertEquals(usd(8), high.draft?.amount) // 7,5 -> 8
    }

    @Test
    fun `when both amounts are typed both are kept and the rate they imply is shown`() {
        val entry = resolveMovementEntry(
            input(units = "1", amount = "100", fee = "10", currency = Currency.USD, rate = 920_000, eur = "86,12"),
        )

        assertEquals(usd(10_000), entry.draft?.amount)
        assertEquals(Money(8_612), entry.draft?.amountEur)
        // The fee follows the ratio of what the broker charged: 10,00 x 86,12 / 100.
        assertEquals(Money(861), entry.draft?.feeEur)
        assertNull(entry.amountPrefillText)
        assertNull(entry.eurPrefillText)
        assertEquals("1 USD = 0,8612 €", entry.impliedRateText)
    }

    @Test
    fun `the rate implied by an amount followed from the rate of the day is shown too`() {
        val entry = resolveMovementEntry(input(units = "1", amount = "100", currency = Currency.USD, rate = 920_000))

        assertEquals("1 USD = 0,9200 €", entry.impliedRateText)
        assertNull(resolveMovementEntry(input(units = "1", amount = "100")).impliedRateText)
        assertNull(resolveMovementEntry(input(units = "1", amount = "100", currency = Currency.USD)).impliedRateText)
    }

    @Test
    fun `typing only the euros without a rate asks for the amount in the holding currency`() {
        val entry = resolveMovementEntry(input(units = "1", currency = Currency.USD, eur = "90"))

        assertNull(entry.draft)
        assertTrue(entry.needsAmount)
        assertFalse(entry.needsEur)
        assertNull(entry.amountPrefillText)
        assertNull(entry.impliedRateText)
    }

    @Test
    fun `a blank amount follows the euros and a blank euro field follows the amount`() {
        val euros = resolveMovementEntry(
            input(units = "1", amount = "  ", currency = Currency.USD, rate = 920_000, eur = "50"),
        )
        val amount = resolveMovementEntry(
            input(units = "1", amount = "100", currency = Currency.USD, rate = 920_000, eur = "  "),
        )

        assertEquals(usd(5_435), euros.draft?.amount)
        assertEquals(Money(9_200), amount.draft?.amountEur)
    }

    @Test
    fun `neither amount typed is incomplete without asking for either`() {
        val entry = resolveMovementEntry(input(units = "1", currency = Currency.USD, rate = 920_000))

        assertNull(entry.draft)
        assertFalse(entry.needsEur)
        assertFalse(entry.needsAmount)
    }

    @Test
    fun `a euro text that is not an amount is invalid even when the amount is blank`() {
        val entry = resolveMovementEntry(input(units = "1", currency = Currency.USD, rate = 920_000, eur = "x"))

        assertNull(entry.draft)
        assertFalse(entry.needsAmount)
    }

    @Test
    fun `an opening balance may take its cost in euros, and with neither the cost stays unknown`() {
        val euros = resolveMovementEntry(
            input(MovementType.OPENING_BALANCE, units = "2", currency = Currency.USD, rate = 920_000, eur = "50"),
        )
        val unknown = resolveMovementEntry(
            input(MovementType.OPENING_BALANCE, units = "2", currency = Currency.USD, rate = 920_000),
        )
        val noRate = resolveMovementEntry(
            input(MovementType.OPENING_BALANCE, units = "2", currency = Currency.USD, eur = "50"),
        )

        assertEquals(usd(5_435), euros.draft?.amount)
        assertEquals(Money(5_000), euros.draft?.amountEur)
        assertEquals(MovementDraft(MovementType.OPENING_BALANCE, day, Units(2 * UNITS_SCALE)), unknown.draft)
        assertNull(noRate.draft)
        assertTrue(noRate.needsAmount)
    }

    @Test
    fun `the note is trimmed and cut to nothing when blank`() {
        assertEquals("Hola", resolveMovementEntry(input(units = "1", amount = "1", note = "  Hola ")).draft?.note)
        assertNull(resolveMovementEntry(input(units = "1", amount = "1", note = "   ")).draft?.note)
    }
}
