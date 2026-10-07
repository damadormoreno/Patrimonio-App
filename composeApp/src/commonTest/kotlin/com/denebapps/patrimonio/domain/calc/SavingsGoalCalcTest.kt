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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val goal = goal(id = "goal-1", targetMinor = 100, progressMinor = 100)

        assertTrue(goal.targetReached)
        assertEquals(SavingsGoalLifecycle.OPEN, goal.lifecycle)
    }

    @Test
    fun `target reached remains false below target regardless of lifecycle`() {
        val goal = goal(id = "goal-1", targetMinor = 100, progressMinor = 99, lifecycle = SavingsGoalLifecycle.CLOSED)

        assertFalse(goal.targetReached)
        assertEquals(SavingsGoalLifecycle.CLOSED, goal.lifecycle)
    }

    @Test
    fun `empty cancellation emits no zero delta while funded cancellation releases exact progress`() {
        assertNull(savingsGoalCancellationDelta(Money.ZERO))
        assertEquals(Money(-75), savingsGoalCancellationDelta(Money(75)))
    }

    @Test
    fun `a goal cannot be linked to an asset and a group at once`() {
        assertFailsWith<IllegalArgumentException> {
            goal(
                id = "goal-1",
                targetMinor = 100,
                progressMinor = 0,
                linkedAssetId = "asset-1",
                linkedGroupId = "group-1",
            )
        }
    }

    @Test
    fun `an open goal linked to an asset tracks its balance whatever it allocated`() {
        val goal = goal(id = "goal-1", targetMinor = 500, progressMinor = 0, linkedAssetId = "a1")

        assertTrue(goal.tracksLinkedBalance)
        assertEquals(Money(420), trackedBalance(goal, listOf(asset("a1", 420), asset("a2", 900)), emptyList(), null))
    }

    @Test
    fun `a group link tracks the members converted to the goal currency`() {
        val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 1 USD = 0.5 EUR
        val assets = listOf(asset("a1", 100), asset("a2", 400, Currency.USD), asset("outside", 9_999))
        val goal = goal(id = "goal-1", targetMinor = 500, progressMinor = 0, linkedGroupId = "g1")

        assertEquals(Money(300), trackedBalance(goal, assets, listOf(group("g1", "a1", "a2")), rates))
    }

    @Test
    fun `same-currency groups need no rates while mixed ones without rates are unavailable`() {
        val goal = goal(id = "goal-1", targetMinor = 500, progressMinor = 0, linkedGroupId = "g1")
        val sameCurrency = listOf(asset("a1", 100), asset("a2", 50))
        val mixed = listOf(asset("a1", 100), asset("a2", 400, Currency.USD))
        val groups = listOf(group("g1", "a1", "a2"))

        assertEquals(Money(150), trackedBalance(goal, sameCurrency, groups, rates = null))
        assertNull(trackedBalance(goal, mixed, groups, rates = null))
        assertNull(trackedBalance(goal, mixed, groups, FxRates(mapOf(Currency.USD to 0L))))
    }

    @Test
    fun `unlinked closed and cancelled goals or missing targets track nothing`() {
        val assets = listOf(asset("a1", 100))
        val groups = listOf(group("g1", "a1"), AccountGroup.allAccounts())

        listOf(
            goal(id = "unlinked", targetMinor = 100, progressMinor = 10),
            goal("closed", 100, 10, linkedAssetId = "a1", lifecycle = SavingsGoalLifecycle.CLOSED),
            goal("cancelled", 100, 0, linkedGroupId = "g1", lifecycle = SavingsGoalLifecycle.CANCELLED),
            goal(id = "missing-asset", targetMinor = 100, progressMinor = 0, linkedAssetId = "deleted"),
            goal(id = "missing-group", targetMinor = 100, progressMinor = 0, linkedGroupId = "deleted"),
            goal("builtin", 100, 0, linkedGroupId = AccountGroup.ALL_ACCOUNTS_ID),
        ).forEach { goal ->
            assertNull(trackedBalance(goal, assets, groups, null), goal.id)
            if (goal.id != "missing-asset" && goal.id != "missing-group" && goal.id != "builtin") {
                assertFalse(goal.tracksLinkedBalance, goal.id)
            }
        }
    }

    @Test
    fun `an empty group tracks a zero balance`() {
        val goal = goal(id = "goal-1", targetMinor = 500, progressMinor = 0, linkedGroupId = "g1")

        assertEquals(Money.ZERO, trackedBalance(goal, listOf(asset("a1", 100)), listOf(group("g1")), null))
    }

    @Test
    fun `goals tracking the same asset or group are reported together`() {
        val sharedAsset1 = goal(id = "s1", targetMinor = 100, progressMinor = 0, linkedAssetId = "a1")
        val sharedAsset2 = goal(id = "s2", targetMinor = 100, progressMinor = 0, linkedAssetId = "a1")
        val sharedGroup1 = goal(id = "g1-1", targetMinor = 100, progressMinor = 0, linkedGroupId = "g1")
        val sharedGroup2 = goal(id = "g1-2", targetMinor = 100, progressMinor = 0, linkedGroupId = "g1")
        val alone = goal(id = "alone", targetMinor = 100, progressMinor = 0, linkedAssetId = "a2")
        val cancelledOnA1 = goal("c", 100, 0, linkedAssetId = "a1", lifecycle = SavingsGoalLifecycle.CANCELLED)
        val unlinked = goal(id = "u", targetMinor = 100, progressMinor = 0)

        val sharing = goalsSharingLinkedBalance(
            listOf(sharedAsset1, alone, sharedGroup1, cancelledOnA1, sharedAsset2, unlinked, sharedGroup2),
        )

        assertEquals(
            setOf(listOf("s1", "s2"), listOf("g1-1", "g1-2")),
            sharing.map { goals -> goals.map { it.id } }.toSet(),
        )
    }

    private fun allocation(id: Long, deltaMinor: Long) = SavingsGoalAllocationEvent(
        id = id,
        goalId = "goal-1",
        delta = Money(deltaMinor),
        timestampEpochMs = id * 1_000,
    )

    private fun goal(
        id: String,
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
