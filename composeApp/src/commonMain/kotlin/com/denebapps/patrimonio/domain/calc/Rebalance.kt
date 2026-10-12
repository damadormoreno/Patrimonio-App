package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Money
import kotlin.math.abs

/** What 100 % is, in basis points (hundredths of a percentage point): the targets of a portfolio add up to it. */
const val TOTAL_TARGET_BP = 10_000

/** The tolerance band a portfolio gets until the user changes it: 5 percentage points either side of the target. */
const val DEFAULT_REBALANCE_TOLERANCE_BP = 500

/** Why a set of targets is not valid; see [validateTargets]. */
sealed interface TargetsError {
    /** The target of [assetClass] is [targetBp], outside 0 to [TOTAL_TARGET_BP]. */
    data class OutOfRange(val assetClass: AssetClass, val targetBp: Int) : TargetsError

    /** The targets add up to [totalBp] instead of [TOTAL_TARGET_BP]. */
    data class WrongSum(val totalBp: Int) : TargetsError
}

/**
 * Checks the per-class [targets] in basis points: each one within 0 to [TOTAL_TARGET_BP] and all adding up to exactly
 * [TOTAL_TARGET_BP]; classes not in the map count as 0. Returns `null` when valid, else the first problem found: an
 * out of range class (the first one in [AssetClass] order) before a wrong sum.
 */
fun validateTargets(targets: Map<AssetClass, Int>): TargetsError? {
    for (assetClass in AssetClass.entries) {
        val bp = targets[assetClass] ?: continue
        if (bp !in 0..TOTAL_TARGET_BP) return TargetsError.OutOfRange(assetClass, bp)
    }
    val total = targets.values.sum()
    return if (total == TOTAL_TARGET_BP) null else TargetsError.WrongSum(total)
}

/**
 * One asset class of a portfolio against its target. [weightBp] is [value]'s share of the classified total rounded
 * half-even to basis points, [driftBp] is [weightBp] - [targetBp] (positive: overweight), and [outOfBand] says the
 * drift is beyond the tolerance. The band is checked on the rounded drift, so it is what the user sees.
 */
data class ClassStatus(
    val assetClass: AssetClass,
    val value: Money,
    val weightBp: Int,
    val targetBp: Int,
    val driftBp: Int,
    val outOfBand: Boolean,
)

/** [amount] (EUR) to put in [assetClass]. */
data class ContributionShare(val assetClass: AssetClass, val amount: Money)

/**
 * How a portfolio stands against its targets.
 *
 * - [totalValue] is the EUR value of the classified holdings; [unclassifiedValue] and [unclassifiedCount] are the
 *   holdings with no class, which are left out of it and only reported.
 * - [classes] has the held classes and the targeted ones, in [AssetClass] order.
 * - [needsRebalancing]: some class is out of band. It is never true when [totalValue] is zero.
 * - [contribution] is the least money that, put in the targeted classes and nothing sold, leaves every targeted class
 *   on its target; [contributionSplit] says where (every class with a target, in [AssetClass] order, adding up to
 *   [contribution] exactly). It does not depend on the band: a portfolio within it still reports the figure.
 * - [onlySelling]: classes that are held but have a target of 0; no contribution reduces them, so they are left out
 *   of [contribution] and only a sale fixes them.
 */
data class RebalanceStatus(
    val totalValue: Money,
    val classes: List<ClassStatus>,
    val toleranceBp: Int,
    val needsRebalancing: Boolean,
    val contribution: Money,
    val contributionSplit: List<ContributionShare>,
    val onlySelling: List<AssetClass>,
    val unclassifiedValue: Money,
    val unclassifiedCount: Int,
) {
    /**
     * Splits [amount], money the user plans to put in, over the targeted classes, the most underweight first: the
     * classes with the lowest value-to-target ratio are filled up to a common level until [amount] is used; once the
     * amount reaches [contribution] every targeted class takes part and lands exactly on its target. Exact to the
     * cent: the shares add up to [amount] (leftover cents go to the largest fractional parts, ties by [AssetClass]
     * order). The result has every class with a target, in [AssetClass] order, some possibly at zero.
     */
    fun splitContribution(amount: Money): List<ContributionShare> {
        require(amount.minorUnits >= 0) { "amount must not be negative" }
        return fillTowardsTargets(classes.filter { it.targetBp > 0 }, amount.minorUnits)
    }
}

/**
 * The [RebalanceStatus] of a portfolio. [valuesByClass] is the EUR value per class (the classified holdings, as
 * `allocationByAssetClass` gives them), [unclassified] and [unclassifiedCount] the holdings with no class,
 * [targetsBp] the per-class target in basis points (valid per [validateTargets]) and [toleranceBp] the band
 * (0 to [TOTAL_TARGET_BP]); a drift equal to it is still inside.
 *
 * The contribution is `C = max over targeted classes of ceil(value x 10 000 / target) - V`, where V is the value of the
 * targeted classes only (a held class with target 0 cannot be fixed by adding money, see
 * [RebalanceStatus.onlySelling]), and never below 0. `V + C` is the smallest total at which no targeted class is above
 * its target; the ceiling keeps it so after rounding to cents, instead of leaving the biggest class a hair overweight.
 * Each class then gets `target x (V + C) - value`, which is never negative, floored to cents and completed to `C` with
 * the largest-remainder method. All arithmetic is on integers; products need to fit in a [Long] (up to ~9e12 EUR).
 *
 * @throws IllegalArgumentException for invalid targets, a tolerance out of range or a negative value or count.
 * @throws ArithmeticException when a product overflows [Long].
 */
