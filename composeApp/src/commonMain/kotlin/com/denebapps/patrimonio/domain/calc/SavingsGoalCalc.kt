package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
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

/** True when [goal]'s progress follows the balance of its linked accounts, products and groups instead of its own
 *  allocations: only open goals do; closed and cancelled ones keep their ledger progress. */
val SavingsGoal.tracksLinkedBalance: Boolean
    get() = lifecycle == SavingsGoalLifecycle.OPEN && hasLinks

/**
 * The balance a goal that [tracksLinkedBalance] shows as its progress, in the goal's currency: the sum of the
 * **deduplicated union** of everything it follows, converted at [rates] (same-currency amounts need no rates).
 * The accounts are the goal's own plus the members of its linked groups, the products ([holdings]) its own plus
 * those held by its linked groups, so an account or product reached by several links (directly and through a group,
 * or through two groups) counts once. A product with no price yet is worth nothing yet: it adds zero and never makes
 * the balance unavailable. Null when the goal does not track a balance, a linked account, product or group is not in
 * [assets]/[holdings]/[groups] (the builtin group never counts as linkable), or a needed conversion has no usable
 * rate. Never negative.
 */
fun trackedBalance(
    goal: SavingsGoal,
    assets: List<Asset>,
    groups: List<AccountGroup>,
    rates: FxRates?,
    holdings: List<PortfolioHolding> = emptyList(),
): Money? {
    if (!goal.tracksLinkedBalance) return null
    val currency = goal.target.currency
    val linkedGroups = goal.linkedGroupIds.map { id ->
        groups.firstOrNull { it.id == id && it.id != AccountGroup.ALL_ACCOUNTS_ID } ?: return null
    }
    val directAssets = assets.filter { it.id in goal.linkedAssetIds }
    if (directAssets.size != goal.linkedAssetIds.size) return null
    val directHoldings = holdings.filter { it.id in goal.linkedHoldingIds }
    if (directHoldings.size != goal.linkedHoldingIds.size) return null
    val trackedAssets = (directAssets + linkedGroups.flatMap { groupMembers(it, assets) }).distinctBy { it.id }
    val trackedHoldings = (directHoldings + linkedGroups.flatMap { groupHoldings(it, holdings) })
        .distinctBy { it.id }
    val amounts = trackedAssets.map { it.amount } + trackedHoldings.mapNotNull { it.value() }
    val balance = amounts.fold(Money.ZERO) { total, amount ->
        checkedSavingsGoalAdd(total, convertOrNull(amount, currency, rates) ?: return null)
    }
    return if (balance < Money.ZERO) Money.ZERO else balance
}

/**
 * Two or more open goals ([goals]) whose balances overlap. [targetIds] names what they share: the links all of them
 * have in common (an account, a product or a group id) when those cover the overlap, otherwise the shared accounts
 * and products themselves.
 */
data class SharedLinkedBalance(val targetIds: List<String>, val goals: List<SavingsGoal>)

/**
 * Open goals whose **effective** member sets overlap: the accounts and products each follows directly or through its
 * linked [groups]. Each of those goals counts the shared members in full, so their progress adds up to more than
 * there is. Since the match is on members and not on link ids, a goal following an account and another following a
 * group that holds it are reported (before, only the same id matched). Members shared by exactly the same goals come
 * back as one entry; its [SharedLinkedBalance.targetIds] are the links every goal of the entry has in common that
 * reach those members (two goals on the same two accounts give one entry with both ids, two goals on the same group
 * the group id), or, when the goals reach them through different links, the shared account and product ids. Groups
 * without members, unknown groups and the builtin group share nothing.
 */
fun goalsSharingLinkedBalance(goals: List<SavingsGoal>, groups: List<AccountGroup>): List<SharedLinkedBalance> {
    val groupsById = groups.filter { it.id != AccountGroup.ALL_ACCOUNTS_ID }.associateBy { it.id }
    fun membersOf(goal: SavingsGoal): Set<MemberKey> {
        val viaGroups = goal.linkedGroupIds.mapNotNull(groupsById::get).flatMap { group ->
            group.memberAssetIds.orEmpty().map<String, MemberKey> { MemberKey.Account(it) } +
                group.memberHoldingIds.map { MemberKey.Product(it) }
        }
        val direct = goal.linkedAssetIds.map<String, MemberKey> { MemberKey.Account(it) } +
            goal.linkedHoldingIds.map { MemberKey.Product(it) }
        return (direct + viaGroups).toSet()
    }
    fun linksOf(goal: SavingsGoal): List<String> =
        goal.linkedAssetIds.toList() + goal.linkedHoldingIds + goal.linkedGroupIds
    fun covers(linkId: String, goal: SavingsGoal, members: Set<MemberKey>): Boolean = when (linkId) {
        in goal.linkedAssetIds -> MemberKey.Account(linkId) in members
        in goal.linkedHoldingIds -> MemberKey.Product(linkId) in members
        else -> groupsById[linkId]?.let { group ->
            group.memberAssetIds.orEmpty().any { MemberKey.Account(it) in members } ||
                group.memberHoldingIds.any { MemberKey.Product(it) in members }
        } ?: false
    }

    val tracking = goals.filter { it.tracksLinkedBalance }
    val goalsByMember = tracking
        .flatMap { goal -> membersOf(goal).map { it to goal } }
        .groupBy({ (member, _) -> member }, { (_, goal) -> goal })
        .filterValues { it.size > 1 }
    return goalsByMember.entries
        .groupBy({ it.value }, { it.key })
        .map { (sharing, members) ->
            val memberSet = members.toSet()
            val common = sharing.map { linksOf(it).toSet() }.reduce(Set<String>::intersect)
            val commonLinks = linksOf(sharing.first()).filter { it in common && covers(it, sharing.first(), memberSet) }
            val targetIds = commonLinks.ifEmpty { members.map { it.id } }
            SharedLinkedBalance(targetIds.distinct(), sharing)
        }
}

/** An account or a product an open goal effectively follows. */
private sealed interface MemberKey {
    val id: String

    data class Account(override val id: String) : MemberKey

    data class Product(override val id: String) : MemberKey
}

/** Same-currency amounts never need rates; otherwise null when [rates] are absent or unusable (<= 0). */
private fun convertOrNull(amount: CurrencyAmount, target: Currency, rates: FxRates?): Money? {
    if (amount.currency == target) return amount.amount
    if (rates == null) return null
    if (rates.rateToEurScaled(amount.currency) <= 0 || rates.rateToEurScaled(target) <= 0) return null
    return amount.convertTo(target, rates)
}
