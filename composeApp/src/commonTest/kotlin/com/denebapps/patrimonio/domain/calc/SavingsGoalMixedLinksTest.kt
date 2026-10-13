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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A goal that follows any mix of accounts, portfolio products and groups: the balance, the sharing notices and the usage. */
class SavingsGoalMixedLinksTest {
    private val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 1 USD = 0.5 EUR

    // 100.00 EUR and 50.00 EUR.
    private val assets = listOf(asset("a1", 10_000), asset("a2", 5_000))

    private val holdings = listOf(
        // 50.00 EUR
        quotedTestHolding("money-market", units = 10, price = 5),
        // 100.00 EUR
        quotedTestHolding("bond", units = 4, price = 25),
    )

    private val groups = listOf(
        AccountGroup.allAccounts(),
        AccountGroup("g1", "Casa", true, 1, setOf("a1"), setOf("bond", "money-market")),
        AccountGroup("g2", "Viaje", true, 2, setOf("a1", "a2"), emptySet()),
    )

    @Test
    fun `a goal can be linked to accounts products and groups at the same time`() {
        val goal = goal(
            linkedAssetIds = setOf("a1"),
            linkedHoldingIds = setOf("bond"),
            linkedGroupIds = setOf("g1", "g2"),
        )

        assertTrue(goal.tracksLinkedBalance)
        assertTrue(goal.hasLinks)
        assertFalse(goal(linkedHoldingIds = emptySet()).hasLinks)
        assertTrue(goal(linkedHoldingIds = setOf("bond")).tracksLinkedBalance)
        assertTrue(goal(linkedGroupIds = setOf("g2")).tracksLinkedBalance)
        assertFalse(goal(linkedHoldingIds = setOf("bond"), lifecycle = SavingsGoalLifecycle.CLOSED).tracksLinkedBalance)
    }

    @Test
    fun `an account followed directly and through a linked group counts once`() {
        val goal = goal(linkedAssetIds = setOf("a1"), linkedGroupIds = setOf("g1"))

        // a1 (100.00) once + bond (100.00) + money-market (50.00); the group's own a1 is not added again.
        assertEquals(Money(25_000), trackedBalance(goal, assets, groups, rates, holdings))
    }

    @Test
    fun `a product followed directly and through a linked group counts once`() {
        val goal = goal(linkedHoldingIds = setOf("bond"), linkedGroupIds = setOf("g1"))

        assertEquals(Money(25_000), trackedBalance(goal, assets, groups, rates, holdings))
    }

    @Test
    fun `an account in two linked groups counts once`() {
        val goal = goal(linkedGroupIds = setOf("g1", "g2"))

        // a1 (100.00) and a2 (50.00) from g2, bond and money-market from g1; a1 is in both groups.
        assertEquals(Money(30_000), trackedBalance(goal, assets, groups, rates, holdings))
    }

    @Test
    fun `accounts products and groups add up in the goal currency`() {
        val usdFund = manualTestHolding("fund-usd", 40_000, Currency.USD) // 400.00 USD = 200.00 EUR
        val goal = goal(
            currency = Currency.USD,
            linkedAssetIds = setOf("a2"),
            linkedHoldingIds = setOf("fund-usd"),
            linkedGroupIds = setOf("g1"),
        )

        // In EUR: a2 50 + usd fund 200 + g1 (a1 100 + bond 100 + money-market 50) = 500.00 EUR = 1000.00 USD.
        assertEquals(Money(100_000), trackedBalance(goal, assets, groups, rates, holdings + usdFund))
    }

    @Test
    fun `a goal of only products tracks their sum`() {
        val goal = goal(linkedHoldingIds = setOf("bond", "money-market"))

        assertEquals(Money(15_000), trackedBalance(goal, assets, groups, rates = null, holdings = holdings))
    }

    @Test
    fun `an unpriced product counts as zero and never makes the balance unavailable`() {
        val unpriced = quotedTestHolding("unpriced", units = 10, price = null, currency = Currency.USD)
        val goal = goal(linkedAssetIds = setOf("a1"), linkedHoldingIds = setOf("unpriced"))

        assertEquals(Money(10_000), trackedBalance(goal, assets, groups, rates = null, holdings = holdings + unpriced))
    }

