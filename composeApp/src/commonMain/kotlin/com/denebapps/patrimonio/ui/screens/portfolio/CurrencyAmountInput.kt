package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.mulDivHalfEven
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.RATE_SCALE
import com.denebapps.patrimonio.ui.components.amountInputText
import com.denebapps.patrimonio.ui.components.currencySymbol

// The two amounts of a movement in another currency — what it came to in the holding currency and in euros — where
// the user types either one and the other follows from the rate of the day, or types both (what the broker charged).
// Shared by the movement sheet and the "Una compra" way of adding a product, so that they cannot drift apart.

/** Shown when the day has no rate and the euro equivalent is the one missing. */
internal const val MISSING_EUR_HINT = "No hay cambio de esa fecha: escribe el equivalente en euros."

/** Shown when the day has no rate and the amount in the holding [currency] is the one missing. */
internal fun missingAmountHint(currency: Currency) =
    "No hay cambio de esa fecha: escribe el importe en ${currency.code}."

/** Decimals of the implied rate ("1 USD = 0,8612 €"). */
private const val RATE_DECIMALS = 4

private const val TEN = 10L

/**
 * What the user typed in the two amount fields: [amount] in the holding currency and [eur] its euro equivalent. A
 * field the user has not touched is `null` and follows the other one. One cleared to blank is blank here and counts as
 * not typed, but stays blank on screen while it is being edited (so a new value can be typed from scratch) until any
 * other input changes ([settled]).
 */
internal data class TypedAmounts(val amount: String? = null, val eur: String? = null) {
    /** The amount field now holds [text]; a blank euro field stops being held blank. */
    fun withAmount(text: String) = TypedAmounts(amount = text, eur = eur.settled())

    /** The euro field now holds [text]; a blank amount field stops being held blank. */
    fun withEur(text: String) = TypedAmounts(amount = amount.settled(), eur = text)

    /** Another input changed: a field held blank follows the other one again, and shows it. */
    fun settled() = TypedAmounts(amount = amount.settled(), eur = eur.settled())
}

private fun String?.settled(): String? = takeIf { !it.isNullOrBlank() }

/**
 * The pair resolved at [resolveCurrencyAmounts]. [amountMinor] and [eurMinor] are the typed amount or the derived one
 * (`null` when neither can be known). [amountPrefillText] and [eurPrefillText] are the derived amounts as the text of
 * their field, `null` when that field was typed. [invalid] says that a typed text is not an amount, and [bothTyped]
 * that the user gave both.
 */
internal class CurrencyAmounts(
    val amountMinor: Long?,
    val eurMinor: Long?,
    val amountPrefillText: String?,
    val eurPrefillText: String?,
    val invalid: Boolean,
    val bothTyped: Boolean,
)

/**
 * Resolves what was typed in the amount ([amountText], in [currency]) and the euro ([eurText]) fields. Each is typed or
 * derived from the other at [rateScaled], the rate of the day: the amount times the rate, or the euros over it, rounded
 * half-even to the minor units. Both typed are kept as typed. Without a rate only what was typed is known. A holding in
 * euros has no second amount. Exact: no floating point.
 */
internal fun resolveCurrencyAmounts(
    currency: Currency,
    amountText: String,
    eurText: String?,
    rateScaled: Long?,
): CurrencyAmounts {
    val inEuros = currency == Currency.EUR
    val amountTyped = amountText.isNotBlank()
    val eurTyped = !inEuros && !eurText.isNullOrBlank()
    val amountParsed = if (amountTyped) parseAmountToMinor(amountText, currency) else null
    val eurParsed = if (eurTyped) parseAmountToMinor(eurText.orEmpty(), Currency.EUR) else null
    val canDerive = !inEuros && rateScaled != null
    val derivedAmount = eurParsed?.takeIf { canDerive && !amountTyped }?.let {
        derive { eurToHolding(it, currency, rateScaled) }
    }
    val derivedEur = amountParsed?.takeIf { canDerive && !eurTyped }?.let {
        derive { holdingToEur(it, currency, rateScaled) }
    }
    val amountMinor = if (amountTyped) amountParsed else derivedAmount?.takeIf { it > 0 }
    val eurMinor = if (eurTyped) eurParsed else derivedEur
    return CurrencyAmounts(
        amountMinor = amountMinor,
        eurMinor = eurMinor,
        amountPrefillText = amountMinor?.takeIf { !amountTyped }?.let { amountInputText(Money(it), currency) },
        eurPrefillText = eurMinor?.takeIf { !eurTyped }?.let { amountInputText(Money(it), Currency.EUR) },
        invalid = (amountTyped && amountParsed == null) || (eurTyped && eurParsed == null),
        bothTyped = amountTyped && eurTyped,
    )
}

