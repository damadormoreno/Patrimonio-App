package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fee of a movement in another currency, typed in euros: the holding-currency fee follows, never the reverse. */
class MovementFeeInEurTest {
    private val day = LocalDate(2026, 10, 9)

    // 0,92 EUR per USD, the way the stored daily rates are scaled.
    private val usdRate = 920_000L

    private fun input(
        type: MovementType = MovementType.BUY,
        currency: Currency = Currency.USD,
        amount: String = "",
        fee: String = "",
        eur: String? = null,
        rate: Long? = null,
        feeInEur: Boolean = true,
    ) = MovementEntryInput(
        type = type,
        date = day,
        currency = currency,
        unitsText = "1",
        amountText = amount,
        feeText = fee,
        eurText = eur,
        rateScaled = rate,
        feeInEur = feeInEur,
    )

    @Test
    fun `a fee in euros is kept exactly and the fee in dollars follows the rate the two amounts imply`() {
        val entry = resolveMovementEntry(input(amount = "100", eur = "86,12", fee = "1", rate = usdRate))

        // 100 cents x 10.000 cents of USD / 8.612 cents of EUR = 116,12 -> 116.
        assertEquals(Money(116), entry.draft?.fee)
        assertEquals(Money(100), entry.draft?.feeEur)
        assertEquals(Money(8_612), entry.draft?.amountEur)
        assertEquals("≈ 1,16 $", entry.feePreviewText)
    }

    @Test
    fun `a fee in euros follows the rate of the day when only one amount is typed`() {
        val amount = resolveMovementEntry(input(amount = "100", fee = "1,38", rate = usdRate))
        val euros = resolveMovementEntry(input(eur = "50", fee = "1", rate = usdRate))

        // 138 cents x 1.000.000 / 920.000 = 150,00 exactly.
        assertEquals(Money(150), amount.draft?.fee)
        assertEquals(Money(138), amount.draft?.feeEur)
        assertEquals("≈ 1,50 $", amount.feePreviewText)
        // 100 cents x 1.000.000 / 920.000 = 108,70 -> 109, next to an amount of 54,35 derived the same way.
        assertEquals(Money(109), euros.draft?.fee)
        assertEquals(Money(100), euros.draft?.feeEur)
        assertEquals(Money(5_435), euros.draft?.amount?.amount)
    }

    @Test
    fun `the fee in the holding currency that follows a fee in euros rounds half to even`() {
        val rate = 800_000L

        val low = resolveMovementEntry(input(amount = "100", fee = "0,02", rate = rate))
        val high = resolveMovementEntry(input(amount = "100", fee = "0,06", rate = rate))

        assertEquals(Money(2), low.draft?.fee) // 2,5 -> 2
        assertEquals(Money(8), high.draft?.fee) // 7,5 -> 8
    }

    @Test
    fun `a fee in euros on a holding without decimals comes out in whole units`() {
        val entry = resolveMovementEntry(input(currency = Currency.JPY, amount = "10000", fee = "1", rate = 6_000))

        // 100 cents x 1.000.000 / (6.000 x 100) = 166,67 -> 167 yen.
        assertEquals(Money(167), entry.draft?.fee)
        assertEquals(Money(100), entry.draft?.feeEur)
        assertEquals("≈ 167 ¥", entry.feePreviewText)
    }

    @Test
    fun `a fee in the holding currency is unchanged and shows its euros`() {
        val entry = resolveMovementEntry(input(amount = "100", fee = "10", rate = usdRate, feeInEur = false))

        assertEquals(Money(1_000), entry.draft?.fee)
        assertEquals(Money(920), entry.draft?.feeEur)
        assertEquals("≈ 9,20 €", entry.feePreviewText)
    }

    @Test
    fun `a sell takes its fee in euros the same way`() {
        val entry = resolveMovementEntry(
            input(MovementType.SELL, amount = "100", eur = "86,12", fee = "1", rate = usdRate),
        )

        assertEquals(Money(116), entry.draft?.fee)
        assertEquals(Money(100), entry.draft?.feeEur)
    }

