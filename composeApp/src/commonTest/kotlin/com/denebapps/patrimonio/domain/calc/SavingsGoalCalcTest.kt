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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavingsGoalCalcTest {
    @Test
    fun `checked progress permits overfunding and later withdrawal`() {
        val events =
            listOf(
                allocation(id = 1, deltaMinor = 80),
                allocation(id = 2, deltaMinor = 30),
                allocation(id = 3, deltaMinor = -10),
            )

        assertEquals(Money(100), checkedSavingsGoalProgress(events))
        assertEquals(listOf(80L, 30L, -10L), events.map { it.delta.minorUnits })
    }

    @Test
    fun `checked progress rejects a zero allocation event`() {
        assertFailsWith<InvalidSavingsGoalDeltaException> {
            checkedSavingsGoalProgress(listOf(allocation(id = 1, deltaMinor = 0)))
        }
    }

    @Test
    fun `checked progress rejects a withdrawal below zero`() {
        assertFailsWith<NegativeSavingsGoalProgressException> {
            checkedSavingsGoalProgress(
                listOf(
                    allocation(id = 1, deltaMinor = 10),
                    allocation(id = 2, deltaMinor = -11),
                ),
            )
        }
    }

    @Test
    fun `checked progress rejects positive overflow`() {
        assertFailsWith<SavingsGoalArithmeticOverflowException> {
            checkedSavingsGoalProgress(
                listOf(
                    allocation(id = 1, deltaMinor = Long.MAX_VALUE),
                    allocation(id = 2, deltaMinor = 1),
                ),
            )
        }
    }

    @Test
    fun `checked arithmetic rejects subtraction and negation overflow`() {
        assertFailsWith<SavingsGoalArithmeticOverflowException> {
            checkedSavingsGoalSubtract(Money(Long.MIN_VALUE), Money(1))
        }
        assertFailsWith<SavingsGoalArithmeticOverflowException> {
            checkedSavingsGoalNegate(Money(Long.MIN_VALUE))
        }
    }

    @Test
    fun `target reached is derived without changing open lifecycle`() {
        val goal = goal(id = 1, targetMinor = 100, progressMinor = 100)

        assertTrue(goal.targetReached)
        assertEquals(SavingsGoalLifecycle.OPEN, goal.lifecycle)
    }

    @Test
    fun `target reached remains false below target regardless of lifecycle`() {
        val goal = goal(id = 1, targetMinor = 100, progressMinor = 99, lifecycle = SavingsGoalLifecycle.CLOSED)

        assertFalse(goal.targetReached)
        assertEquals(SavingsGoalLifecycle.CLOSED, goal.lifecycle)
    }

    @Test
    fun `shared asset coverage includes open and closed preserved progress`() {
        val asset = asset(balanceMinor = 100)
        val openGoal = goal(id = 1, targetMinor = 100, progressMinor = 70, linkedAssetId = asset.id)
        val closedGoal =
            goal(
                id = 2,
                targetMinor = 50,
                progressMinor = 50,
                linkedAssetId = asset.id,
                lifecycle = SavingsGoalLifecycle.CLOSED,
            )

        val coverage =
            assertIs<SavingsGoalCoverage.SharedAsset>(
                savingsGoalCoverage(openGoal, listOf(asset), listOf(openGoal, closedGoal)),
            )

        assertEquals(CurrencyAmount(Money(120), Currency.EUR), coverage.reserved)
        assertEquals(CurrencyAmount(Money(100), Currency.EUR), coverage.balance)
        assertTrue(coverage.undercovered)
    }

    @Test
    fun `cancelled goals contribute zero to shared coverage`() {
        val asset = asset(balanceMinor = 100)
        val openGoal = goal(id = 1, targetMinor = 100, progressMinor = 40, linkedAssetId = asset.id)
        val cancelledGoal =
            goal(
                id = 2,
                targetMinor = 100,
                progressMinor = 60,
                linkedAssetId = asset.id,
                lifecycle = SavingsGoalLifecycle.CANCELLED,
            )

        val coverage =
            assertIs<SavingsGoalCoverage.SharedAsset>(
                savingsGoalCoverage(openGoal, listOf(asset), listOf(openGoal, cancelledGoal)),
            )

        assertEquals(CurrencyAmount(Money(40), Currency.EUR), coverage.reserved)
        assertFalse(coverage.undercovered)
    }

    @Test
    fun `empty cancellation emits no zero delta while funded cancellation releases exact progress`() {
        assertNull(savingsGoalCancellationDelta(Money.ZERO))
        assertEquals(Money(-75), savingsGoalCancellationDelta(Money(75)))
    }

    @Test
    fun `unlinked goal coverage is unavailable`() {
        val unlinkedGoal = goal(id = 1, targetMinor = 100, progressMinor = 70)

        assertEquals(
            SavingsGoalCoverage.Unavailable,
            savingsGoalCoverage(unlinkedGoal, listOf(asset(balanceMinor = 100)), listOf(unlinkedGoal)),
        )
    }

    @Test
    fun `missing linked asset coverage is unavailable`() {
        val goal = goal(id = 1, targetMinor = 100, progressMinor = 70, linkedAssetId = "deleted")

        assertEquals(SavingsGoalCoverage.Unavailable, savingsGoalCoverage(goal, emptyList(), listOf(goal)))
    }

    @Test
    fun `shared coverage counts each goal id once without per-goal priority`() {
        val asset = asset(balanceMinor = 100)
        val first = goal(id = 1, targetMinor = 100, progressMinor = 70, linkedAssetId = asset.id)
        val second = goal(id = 2, targetMinor = 100, progressMinor = 20, linkedAssetId = asset.id)
        val multipliedRelationRows = listOf(first, first, second, second)

        val firstCoverage =
            assertIs<SavingsGoalCoverage.SharedAsset>(
                savingsGoalCoverage(first, listOf(asset), multipliedRelationRows),
            )
        val secondCoverage =
            assertIs<SavingsGoalCoverage.SharedAsset>(
                savingsGoalCoverage(second, listOf(asset), multipliedRelationRows),
            )

        assertEquals(CurrencyAmount(Money(90), Currency.EUR), firstCoverage.reserved)
        assertEquals(firstCoverage, secondCoverage)
    }

    @Test
    fun `a goal cannot be linked to an asset and a group at once`() {
        assertFailsWith<IllegalArgumentException> {
            goal(id = 1, targetMinor = 100, progressMinor = 0, linkedAssetId = "asset-1", linkedGroupId = "group-1")
        }
    }

    @Test
    fun `group coverage sums member balances and reservations in the goal currency`() {
        val members = listOf(asset("a1", 60), asset("a2", 40))
        val group = group("g1", "a1", "a2")
        val first = goal(id = 1, targetMinor = 100, progressMinor = 70, linkedGroupId = "g1")
        val second = goal(id = 2, targetMinor = 100, progressMinor = 50, linkedGroupId = "g1")

        val coverage = assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(first, members, listOf(first, second), listOf(group)),
        )

        assertEquals("g1", coverage.groupId)
        assertEquals(CurrencyAmount(Money(100), Currency.EUR), coverage.balance)
        assertEquals(CurrencyAmount(Money(120), Currency.EUR), coverage.reserved)
        assertTrue(coverage.undercovered)
    }

    @Test
    fun `group coverage ignores assets outside the group and cancelled goals but keeps closed ones`() {
        val assets = listOf(asset("a1", 100), asset("outside", 9_999))
        val group = group("g1", "a1")
        val open = goal(id = 1, targetMinor = 100, progressMinor = 30, linkedGroupId = "g1")
        val closed = goal(
            id = 2,
            targetMinor = 100,
            progressMinor = 20,
            linkedGroupId = "g1",
            lifecycle = SavingsGoalLifecycle.CLOSED,
        )
        val cancelled = goal(
            id = 3,
            targetMinor = 100,
            progressMinor = 60,
            linkedGroupId = "g1",
            lifecycle = SavingsGoalLifecycle.CANCELLED,
        )
        val onAsset = goal(id = 4, targetMinor = 100, progressMinor = 90, linkedAssetId = "a1")

        val coverage = assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(open, assets, listOf(open, closed, cancelled, onAsset), listOf(group)),
        )

        assertEquals(CurrencyAmount(Money(100), Currency.EUR), coverage.balance)
        assertEquals(CurrencyAmount(Money(50), Currency.EUR), coverage.reserved)
        assertFalse(coverage.undercovered)
    }

    @Test
    fun `group coverage counts each goal id once`() {
        val group = group("g1", "a1")
        val first = goal(id = 1, targetMinor = 100, progressMinor = 70, linkedGroupId = "g1")

        val coverage = assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(first, listOf(asset("a1", 100)), listOf(first, first), listOf(group)),
        )

        assertEquals(CurrencyAmount(Money(70), Currency.EUR), coverage.reserved)
    }

    @Test
    fun `empty group has a zero balance`() {
        val group = group("g1")
        val goal = goal(id = 1, targetMinor = 100, progressMinor = 10, linkedGroupId = "g1")

        val coverage = assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(goal, listOf(asset("a1", 100)), listOf(goal), listOf(group)),
        )

        assertEquals(CurrencyAmount(Money.ZERO, Currency.EUR), coverage.balance)
        assertTrue(coverage.undercovered)
    }

    @Test
    fun `group coverage converts mixed-currency members to the goal currency`() {
        val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 1 USD = 0.5 EUR
        val assets = listOf(asset("a1", 100), asset("a2", 400, Currency.USD)) // 1.00 EUR + 4.00 USD (= 2.00 EUR)
        val group = group("g1", "a1", "a2")
        val goal = goal(id = 1, targetMinor = 500, progressMinor = 250, linkedGroupId = "g1")

        val coverage = assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(goal, assets, listOf(goal), listOf(group), rates),
        )

        assertEquals(CurrencyAmount(Money(300), Currency.EUR), coverage.balance)
        assertEquals(CurrencyAmount(Money(250), Currency.EUR), coverage.reserved)
    }

    @Test
    fun `group coverage converts reservations of goals in other currencies`() {
        val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 1 USD = 0.5 EUR
        val group = group("g1", "a1")
        val eurGoal = goal(id = 1, targetMinor = 500, progressMinor = 100, linkedGroupId = "g1")
        val usdGoal = goal(
            id = 2,
            targetMinor = 500,
            progressMinor = 200,
            currency = Currency.USD,
            linkedGroupId = "g1",
        )

        val coverage = assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(eurGoal, listOf(asset("a1", 1_000)), listOf(eurGoal, usdGoal), listOf(group), rates),
        )

        // 1.00 EUR + 2.00 USD (= 1.00 EUR)
        assertEquals(CurrencyAmount(Money(200), Currency.EUR), coverage.reserved)
    }

    @Test
    fun `group coverage is unavailable when a needed conversion has no usable rates`() {
        val assets = listOf(asset("a1", 100), asset("a2", 400, Currency.USD))
        val group = group("g1", "a1", "a2")
        val goal = goal(id = 1, targetMinor = 500, progressMinor = 250, linkedGroupId = "g1")

        assertEquals(SavingsGoalCoverage.Unavailable, savingsGoalCoverage(goal, assets, listOf(goal), listOf(group)))
        assertEquals(
            SavingsGoalCoverage.Unavailable,
            savingsGoalCoverage(goal, assets, listOf(goal), listOf(group), FxRates(mapOf(Currency.USD to 0L))),
        )
    }

    @Test
    fun `group coverage needs no rates when everything shares the goal currency`() {
        val group = group("g1", "a1")
        val goal = goal(id = 1, targetMinor = 500, progressMinor = 250, linkedGroupId = "g1")

        assertIs<SavingsGoalCoverage.SharedGroup>(
            savingsGoalCoverage(goal, listOf(asset("a1", 100)), listOf(goal), listOf(group), rates = null),
        )
    }

    @Test
    fun `missing or builtin linked group coverage is unavailable`() {
        val missing = goal(id = 1, targetMinor = 100, progressMinor = 10, linkedGroupId = "deleted")
        val builtin = goal(id = 2, targetMinor = 100, progressMinor = 10, linkedGroupId = AccountGroup.ALL_ACCOUNTS_ID)
        val groups = listOf(AccountGroup.allAccounts())

        assertEquals(
            SavingsGoalCoverage.Unavailable,
            savingsGoalCoverage(missing, emptyList(), listOf(missing), groups),
        )
        assertEquals(
            SavingsGoalCoverage.Unavailable,
            savingsGoalCoverage(builtin, emptyList(), listOf(builtin), groups),
        )
    }

    private fun allocation(id: Long, deltaMinor: Long) = SavingsGoalAllocationEvent(
        id = id,
        goalId = 1,
        delta = Money(deltaMinor),
        timestampEpochMs = id * 1_000,
    )

    private fun goal(
        id: Long,
        targetMinor: Long,
        progressMinor: Long,
        currency: Currency = Currency.EUR,
        linkedAssetId: String? = null,
        linkedGroupId: String? = null,
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = "Goal $id",
        target = CurrencyAmount(Money(targetMinor), currency),
        targetDate = null,
        linkedAssetId = linkedAssetId,
        linkedGroupId = linkedGroupId,
        lifecycle = lifecycle,
        progress = Money(progressMinor),
    )

    private fun asset(balanceMinor: Long) = asset("asset-1", balanceMinor)

    private fun asset(id: String, balanceMinor: Long, currency: Currency = Currency.EUR) = Asset(
        id = id,
        group = Asset.AssetGroup.BANK,
        name = "Savings",
        subtitle = null,
        amount = CurrencyAmount(Money(balanceMinor), currency),
    )

    private fun group(id: String, vararg memberIds: String) = AccountGroup(
        id = id,
        name = "Group $id",
        showBalance = true,
        sortOrder = 0,
        memberAssetIds = memberIds.toSet(),
    )
}
