package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountUsageTest {
    private val groups = listOf(
        AccountGroup.allAccounts(),
        AccountGroup("g1", "Ahorro", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1", "a2")),
        AccountGroup("g2", "Día a día", showBalance = true, sortOrder = 2, memberAssetIds = setOf("a1")),
    )

    @Test
    fun `accounts list the open goals that follow them and the groups that hold them`() {
        val goals = listOf(
            goal("viaje", linkedAssetIds = setOf("a1", "a3")),
            goal("coche", linkedAssetIds = setOf("a1")),
            goal("cerrada", linkedAssetIds = setOf("a2"), lifecycle = SavingsGoalLifecycle.CANCELLED),
            goal("colchon", linkedGroupId = "g1"),
        )

        val usage = accountUsage(goals, groups)

        assertEquals(AccountUsage(listOf("viaje", "coche"), listOf("Ahorro", "Día a día")), usage["a1"])
        // Followed by a cancelled goal and, through its group, by "colchon": neither counts as a goal.
        assertEquals(AccountUsage(groupNames = listOf("Ahorro")), usage["a2"])
        assertEquals(AccountUsage(goalNames = listOf("viaje")), usage["a3"])
        assertEquals(setOf("a1", "a2", "a3"), usage.keys)
    }

    @Test
    fun `the goal or group being edited is left out`() {
        val goals = listOf(goal("viaje", linkedAssetIds = setOf("a1")), goal("coche", linkedAssetIds = setOf("a1")))

        assertEquals(listOf("coche"), accountUsage(goals, groups, excludeGoalId = "viaje").getValue("a1").goalNames)
        val withoutAhorro = accountUsage(goals, groups, excludeGroupId = "g1")
        assertEquals(listOf("Día a día"), withoutAhorro.getValue("a1").groupNames)
        assertEquals(emptyMap(), accountUsage(emptyList(), listOf(AccountGroup.allAccounts())))
    }

    @Test
    fun `groups list the other open goals that follow them`() {
        val goals = listOf(
            goal("colchon", linkedGroupId = "g1"),
            goal("vacaciones", linkedGroupId = "g1"),
            goal("vieja", linkedGroupId = "g2", lifecycle = SavingsGoalLifecycle.CLOSED),
            goal("viaje", linkedAssetIds = setOf("a1")),
        )

        assertEquals(mapOf("g1" to listOf("colchon", "vacaciones")), goalNamesByLinkedGroup(goals))
        assertEquals(mapOf("g1" to listOf("vacaciones")), goalNamesByLinkedGroup(goals, excludeGoalId = "colchon"))
    }

    private fun goal(
        name: String,
        linkedAssetIds: Set<String> = emptySet(),
        linkedGroupId: String? = null,
        lifecycle: SavingsGoalLifecycle = SavingsGoalLifecycle.OPEN,
    ) = SavingsGoal(
        id = name,
        name = name,
        target = CurrencyAmount(Money(100_000), Currency.EUR),
        targetDate = null,
        linkedAssetIds = linkedAssetIds,
        lifecycle = lifecycle,
        progress = Money.ZERO,
        linkedGroupId = linkedGroupId,
    )
}
