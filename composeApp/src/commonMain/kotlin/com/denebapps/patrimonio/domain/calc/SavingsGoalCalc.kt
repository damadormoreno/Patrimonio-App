package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalAllocationEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalDeltaException
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.SavingsGoalArithmeticOverflowException

fun checkedSavingsGoalProgress(events: Iterable<SavingsGoalAllocationEvent>): Money =
    events.fold(Money.ZERO) { progress, event ->
        if (event.delta == Money.ZERO) {
            throw InvalidSavingsGoalDeltaException(event.delta.minorUnits)
        }
        checkedSavingsGoalAdd(progress, event.delta).also { updated ->
            if (updated < Money.ZERO) {
                throw NegativeSavingsGoalProgressException(updated.minorUnits)
            }
        }
    }

fun checkedSavingsGoalAdd(left: Money, right: Money): Money {
    val leftMinor = left.minorUnits
    val rightMinor = right.minorUnits
    if (
        (rightMinor > 0 && leftMinor > Long.MAX_VALUE - rightMinor) ||
        (rightMinor < 0 && leftMinor < Long.MIN_VALUE - rightMinor)
    ) {
        throw SavingsGoalArithmeticOverflowException(leftMinor, "+", rightMinor)
    }
    return Money(leftMinor + rightMinor)
}

fun checkedSavingsGoalSubtract(left: Money, right: Money): Money {
    val leftMinor = left.minorUnits
    val rightMinor = right.minorUnits
    if (
        (rightMinor > 0 && leftMinor < Long.MIN_VALUE + rightMinor) ||
        (rightMinor < 0 && leftMinor > Long.MAX_VALUE + rightMinor)
    ) {
        throw SavingsGoalArithmeticOverflowException(leftMinor, "-", rightMinor)
    }
    return Money(leftMinor - rightMinor)
}

fun checkedSavingsGoalNegate(value: Money): Money {
    if (value.minorUnits == Long.MIN_VALUE) {
        throw SavingsGoalArithmeticOverflowException(0, "-", value.minorUnits)
    }
    return Money(-value.minorUnits)
}

/** Returns null when cancelling an empty goal so no forbidden zero-delta event is appended. */
fun savingsGoalCancellationDelta(progress: Money): Money? {
    if (progress < Money.ZERO) {
        throw NegativeSavingsGoalProgressException(progress.minorUnits)
    }
    return if (progress == Money.ZERO) null else checkedSavingsGoalNegate(progress)
}

/** True when [goal]'s progress follows the balance of its linked asset or group instead of its own
 *  allocations: only open goals do; closed and cancelled ones keep their ledger progress. */
val SavingsGoal.tracksLinkedBalance: Boolean
    get() = lifecycle == SavingsGoalLifecycle.OPEN && (linkedAssetIds.isNotEmpty() || linkedGroupId != null)

/**
 * The balance a goal that [tracksLinkedBalance] shows as its progress, in the goal's currency: the sum
 * of its linked assets, or of the linked group's members, converted at [rates] (same-currency amounts
 * need no rates). Null when the goal does not track a balance, a linked asset or the group is not in
 * [assets]/[groups], or a needed conversion has no usable rate. Never negative.
 */
fun trackedBalance(goal: SavingsGoal, assets: List<Asset>, groups: List<AccountGroup>, rates: FxRates?): Money? {
    if (!goal.tracksLinkedBalance) return null
    val currency = goal.target.currency
    val members = if (goal.linkedAssetIds.isNotEmpty()) {
        val linked = assets.filter { it.id in goal.linkedAssetIds }
        if (linked.size != goal.linkedAssetIds.size) return null
        linked
    } else {
        val group = groups.firstOrNull { it.id == goal.linkedGroupId && it.id != AccountGroup.ALL_ACCOUNTS_ID }
        groupMembers(group ?: return null, assets)
    }
    val balance = members.fold(Money.ZERO) { total, asset ->
        checkedSavingsGoalAdd(total, convertOrNull(asset.amount, currency, rates) ?: return null)
    }
    return if (balance < Money.ZERO) Money.ZERO else balance
}

/** Two or more open goals ([goals]) that all count the same linked assets or group ([targetIds]). */
data class SharedLinkedBalance(val targetIds: List<String>, val goals: List<SavingsGoal>)

/**
 * Open goals that track the same asset or the same group. Each of those goals counts the whole balance,
 * so their progress adds up to more than there is. Targets shared by exactly the same goals come back
 * as one entry (two goals following the same two accounts give one entry with both ids). An asset
 * inside a linked group is not matched against a goal linked to that asset.
 */
fun goalsSharingLinkedBalance(goals: List<SavingsGoal>): List<SharedLinkedBalance> = goals
    .filter { it.tracksLinkedBalance }
    .flatMap { goal -> (goal.linkedAssetIds + listOfNotNull(goal.linkedGroupId)).map { it to goal } }
    .groupBy({ (targetId, _) -> targetId }, { (_, goal) -> goal })
    .filterValues { it.size > 1 }
    .entries
    .groupBy({ it.value }, { it.key })
    .map { (sharing, targetIds) -> SharedLinkedBalance(targetIds, sharing) }

/** Same-currency amounts never need rates; otherwise null when [rates] are absent or unusable (<= 0). */
private fun convertOrNull(amount: CurrencyAmount, target: Currency, rates: FxRates?): Money? {
    if (amount.currency == target) return amount.amount
    if (rates == null) return null
    if (rates.rateToEurScaled(amount.currency) <= 0 || rates.rateToEurScaled(target) <= 0) return null
    return amount.convertTo(target, rates)
}