fun rebalanceStatus(
    valuesByClass: Map<AssetClass, Money>,
    unclassified: Money,
    unclassifiedCount: Int,
    targetsBp: Map<AssetClass, Int>,
    toleranceBp: Int,
): RebalanceStatus {
    require(validateTargets(targetsBp) == null) { "invalid targets: ${validateTargets(targetsBp)}" }
    require(toleranceBp in 0..TOTAL_TARGET_BP) { "tolerance must be within 0 to $TOTAL_TARGET_BP bp, was $toleranceBp" }
    require(valuesByClass.values.all { it.minorUnits >= 0 }) { "class values must not be negative" }
    require(unclassified.minorUnits >= 0) { "unclassified value must not be negative" }
    require(unclassifiedCount >= 0) { "unclassified count must not be negative" }

    val total = valuesByClass.values.fold(0L) { sum, value -> checkedAdd(sum, value.minorUnits) }
    val classes = AssetClass.entries.mapNotNull { assetClass ->
        val value = valuesByClass[assetClass]?.minorUnits ?: 0L
        val target = targetsBp[assetClass] ?: 0
        if (value == 0L && target == 0) return@mapNotNull null
        val weight = if (total == 0L) 0 else mulDivHalfEven(value, TOTAL_TARGET_BP.toLong(), total).toInt()
        val drift = weight - target
        ClassStatus(assetClass, Money(value), weight, target, drift, outOfBand = total > 0 && abs(drift) > toleranceBp)
    }
    val targeted = classes.filter { it.targetBp > 0 }
    val contribution = minimalContribution(targeted)
    return RebalanceStatus(
        totalValue = Money(total),
        classes = classes,
        toleranceBp = toleranceBp,
        needsRebalancing = classes.any { it.outOfBand },
        contribution = Money(contribution),
        contributionSplit = fillTowardsTargets(targeted, contribution),
        onlySelling = classes.filter { it.targetBp == 0 }.map { it.assetClass },
        unclassifiedValue = unclassified,
        unclassifiedCount = unclassifiedCount,
    )
}

/** `max(ceil(value x 10 000 / target)) - V` over the [targeted] classes, `V` being their total; 0 when none is held. */
private fun minimalContribution(targeted: List<ClassStatus>): Long {
    val held = targeted.fold(0L) { sum, item -> checkedAdd(sum, item.value.minorUnits) }
    val needed = targeted.maxOf {
        ceilDiv(checkedMultiply(it.value.minorUnits, TOTAL_TARGET_BP.toLong()), it.targetBp.toLong())
    }
    return maxOf(needed - held, 0L)
}

private fun ceilDiv(numerator: Long, divisor: Long): Long = (numerator + divisor - 1) / divisor

/**
 * Water-filling of [amount] cents over the [targeted] classes. Sorted by `value / target`, the first k classes take
 * part: the k-th joins while its ratio is below the common level `(amount + sum of values) / sum of targets` the
 * ones already in would reach. A participating class gets `target x level - value`, as an exact fraction over the
 * sum of its participants' targets, floored; the cents still missing go to the largest fractional parts.
 */
private fun fillTowardsTargets(targeted: List<ClassStatus>, amount: Long): List<ContributionShare> {
    val ordered = targeted.sortedWith { a, b ->
        val byRatio = checkedMultiply(a.value.minorUnits, b.targetBp.toLong())
            .compareTo(checkedMultiply(b.value.minorUnits, a.targetBp.toLong()))
        if (byRatio != 0) byRatio else a.assetClass.compareTo(b.assetClass)
    }
    var sumValue = 0L
    var sumTarget = 0L
    var participants = 0
    for (item in ordered) {
        val joins = participants == 0 ||
            checkedMultiply(item.value.minorUnits, sumTarget) <
            checkedMultiply(checkedAdd(amount, sumValue), item.targetBp.toLong())
        if (!joins) break
        sumValue = checkedAdd(sumValue, item.value.minorUnits)
        sumTarget += item.targetBp
        participants++
    }

    val level = checkedAdd(amount, sumValue)
    val parts = ordered.take(participants).map { item ->
        val numerator = checkedMultiply(item.targetBp.toLong(), level) -
            checkedMultiply(item.value.minorUnits, sumTarget)
        Part(item.assetClass, numerator / sumTarget, numerator % sumTarget)
    }
    val missingCents = (amount - parts.sumOf { it.floor }).toInt()
    val extra = parts
        .sortedWith(compareByDescending<Part> { it.remainder }.thenBy { it.assetClass })
        .take(missingCents)
        .map { it.assetClass }
        .toSet()
    val shares = parts.associate { it.assetClass to it.floor + if (it.assetClass in extra) 1 else 0 }
    return targeted.map { ContributionShare(it.assetClass, Money(shares[it.assetClass] ?: 0L)) }
}

private class Part(val assetClass: AssetClass, val floor: Long, val remainder: Long)

private fun checkedMultiply(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val product = a * b
    if (product / b != a) throw ArithmeticException("long overflow multiplying $a and $b")
    return product
}
