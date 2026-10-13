package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.mulDivHalfEven
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.ui.components.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

// What the movement forms (the movement sheet and the "Una compra" way of adding a product) share: turning what the
// user typed into the draft the repository takes, with the price it implies and, for an amount in another currency, the
// two-way conversion between that currency and euros ([resolveCurrencyAmounts]).

/** The movement types whose units are typed. */
private val TYPES_WITH_UNITS = setOf(MovementType.BUY, MovementType.SELL, MovementType.OPENING_BALANCE)

/** The movement types that take a broker fee. */
private val TYPES_WITH_FEE = setOf(MovementType.BUY, MovementType.SELL)

/** Minor units are 10^decimals per major one; `PRICE_SCALE * UNITS_SCALE` over that is the price factor. */
private const val PRICE_FACTOR = PRICE_SCALE * UNITS_SCALE

/**
 * What a movement form holds: the texts as typed, in the holding [currency]. [amountText] is the amount when the user
 * typed one and [eurText] the euro equivalent when the user typed one (blank or `null`: not typed). Whichever was not
 * typed follows from the other at [rateScaled], the rate of [date]; both typed are kept as they are. [feeText] is in
 * euros when [feeInEur] (ignored for a holding in euros) and in the holding currency otherwise; the other one follows
 * from the ratio of the two typed amounts or, with one typed, from [rateScaled].
 */
internal data class MovementEntryInput(
    val type: MovementType,
    val date: LocalDate,
    val currency: Currency,
    val unitsText: String = "",
    val amountText: String = "",
    val feeText: String = "",
    val eurText: String? = null,
    val noteText: String = "",
    val rateScaled: Long? = null,
    val feeInEur: Boolean = false,
)

/**
 * The outcome of [resolveMovementEntry]. [draft] is `null` while the form is incomplete or invalid. [impliedPriceText]
 * is the price per unit the amount and units come to ("≈ 64,761 €/part."), [impliedRateText] the rate the two amounts
 * come to ("1 USD = 0,8612 €"). [eurPrefillText] and [amountPrefillText] are the texts of the euro and the amount
 * fields when the user did not type them but they follow the other one at the rate of the day. [needsEur] says that
 * the holding is in another currency and only the amount was typed but there is no rate for that day, [needsAmount]
 * the other way round: only the euros were typed. [feePreviewText] is the fee in the currency it was not typed in
 * ("≈ 1,08 $" for a fee in euros, "≈ 0,92 €" for one in the holding currency), `null` while there is none to show.
 */
internal data class MovementEntry(
    val draft: MovementDraft?,
    val impliedPriceText: String?,
    val eurPrefillText: String?,
    val needsEur: Boolean,
    val amountPrefillText: String? = null,
    val impliedRateText: String? = null,
    val needsAmount: Boolean = false,
    val feePreviewText: String? = null,
)

/** Turns what was typed into a [MovementDraft], the price it implies and the derived amounts. Pure and exact. */
internal fun resolveMovementEntry(input: MovementEntryInput): MovementEntry {
    val type = input.type
    val currency = input.currency
    val units = if (type in TYPES_WITH_UNITS) parseUnitsEs(input.unitsText) else null
    val amounts = resolveCurrencyAmounts(currency, input.amountText, input.eurText, input.rateScaled)
    // An opening balance may have no cost at all (unknown cost): neither amount typed.
    val costOptional = type == MovementType.OPENING_BALANCE && input.amountText.isBlank() &&
        (currency == Currency.EUR || input.eurText.isNullOrBlank())
    val feeInEur = input.feeInEur && currency != Currency.EUR
    val feeTyped = when {
        type !in TYPES_WITH_FEE || input.feeText.isBlank() -> 0L
        else -> parseAmountToMinor(input.feeText, if (feeInEur) Currency.EUR else currency, allowZero = true)
    }
    val impliedPrice = impliedPriceText(units?.scaled, amounts.amountMinor, currency)
    val impliedRate = impliedRateText(currency, amounts.amountMinor, amounts.eurMinor)
    val amountMinor = amounts.amountMinor
    val eurMinor = amounts.eurMinor
    val fee = feeTyped?.let { resolveFee(it, feeInEur, input, amounts) }
    fun entry(draft: MovementDraft?, needsEur: Boolean = false, needsAmount: Boolean = false) = MovementEntry(
        draft = draft,
        impliedPriceText = impliedPrice,
        eurPrefillText = amounts.eurPrefillText,
        needsEur = needsEur,
        amountPrefillText = amounts.amountPrefillText,
        impliedRateText = impliedRate,
        needsAmount = needsAmount,
        feePreviewText = fee?.let { feePreviewText(it, feeInEur, currency) },
    )
    val incomplete = (type in TYPES_WITH_UNITS && units == null) ||
        (!costOptional && amountMinor == null && eurMinor == null) ||
        feeTyped == null ||
        amounts.invalid
    if (incomplete) return entry(null)
    // A holding in another currency needs both amounts, each typed or followed from the other at the rate of the day.
    if (currency != Currency.EUR && !costOptional) {
        if (amountMinor != null && eurMinor == null) return entry(null, needsEur = true)
        if (eurMinor != null && amountMinor == null) return entry(null, needsAmount = true)
    }
    // A fee in euros that the amounts and the rate cannot turn into the holding currency leaves the form incomplete.
    if (fee == null) return entry(null)
    return entry(buildDraft(input, units?.scaled, amountMinor, fee, eurMinor))
}

/**
 * A fee as both amounts: [minor] in the holding currency and [eurMinor] in euros. [eurMinor] is `null` when there is no
 * fee, the holding is in euros (the fee is its own euros), or the euros cannot be known yet.
 */