    @Test
    fun `a foreign product without a usable rate makes the balance unavailable`() {
        val usdFund = manualTestHolding("fund-usd", 40_000, Currency.USD)
        val goal = goal(linkedHoldingIds = setOf("fund-usd"))

        assertNull(trackedBalance(goal, assets, groups, rates = null, holdings = holdings + usdFund))
        assertNull(trackedBalance(goal, assets, groups, FxRates(mapOf(Currency.USD to 0L)), holdings + usdFund))
        assertEquals(Money(20_000), trackedBalance(goal, assets, groups, rates, holdings + usdFund))
    }

    @Test
    fun `a missing account product or group makes the balance unavailable`() {
        assertNull(trackedBalance(goal(linkedAssetIds = setOf("gone")), assets, groups, rates, holdings))
        assertNull(trackedBalance(goal(linkedHoldingIds = setOf("gone")), assets, groups, rates, holdings))
        assertNull(trackedBalance(goal(linkedGroupIds = setOf("g1", "gone")), assets, groups, rates, holdings))
        assertNull(
            trackedBalance(goal(linkedGroupIds = setOf(AccountGroup.ALL_ACCOUNTS_ID)), assets, groups, rates, holdings),
        )
    }

    @Test
    fun `closed and unlinked goals track nothing`() {
        assertNull(trackedBalance(goal(), assets, groups, rates, holdings))
        val closed = goal(linkedHoldingIds = setOf("bond"), lifecycle = SavingsGoalLifecycle.CLOSED)
        assertNull(trackedBalance(closed, assets, groups, rates, holdings))
    }

    @Test
    fun `goals that follow the same account directly or through a group are reported together`() {
        val direct = goal(id = "direct", linkedAssetIds = setOf("a2"))
        val viaGroup = goal(id = "via-group", linkedGroupIds = setOf("g2"))
        val unrelated = goal(id = "unrelated", linkedHoldingIds = setOf("bond"))

        val sharing = goalsSharingLinkedBalance(listOf(direct, viaGroup, unrelated), groups)

        assertEquals(
            listOf(listOf("a2") to listOf("direct", "via-group")),
            sharing.map { it.targetIds to it.goals.map(SavingsGoal::id) },
        )
    }

    @Test
    fun `goals that follow the same product directly or through a group are reported together`() {
        val direct = goal(id = "direct", linkedHoldingIds = setOf("bond"))
        val viaGroup = goal(id = "via-group", linkedGroupIds = setOf("g1"))

        val sharing = goalsSharingLinkedBalance(listOf(direct, viaGroup), groups)

        assertEquals(
            listOf(listOf("bond") to listOf("direct", "via-group")),
            sharing.map { it.targetIds to it.goals.map(SavingsGoal::id) },
        )
    }

    @Test
    fun `goals that follow the same group are reported with the group`() {
        val first = goal(id = "first", linkedGroupIds = setOf("g1"))
        val second = goal(id = "second", linkedGroupIds = setOf("g1", "g2"))

        val sharing = goalsSharingLinkedBalance(listOf(first, second), groups)

        // g1 is followed by both; a1 is also in g2 but both goals reach it through g1 already.
        assertEquals(
            listOf(listOf("g1") to listOf("first", "second")),
            sharing.map { it.targetIds to it.goals.map(SavingsGoal::id) },
        )
    }

    @Test
    fun `goals whose groups overlap in a member name that member`() {
        val first = goal(id = "first", linkedGroupIds = setOf("g1"))
        val second = goal(id = "second", linkedGroupIds = setOf("g2"))

        val sharing = goalsSharingLinkedBalance(listOf(first, second), groups)

        assertEquals(
            listOf(listOf("a1") to listOf("first", "second")),
            sharing.map { it.targetIds to it.goals.map(SavingsGoal::id) },
        )
    }

    @Test
    fun `closed goals disjoint goals and groups without members share nothing`() {
        val closed = goal(id = "closed", linkedAssetIds = setOf("a1"), lifecycle = SavingsGoalLifecycle.CLOSED)
        val open = goal(id = "open", linkedAssetIds = setOf("a1"))
        val other = goal(id = "other", linkedAssetIds = setOf("a2"))
        val emptyGroup = AccountGroup("empty", "Vacío", true, 3, emptySet(), emptySet())
        val onEmpty1 = goal(id = "e1", linkedGroupIds = setOf("empty"))
        val onEmpty2 = goal(id = "e2", linkedGroupIds = setOf("empty"))

        assertEquals(
            emptyList(),
            goalsSharingLinkedBalance(listOf(closed, open, other, onEmpty1, onEmpty2), groups + emptyGroup),
        )
    }

