package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.CurrencyAmount
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

fun savingsGoalCoverage(goal: SavingsGoal, assets: List<Asset>, allGoals: List<SavingsGoal>): SavingsGoalCoverage {
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
