package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalAllocationEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalCoverage
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalDeltaException
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.SavingsGoalArithmeticOverflowException
import com.denebapps.patrimonio.domain.repository.SavingsGoalCurrencyMismatchException

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

/**
 * Coverage of [goal]'s link: [SavingsGoalCoverage.SharedAsset] for an asset link,
 * [SavingsGoalCoverage.SharedGroup] for a group link and [SavingsGoalCoverage.Unavailable] when the goal is
 * unlinked, the target no longer exists, or a conversion a group needs cannot be done.
 *
 * [groups] and [rates] only matter for group links. A group link converts every amount to the goal's
 * currency; [rates] `null` means "no rates available", which makes any needed conversion (a member or a
 * linked goal in another currency) [SavingsGoalCoverage.Unavailable]. Same-currency groups need no rates.
 */
fun savingsGoalCoverage(
    goal: SavingsGoal,
    assets: List<Asset>,
    allGoals: List<SavingsGoal>,
    groups: List<AccountGroup> = emptyList(),
    rates: FxRates? = null,
): SavingsGoalCoverage {
    goal.linkedGroupId?.let { groupId -> return groupCoverage(goal, groupId, assets, allGoals, groups, rates) }
    val assetId = goal.linkedAssetId ?: return SavingsGoalCoverage.Unavailable
    val asset = assets.firstOrNull { it.id == assetId } ?: return SavingsGoalCoverage.Unavailable

    val reserved =
        allGoals
            .asSequence()
            .filter { it.linkedAssetId == assetId }
            .distinctBy { it.id }
            .fold(Money.ZERO) { total, linkedGoal ->
                if (linkedGoal.target.currency != asset.amount.currency) {
                    throw SavingsGoalCurrencyMismatchException(
                        goalCurrency = linkedGoal.target.currency,
                        assetCurrency = asset.amount.currency,
                    )
                }
                val contribution =
                    when (linkedGoal.lifecycle) {
                        SavingsGoalLifecycle.CANCELLED -> Money.ZERO
                        SavingsGoalLifecycle.OPEN,
                        SavingsGoalLifecycle.CLOSED,
                        -> linkedGoal.progress
                    }
                if (contribution < Money.ZERO) {
                    throw NegativeSavingsGoalProgressException(contribution.minorUnits)
                }
                checkedSavingsGoalAdd(total, contribution)
            }

    return SavingsGoalCoverage.SharedAsset(
        assetId = assetId,
        balance = asset.amount,
        reserved = CurrencyAmount(reserved, asset.amount.currency),
    )
}

private fun groupCoverage(
    goal: SavingsGoal,
    groupId: String,
    assets: List<Asset>,
    allGoals: List<SavingsGoal>,
    groups: List<AccountGroup>,
    rates: FxRates?,
): SavingsGoalCoverage {
    if (groupId == AccountGroup.ALL_ACCOUNTS_ID) return SavingsGoalCoverage.Unavailable
    val group = groups.firstOrNull { it.id == groupId } ?: return SavingsGoalCoverage.Unavailable
    val currency = goal.target.currency

    val balance =
        groupMembers(group, assets).fold(Money.ZERO) { total, member ->
            val converted = convertOrNull(member.amount, currency, rates) ?: return SavingsGoalCoverage.Unavailable
            checkedSavingsGoalAdd(total, converted)
        }

    val reserved =
        allGoals
            .asSequence()
            .filter { it.linkedGroupId == groupId }
            .distinctBy { it.id }
            .fold(Money.ZERO) { total, linkedGoal ->
                val contribution =
                    when (linkedGoal.lifecycle) {
                        SavingsGoalLifecycle.CANCELLED -> Money.ZERO
                        SavingsGoalLifecycle.OPEN,
                        SavingsGoalLifecycle.CLOSED,
                        -> linkedGoal.progress
                    }
                if (contribution < Money.ZERO) {
                    throw NegativeSavingsGoalProgressException(contribution.minorUnits)
                }
                val converted =
                    convertOrNull(CurrencyAmount(contribution, linkedGoal.target.currency), currency, rates)
                        ?: return SavingsGoalCoverage.Unavailable
                checkedSavingsGoalAdd(total, converted)
            }

    return SavingsGoalCoverage.SharedGroup(
        groupId = groupId,
        balance = CurrencyAmount(balance, currency),
        reserved = CurrencyAmount(reserved, currency),
    )
}

/** Same-currency amounts never need rates; otherwise null when [rates] are absent or unusable (<= 0). */
private fun convertOrNull(amount: CurrencyAmount, target: Currency, rates: FxRates?): Money? {
    if (amount.currency == target) return amount.amount
    if (rates == null) return null
    if (rates.rateToEurScaled(amount.currency) <= 0 || rates.rateToEurScaled(target) <= 0) return null
    return amount.convertTo(target, rates)
}
