package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

// The user's own returns, in EUR, from the FIFO ledgers of their positions: what a position or a whole scope (one
// portfolio, or all of them) cost, is worth, gained and yields a year. Pure and exact for money; only the annual
// return and the gain ratio are `Double`.

/**
 * The own-return figures of one position. [valueEur] is what it is worth now (`null` while a quoted holding has no
 * price); [unrealized] is the gain of its open lots at that value, split by whether their cost is known. [flows] are
 * its cash flows for the annual return and [xirr] that return (0.10 = 10 %): both `null` when some cost is unknown or
 * there is no value.
 */
data class PositionReturns(
    val ledger: PositionLedger,
    val valueEur: Money?,
    val unrealized: GainBreakdown?,
    val flows: List<CashFlow>?,
    val xirr: Double?,
) {
    /** The fees paid: those inside trades plus the standalone ones. */
    val feesEur: Money get() = ledger.tradeFeesEur + ledger.standaloneFeesEur

    /** Whether it sold or transferred everything out. */
    val isClosed: Boolean get() = ledger.unitsHeld.isZero

    /**
     * Whether every figure can be given: the position is priced, no open lot and no sale has an unknown cost, and its
     * cash flows are known. Only such positions take part in the figures of a scope.
     */
    val isComplete: Boolean
        get() = unrealized?.isComplete == true && ledger.realized.isComplete && flows != null
}

/**
 * The own returns of the position [ledger] was built from. [movements] are its own movements (those of the
 * position), [valueEur] what it is worth on [today] and [transferValueEur] the market value of a transfer leg (see
 * [positionCashFlows]).
 */
fun positionReturns(
    movements: List<Movement>,
    ledger: PositionLedger,
    today: LocalDate,
    valueEur: Money?,
    transferValueEur: (Movement) -> Money? = { null },
): PositionReturns {
    val flows = valueEur?.let { positionCashFlows(movements, ledger, today, it, transferValueEur) }
    return PositionReturns(
        ledger = ledger,
        valueEur = valueEur,
        unrealized = valueEur?.let(ledger::unrealizedGain),
        flows = flows,
        xirr = flows?.let(::xirr),
    )
}

/**
 * The own returns of a scope: the sum of its positions that are [PositionReturns.isComplete] ([covered] of the
 * [withMovements] that have movements, out of [productCount] products in the scope, with or without movements), and
 * the annual return of their combined cash flows — transfers between them cancel out, a transfer to or from a product
 * outside stays at its market value.
 */
data class ScopeReturns(
    val productCount: Int,
    val withMovements: Int,
    val covered: Int,
    val knownCostEur: Money,
    val valueEur: Money,
    val unrealizedGainEur: Money,
    val realizedGainEur: Money,
    val dividendsEur: Money,
    val feesEur: Money,
    val xirr: Double?,
) {
    /** The unrealized gain over the cost of the open lots (0.1 = 10 %); `null` when that cost is zero. */
    val unrealizedRatio: Double?
        get() = knownCostEur.minorUnits.takeIf { it > 0 }?.let { unrealizedGainEur.minorUnits.toDouble() / it }
}

/** [ScopeReturns] of [positions], the ones of a scope of [productCount] products that have movements. */
fun scopeReturns(productCount: Int, positions: List<PositionReturns>): ScopeReturns {
    val covered = positions.filter { it.isComplete }
    fun sum(of: (PositionReturns) -> Money) = covered.fold(Money.ZERO) { total, position -> total + of(position) }
    val combined = covered.takeIf { it.isNotEmpty() }?.map { checkNotNull(it.flows) }?.let(::combineCashFlows)
    return ScopeReturns(
        productCount = productCount,
        withMovements = positions.size,
        covered = covered.size,
        knownCostEur = sum { it.ledger.costBasis.knownCostEur },
        valueEur = sum { checkNotNull(it.valueEur) },
        unrealizedGainEur = sum { checkNotNull(it.unrealized).knownGainEur },
        realizedGainEur = sum { it.ledger.realized.knownGainEur },
        dividendsEur = sum { it.ledger.dividendsEur },
        feesEur = sum { it.feesEur },
        xirr = combined?.let(::xirr),
    )
}

/**
 * The price on [date]: its own, or the latest one before it within [maxGapDays] days (a weekend, a holiday). `null`
 * before the first price or when the nearest earlier one is older than that — a price is never invented and a later
 * one is never borrowed. The list is ascending by date.
 */
fun List<PricePoint>.priceOn(date: LocalDate, maxGapDays: Int = MAX_FORWARD_FILL_DAYS): Price? {
    val found = binarySearchBy(date) { it.date }
    val index = if (found >= 0) found else -found - 2
    if (index < 0) return null
    val point = this[index]
    return point.price.takeIf { point.date >= date.minus(maxGapDays, DateTimeUnit.DAY) }
}

/**
 * The market value, in EUR, of the units of a transfer leg on its date, from the EUR price history of the position:
 * `null` without a price near that day (the carried cost is used then, see [positionCashFlows]).
 */
fun transferMarketValue(eurPrices: List<PricePoint>): (Movement) -> Money? = { leg ->
    val units = leg.units
    val price = eurPrices.priceOn(leg.date)
    if (units == null || price == null) null else quotedValue(units, price).amount
}