/** The conversion, or `null` without a rate or when the result does not fit. */
private inline fun derive(convert: () -> Long?): Long? = try {
    convert()
} catch (e: ArithmeticException) {
    null
}

/** [minorUnits] of [currency] at the rate of the day in euro cents, the way `CurrencyAmount.toEur` does. */
internal fun holdingToEur(minorUnits: Long, currency: Currency, rateScaled: Long?): Long? =
    rateScaled?.let { CurrencyAmount(Money(minorUnits), currency).toEur(FxRates(mapOf(currency to it))).minorUnits }

/**
 * The inverse of `CurrencyAmount.toEur`: the minor units of [currency] that [eurMinor] euro cents come to at
 * [rateScaled], `eurMinor * RATE_SCALE / (rate * 10^(EUR.decimals - currency.decimals))`, rounded half-even.
 */
internal fun eurToHolding(eurMinor: Long, currency: Currency, rateScaled: Long?): Long? {
    if (rateScaled == null) return null
    var divisor: Long = rateScaled
    repeat(Currency.EUR.decimals - currency.decimals) { divisor *= TEN }
    return mulDivHalfEven(eurMinor, RATE_SCALE, divisor)
}

/**
 * "1 USD = 0,8612 €": the euros per unit of the holding [currency] that [amountMinor] and [eurMinor] imply, with four
 * decimals. `null` while either is missing, or for euros.
 */
internal fun impliedRateText(currency: Currency, amountMinor: Long?, eurMinor: Long?): String? {
    if (currency == Currency.EUR || amountMinor == null || eurMinor == null || amountMinor <= 0) return null
    var scale = 1L
    repeat(RATE_DECIMALS + currency.decimals - Currency.EUR.decimals) { scale *= TEN }
    var factor = 1L
    repeat(RATE_DECIMALS) { factor *= TEN }
    return try {
        val rate = mulDivHalfEven(eurMinor, scale, amountMinor)
        val fraction = (rate % factor).toString().padStart(RATE_DECIMALS, '0')
        "1 ${currency.code} = ${rate / factor},$fraction ${currencySymbol(Currency.EUR)}"
    } catch (e: ArithmeticException) {
        null
    }
}

/** The hint under the euro field and the one under the amount field; each `null` when it does not apply. */
internal class MissingRateHints(val eurHint: String?, val amountHint: String?)

/**
 * Why the form is waiting for a field the day's rate cannot fill in. With no rate, the euros are asked for unless typed
 * (even before the amount: the form cannot be saved without them), and the amount is asked for when only the euros are
 * typed. A [costOptional] cost (an opening balance) asks for nothing while both fields are blank.
 */
internal fun missingRateHints(
    currency: Currency,
    rateScaled: Long?,
    amountText: String,
    eurText: String?,
    costOptional: Boolean,
): MissingRateHints {
    if (currency == Currency.EUR || rateScaled != null) return MissingRateHints(null, null)
    val amountTyped = amountText.isNotBlank()
    val eurTyped = !eurText.isNullOrBlank()
    val eurHint = MISSING_EUR_HINT.takeIf { !eurTyped && (amountTyped || !costOptional) }
    val amountHint = missingAmountHint(currency).takeIf { eurTyped && !amountTyped }
    return MissingRateHints(eurHint, amountHint)
}
