package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.manualTestHolding
import com.denebapps.patrimonio.testing.quotedTestHolding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavingsGoalHoldingsTest {
    private val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 1 USD = 0.5 EUR

    private val account =
        Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(10_000), Currency.EUR))

    private fun group(vararg holdingIds: String, assetIds: Set<String> = setOf("a1")) = AccountGroup(
        id = "g1",
        name = "Casa",
        showBalance = true,
        sortOrder = 1,
        memberAssetIds = assetIds,
        memberHoldingIds = holdingIds.toSet(),
    )

    private fun goal(
        currency: Currency = Currency.EUR,
        linkedGroupId: String? = "g1",
        linkedAssetIds: Set<String> = emptySet(),
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = "goal-1",
        name = "Casa",
        target = CurrencyAmount(Money(1_000_000), currency),
        targetDate = null,
        linkedAssetIds = linkedAssetIds,
        linkedGroupIds = setOfNotNull(linkedGroupId),
        lifecycle = lifecycle,
        progress = Money.ZERO,
    )

    private val twoFunds = listOf(
        // 50.00 EUR
        quotedTestHolding("money-market", units = 10, price = 5),
        // 100.00 EUR
        quotedTestHolding("bond", units = 4, price = 25),
    )

    @Test
    fun `a group goal tracks its account plus two funds in the goal currency`() {
        val groups = listOf(group("money-market", "bond"))

        assertEquals(Money(25_000), trackedBalance(goal(), listOf(account), groups, rates, twoFunds))
        // The same balance in USD: 250.00 EUR = 500.00 USD.
        assertEquals(Money(50_000), trackedBalance(goal(Currency.USD), listOf(account), groups, rates, twoFunds))
    }

    @Test
    fun `the balance follows the price of a member holding`() {
        val groups = listOf(group("money-market", "bond"))
        val repriced = listOf(quotedTestHolding("money-market", units = 10, price = 6), twoFunds[1])

        assertEquals(Money(26_000), trackedBalance(goal(), listOf(account), groups, rates, repriced))
    }

    @Test
    fun `a foreign currency holding is converted with the same rates as an account`() {
        val groups = listOf(group("fund-usd"))
        val holdings = listOf(manualTestHolding("fund-usd", 40_000, Currency.USD)) // 400.00 USD = 200.00 EUR

        assertEquals(Money(30_000), trackedBalance(goal(), listOf(account), groups, rates, holdings))
    }

    @Test
    fun `a holding currency without a usable rate makes the balance unavailable`() {
        val groups = listOf(group("fund-usd"))
        val holdings = listOf(manualTestHolding("fund-usd", 40_000, Currency.USD))

        assertNull(trackedBalance(goal(), listOf(account), groups, rates = null, holdings = holdings))
        assertNull(trackedBalance(goal(), listOf(account), groups, FxRates(mapOf(Currency.USD to 0L)), holdings))
    }

    @Test
    fun `same-currency holdings need no rates`() {
        val groups = listOf(group("money-market", "bond"))

        assertEquals(Money(25_000), trackedBalance(goal(), listOf(account), groups, rates = null, holdings = twoFunds))
    }

    @Test
    fun `an unpriced holding counts as zero and never makes the balance unavailable`() {
        val groups = listOf(group("unpriced"))
        val holdings = listOf(quotedTestHolding("unpriced", units = 10, price = null, currency = Currency.USD))

        assertEquals(Money(10_000), trackedBalance(goal(), listOf(account), groups, rates = null, holdings = holdings))
    }

    @Test
    fun `a closed position counts as zero`() {
        val groups = listOf(group("closed"))
        val holdings = listOf(quotedTestHolding("closed", units = 0, price = 5))

        assertEquals(Money(10_000), trackedBalance(goal(), listOf(account), groups, rates, holdings))
    }

    @Test
    fun `a group of only holdings tracks their sum`() {
        val groups = listOf(group("money-market", "bond", assetIds = emptySet()))

        assertEquals(Money(15_000), trackedBalance(goal(), listOf(account), groups, rates, twoFunds))
    }

    @Test
    fun `goals linked to accounts ignore holdings`() {
        val direct = goal(linkedGroupId = null, linkedAssetIds = setOf("a1"))

        assertEquals(Money(10_000), trackedBalance(direct, listOf(account), listOf(group("bond")), rates, twoFunds))
    }

    @Test
    fun `closed goals and goals on a missing group still track nothing`() {
        val groups = listOf(group("bond"))

        assertNull(
            trackedBalance(goal(lifecycle = SavingsGoalLifecycle.CLOSED), listOf(account), groups, rates, twoFunds),
        )
        assertNull(trackedBalance(goal(linkedGroupId = "deleted"), listOf(account), groups, rates, twoFunds))
    }

    @Test
    fun `goals sharing a group are still reported together`() {
        val first = goal()
        val second = goal().copy(id = "goal-2", name = "Reforma")

        val sharing = goalsSharingLinkedBalance(listOf(first, second), listOf(group()))

        assertEquals(
            listOf(listOf("g1") to listOf("goal-1", "goal-2")),
            sharing.map {
                it.targetIds to it.goals.map(SavingsGoal::id)
            },
        )
    }
}
