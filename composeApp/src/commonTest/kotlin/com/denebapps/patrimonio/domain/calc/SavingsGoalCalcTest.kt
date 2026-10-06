package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
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
        linkedAssetId: String? = null,
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = id,
        name = "Goal $id",
        target = CurrencyAmount(Money(targetMinor), Currency.EUR),
        targetDate = null,
        linkedAssetId = linkedAssetId,
        lifecycle = lifecycle,
        progress = Money(progressMinor),
    )

    private fun asset(balanceMinor: Long) = Asset(
        id = "asset-1",
        group = Asset.AssetGroup.BANK,
        name = "Savings",
        subtitle = null,
        amount = CurrencyAmount(Money(balanceMinor), Currency.EUR),
    )
}