    @Test
    fun `accounts count goals that follow them directly or through a linked group`() {
        val goals = listOf(
            goal(id = "direct", linkedAssetIds = setOf("a1")),
            goal(id = "both", linkedAssetIds = setOf("a1"), linkedGroupIds = setOf("g1")),
            goal(id = "via-group", linkedGroupIds = setOf("g2")),
            goal(id = "closed", linkedGroupIds = setOf("g2"), lifecycle = SavingsGoalLifecycle.CLOSED),
            goal(id = "builtin-link", linkedGroupIds = setOf(AccountGroup.ALL_ACCOUNTS_ID)),
        )

        val usage = accountUsage(goals, groups)

        assertEquals(listOf("direct", "both", "via-group"), usage.getValue("a1").goalNames)
        assertEquals(listOf("via-group"), usage.getValue("a2").goalNames)
    }

    @Test
    fun `account usage leaves out the goal being edited and the goals that follow the group being edited`() {
        val goals = listOf(
            goal(id = "direct", linkedAssetIds = setOf("a2")),
            goal(id = "via-g2", linkedGroupIds = setOf("g2")),
        )

        assertEquals(listOf("via-g2"), accountUsage(goals, groups, excludeGoalId = "direct").getValue("a2").goalNames)
        assertEquals(listOf("direct"), accountUsage(goals, groups, excludeGroupId = "g2").getValue("a2").goalNames)
    }

    @Test
    fun `products count goals that follow them directly or through a linked group`() {
        val goals = listOf(
            goal(id = "direct", linkedHoldingIds = setOf("bond")),
            goal(id = "both", linkedHoldingIds = setOf("bond"), linkedGroupIds = setOf("g1")),
            goal(id = "via-group", linkedGroupIds = setOf("g1")),
            goal(id = "only-direct", linkedHoldingIds = setOf("lonely")),
            goal(id = "closed", linkedHoldingIds = setOf("bond"), lifecycle = SavingsGoalLifecycle.CLOSED),
        )

        val usage = holdingUsage(goals, groups)

        assertEquals(HoldingUsage(listOf("direct", "both", "via-group"), listOf("Casa")), usage["bond"])
        assertEquals(HoldingUsage(listOf("both", "via-group"), listOf("Casa")), usage["money-market"])
        // A product in no group still shows the goal that follows it directly.
        assertEquals(HoldingUsage(listOf("only-direct"), emptyList()), usage["lonely"])
    }

    @Test
    fun `product usage leaves out the goal being edited and the goals that follow the group being edited`() {
        val goals = listOf(
            goal(id = "direct", linkedHoldingIds = setOf("bond")),
            goal(id = "via-g1", linkedGroupIds = setOf("g1")),
        )

        assertEquals(listOf("via-g1"), holdingUsage(goals, groups, excludeGoalId = "direct").getValue("bond").goalNames)
        assertEquals(listOf("direct"), holdingUsage(goals, groups, excludeGroupId = "g1").getValue("bond").goalNames)
    }

    @Test
    fun `groups list the other open goals that follow them`() {
        val goals = listOf(
            goal(id = "first", linkedGroupIds = setOf("g1", "g2")),
            goal(id = "second", linkedGroupIds = setOf("g2")),
            goal(id = "closed", linkedGroupIds = setOf("g1"), lifecycle = SavingsGoalLifecycle.CLOSED),
            goal(id = "account", linkedAssetIds = setOf("a1")),
        )

        assertEquals(mapOf("g1" to listOf("first"), "g2" to listOf("first", "second")), goalNamesByLinkedGroup(goals))
        assertEquals(mapOf("g2" to listOf("second")), goalNamesByLinkedGroup(goals, excludeGoalId = "first"))
    }

    private fun asset(id: String, minor: Long, currency: Currency = Currency.EUR) =
        Asset(id, Asset.AssetGroup.BANK, id, null, CurrencyAmount(Money(minor), currency))

    private fun goal(
        id: String = "goal",
        currency: Currency = Currency.EUR,
        linkedAssetIds: Set<String> = emptySet(),
        linkedHoldingIds: Set<String> = emptySet(),
        linkedGroupIds: Set<String> = emptySet(),
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = id,
        target = CurrencyAmount(Money(1_000_000), currency),
        targetDate = null,
        linkedAssetIds = linkedAssetIds,
        lifecycle = lifecycle,
        progress = Money.ZERO,
        linkedHoldingIds = linkedHoldingIds,
        linkedGroupIds = linkedGroupIds,
    )
}