    @Test
    fun `a holding in euros has no fee currency to choose`() {
        val entry = resolveMovementEntry(input(currency = Currency.EUR, amount = "100", fee = "2"))

        assertEquals(Money(200), entry.draft?.fee)
        assertNull(entry.draft?.feeEur)
        assertNull(entry.feePreviewText)
    }

    @Test
    fun `without a rate the fee follows only the amounts that were both typed`() {
        val both = resolveMovementEntry(input(amount = "100", eur = "90", fee = "1"))
        val onlyAmount = resolveMovementEntry(input(amount = "100", fee = "1"))
        val onlyEuros = resolveMovementEntry(input(eur = "90", fee = "1"))

        // 100 cents x 10.000 / 9.000 = 111,11 -> 111.
        assertEquals(Money(111), both.draft?.fee)
        assertEquals(Money(100), both.draft?.feeEur)
        assertEquals("≈ 1,11 $", both.feePreviewText)
        // Nothing to derive it from: it is the missing euro equivalent (or amount) that the form asks for.
        assertNull(onlyAmount.draft)
        assertTrue(onlyAmount.needsEur)
        assertNull(onlyAmount.feePreviewText)
        assertNull(onlyEuros.draft)
        assertTrue(onlyEuros.needsAmount)
        assertNull(onlyEuros.feePreviewText)
    }

    @Test
    fun `no fee, a zero fee or a text that is not an amount`() {
        val none = resolveMovementEntry(input(amount = "100", rate = usdRate))
        val zero = resolveMovementEntry(input(amount = "100", fee = "0", rate = usdRate))
        val invalid = resolveMovementEntry(input(amount = "100", fee = "x", rate = usdRate))

        assertEquals(Money.ZERO, none.draft?.fee)
        assertNull(none.draft?.feeEur)
        assertNull(none.feePreviewText)
        assertEquals(Money.ZERO, zero.draft?.fee)
        assertNull(zero.draft?.feeEur)
        assertNull(zero.feePreviewText)
        assertNull(invalid.draft)
    }

    @Test
    fun `types without a fee ignore the fee text`() {
        val entry = resolveMovementEntry(input(MovementType.DIVIDEND, amount = "10", fee = "1", rate = usdRate))

        assertEquals(Money.ZERO, entry.draft?.fee)
        assertNull(entry.draft?.feeEur)
        assertNull(entry.feePreviewText)
    }

    // ---- which currency a stored fee was typed in ----------------------------------------------------------------

    private fun typedInEur(
        fee: Long,
        feeEur: Long,
        amount: Long?,
        amountEur: Long?,
        currency: Currency = Currency.USD,
    ) = feeWasTypedInEur(currency, Money(fee), Money(feeEur), amount?.let(::Money), amountEur?.let(::Money))

    @Test
    fun `a stored fee that the euros reproduce exactly is restored in euros`() {
        // 475 cents x 10.000 / 9.500 = 500: typing 4,75 EUR gives back the stored 5,00 USD.
        assertTrue(typedInEur(fee = 500, feeEur = 475, amount = 10_000, amountEur = 9_500))
    }

    @Test
    fun `a stored fee that the euros do not reproduce was typed in the holding currency`() {
        // 3 cents of USD at half a euro per dollar are 1,5 -> 2 cents; 2 cents back are 4 cents of USD, not 3.
        assertFalse(typedInEur(fee = 3, feeEur = 2, amount = 10_000, amountEur = 5_000))
        // Not even on the ratio of the amounts: neither can be told apart, so the holding currency stays.
        assertFalse(typedInEur(fee = 500, feeEur = 400, amount = 10_000, amountEur = 9_500))
    }

    @Test
    fun `a movement without a fee or without both amounts restores the default or the holding currency`() {
        assertTrue(typedInEur(fee = 0, feeEur = 0, amount = 10_000, amountEur = 9_500))
        assertFalse(typedInEur(fee = 500, feeEur = 475, amount = null, amountEur = null))
        assertFalse(typedInEur(fee = 500, feeEur = 475, amount = 10_000, amountEur = 0))
        assertFalse(typedInEur(fee = 500, feeEur = 500, amount = 10_000, amountEur = 10_000, currency = Currency.EUR))
    }
}