private class FeeAmounts(val minor: Long, val eurMinor: Long?)

/**
 * The fee [typed] (in euros when [inEur], else in the holding currency) with its counterpart. Both amounts typed give
 * the ratio the broker applied; otherwise the rate of the day does. Rounded half-even to minor units. `null` when a fee
 * typed in euros cannot be turned into the holding currency (no rate and no pair of typed amounts, or it does not fit).
 */
private fun resolveFee(typed: Long, inEur: Boolean, input: MovementEntryInput, amounts: CurrencyAmounts): FeeAmounts? {
    val currency = input.currency
    val amountMinor = amounts.amountMinor
    val eurMinor = amounts.eurMinor
    if (currency == Currency.EUR) return FeeAmounts(typed, null)
    if (typed == 0L) return FeeAmounts(0L, null)
    return try {
        if (inEur) {
            val minor = if (amounts.bothTyped && amountMinor != null && eurMinor != null) {
                mulDivHalfEven(typed, amountMinor, eurMinor)
            } else {
                eurToHolding(typed, currency, input.rateScaled)
            }
            minor?.let { FeeAmounts(it, typed) }
        } else {
            val feeEur = when {
                amountMinor == null || eurMinor == null -> null
                // The fee follows the ratio of what the broker charged, so both stay in step with it.
                amounts.bothTyped -> mulDivHalfEven(typed, eurMinor, amountMinor)
                else -> holdingToEur(typed, currency, input.rateScaled)
            }
            FeeAmounts(typed, feeEur)
        }
    } catch (e: ArithmeticException) {
        null
    }
}

/** "≈ 1,08 $" (a fee typed in euros) or "≈ 0,92 €" (one typed in the holding currency); `null` without a fee. */
private fun feePreviewText(fee: FeeAmounts, inEur: Boolean, currency: Currency): String? {
    if (currency == Currency.EUR) return null
    val shown = (if (inEur) fee.minor else fee.eurMinor) ?: return null
    val typed = (if (inEur) fee.eurMinor else fee.minor) ?: return null
    if (typed == 0L) return null
    return "≈ ${formatMoney(Money(shown), if (inEur) currency else Currency.EUR)}"
}

private fun buildDraft(
    input: MovementEntryInput,
    unitsScaled: Long?,
    amountMinor: Long?,
    fee: FeeAmounts,
    eurMinor: Long?,
): MovementDraft {
    val currency = input.currency
    return MovementDraft(
        type = input.type,
        date = input.date,
        units = unitsScaled?.let(::Units),
        amount = amountMinor?.let { CurrencyAmount(Money(it), currency) },
        fee = Money(fee.minor),
        amountEur = eurMinor?.let(::Money),
        feeEur = fee.eurMinor?.takeIf { it > 0L }?.let(::Money),
        note = input.noteText.trim().ifEmpty { null },
    )
}

/** "≈ 64,761 €/part.": the amount over the units, `null` while either is missing or the price does not fit. */
private fun impliedPriceText(unitsScaled: Long?, amountMinor: Long?, currency: Currency): String? {
    if (unitsScaled == null || amountMinor == null || amountMinor <= 0) return null
    var minorPerMajor = 1L
    repeat(currency.decimals) { minorPerMajor *= TEN }
    return try {
        val priceScaled = mulDivHalfEven(amountMinor, PRICE_FACTOR / minorPerMajor, unitsScaled)
        "≈ ${formatPrice(Price(priceScaled, currency))}/part."
    } catch (e: ArithmeticException) {
        null
    }
}

private const val TEN = 10L

/**
 * The daily rates of a currency to euros for the forms that ask for a euro equivalent: keeps the stored history, tells
 * [onChange] when it changes, and downloads what a date needs ([ensure]). A download that fails is ignored — the user
 * then types the equivalent. Call [start] once the owner is fully built.
 */
internal class FxRateLookup(
    private val repository: FxHistoryRepository,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    private var history = FxHistory(emptyMap())

    fun start() {
        scope.launch {
            repository.observe().collect {
                history = it
                onChange()
            }
        }
    }

    /** The scaled rate of [currency] to EUR on [date], `null` when none is stored within a week of it. */
    fun rateOn(currency: Currency, date: LocalDate): Long? = history.rateOn(currency, date)

    /** Makes sure the rates of [currency] reach back to [date]; nothing to do for euros. */
    fun ensure(currency: Currency, date: LocalDate) {
        if (currency == Currency.EUR) return
        scope.launch {
            try {
                repository.sync(setOf(currency), date, force = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Offline or the source is down: what is stored stays and the user types the equivalent.
            }
        }
    }
}

/**
 * Whether a stored fee is shown in euros when its movement is edited. The currency a fee was typed in is not stored, so
 * the one that gives back the stored amounts is chosen: in euros when typing [feeEur] again produces exactly [fee] at
 * the ratio of the stored [amount] and [amountEur] (saving the movement untouched then changes nothing), else in the
 * holding currency, which is also the answer when the ratio is not known. A movement without a fee, whose fee field
 * is empty, gets the default for new ones: euros. A holding in euros has no such choice.
 */
internal fun feeWasTypedInEur(
    currency: Currency,
    fee: Money,
    feeEur: Money,
    amount: Money?,
    amountEur: Money?,
): Boolean {
    if (currency == Currency.EUR) return false
    if (fee.minorUnits == 0L && feeEur.minorUnits == 0L) return true
    if (amount == null || amountEur == null || amount.minorUnits <= 0L || amountEur.minorUnits <= 0L) return false
    return try {
        mulDivHalfEven(feeEur.minorUnits, amount.minorUnits, amountEur.minorUnits) == fee.minorUnits
    } catch (e: ArithmeticException) {
        false
    }
}
