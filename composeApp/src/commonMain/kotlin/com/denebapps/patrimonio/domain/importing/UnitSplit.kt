package com.denebapps.patrimonio.domain.importing

import com.denebapps.patrimonio.domain.calc.checkedAdd
import com.denebapps.patrimonio.domain.calc.mulDivHalfEven
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PRICE_SCALE
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.UNITS_SCALE
import com.denebapps.patrimonio.domain.model.Units
import kotlin.math.abs

/** Cents of a EUR amount over a price (scaled by [PRICE_SCALE]) give units scaled by [UNITS_SCALE]: 100 cents per EUR. */
private const val CENTS_TO_SCALED_UNITS = UNITS_SCALE * PRICE_SCALE / 100

/** Micro-euros in a cent: [Units] x [Price] is a value in millionths of a euro once divided by [UNITS_SCALE]. */
private const val MICRO_EUR_PER_CENT = 10_000L

/** The part of the market value the units may be away from the file before they look wrong: 2 %. */
private const val OFF_PERCENT = 2L
private const val PERCENT = 100L

/** Why units cannot be shared among the lots of a file. */
sealed interface UnitSplitError {
    /** The total has fewer units than lots (or a lot is worth so little that its share rounds to nothing). */
    data object TotalTooSmall : UnitSplitError

    /** The lot at [lotIndex] has no market value, so there is nothing to share the units by. */
    data class LotWithoutMarketValue(val lotIndex: Int) : UnitSplitError
}

/**
 * Shares [total] among [lots] in proportion to their market value, one [Units] per lot in the same order, adding up
 * exactly to [total]: the largest-remainder method on the scaled integers. Each lot gets the whole part of its exact
 * share, and the units left over (fewer than lots) go one each to the lots whose shares lost the most, an earlier lot
 * winning a tie.
 *
 * @throws IllegalArgumentException when there are no [lots].
 */
fun splitUnits(lots: List<OpenLot>, total: Units): ImportResult<List<Units>, UnitSplitError> {
    require(lots.isNotEmpty()) { "there are no lots to share the units among" }
    lots.forEachIndexed { index, lot ->
        if (lot.marketValue.minorUnits == 0L) return ImportResult.Err(UnitSplitError.LotWithoutMarketValue(index))
    }
    val weights = lots.map { it.marketValue.minorUnits }
    val weightSum = weights.fold(0L, ::checkedAdd)
    val shares = weights.map { share(total.scaled, it, weightSum) }

    val units = shares.map { it.whole }.toLongArray()
    val leftOver = (total.scaled - units.sum()).toInt()
    val byLostShare = lots.indices.sortedWith(compareByDescending<Int> { shares[it].lost }.thenBy { it })
    byLostShare.take(leftOver).forEach { units[it]++ }

    if (units.any { it <= 0 }) return ImportResult.Err(UnitSplitError.TotalTooSmall)
    return ImportResult.Ok(units.map { Units(it) })
}

/** The exact share `total * weight / weightSum` as its [whole] part and the [lost] part, in `weightSum`ths. */
private class Share(val whole: Long, val lost: Long)

private fun share(total: Long, weight: Long, weightSum: Long): Share {
    val rounded = mulDivHalfEven(total, weight, weightSum)
    // The product does not fit in a Long, but its distance to `rounded * weightSum` is at most weightSum / 2, so the
    // wrapped Long arithmetic (which is exact modulo 2^64) yields that distance exactly.
    val distance = total * weight - rounded * weightSum
    return if (distance >= 0) Share(rounded, distance) else Share(rounded - 1, distance + weightSum)
}

/**
 * The units a fund probably has: the market value of all the [lots] over the latest [price], rounded half to even to
 * the units scale. `null` when there is no price, or no value or price to divide by. Only for EUR funds.
 *
 * @throws IllegalArgumentException when [price] is not in EUR.
 */
fun estimateTotalUnits(lots: List<OpenLot>, price: Price?): Units? {
    if (price == null) return null
    require(price.currency == Currency.EUR) { "the units estimate needs a EUR price, was ${price.currency}" }
    if (price.scaled == 0L) return null
    val cents = lots.fold(0L) { sum, lot -> checkedAdd(sum, lot.marketValue.minorUnits) }
    return mulDivHalfEven(cents, CENTS_TO_SCALED_UNITS, price.scaled).takeIf { it > 0 }?.let(::Units)
}

/**
 * Whether [totalUnits] x [latestPrice] is more than 2 % away from the file's [totalMarketValue] (EUR), which hints at
 * units copied wrongly or a file of another day. Exact integer arithmetic, compared in millionths of a euro.
 *
 * @throws IllegalArgumentException when [latestPrice] is not in EUR.
 */
fun unitsLookOff(totalUnits: Units, latestPrice: Price, totalMarketValue: Money): Boolean {
    require(latestPrice.currency == Currency.EUR) { "the check needs a EUR price, was ${latestPrice.currency}" }
    val valueMicro = mulDivHalfEven(totalUnits.scaled, latestPrice.scaled, UNITS_SCALE)
    val fileMicro = totalMarketValue.minorUnits * MICRO_EUR_PER_CENT
    return abs(valueMicro - fileMicro) * PERCENT > fileMicro * OFF_PERCENT
}
