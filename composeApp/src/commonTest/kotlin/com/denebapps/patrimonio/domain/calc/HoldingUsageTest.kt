package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

class HoldingUsageTest {
    private val groups = listOf(
        AccountGroup.allAccounts(),
        AccountGroup("g1", "Casa", true, 1, memberAssetIds = setOf("a1"), memberHoldingIds = setOf("h1", "h2")),
        AccountGroup("g2", "Jubilación", true, 2, memberAssetIds = emptySet(), memberHoldingIds = setOf("h1")),
        AccountGroup("g3", "Sin metas", true, 3, memberAssetIds = setOf("a1"), memberHoldingIds = setOf("h3")),
    )

    @Test
    fun `a holding lists the groups that hold it and the open goals that follow those groups`() {
        val goals = listOf(
            goal("reforma", linkedGroupId = "g1"),
            goal("retiro", linkedGroupId = "g2"),
            goal("entrada", linkedGroupId = "g1"),
            goal("cerrada", linkedGroupId = "g1", lifecycle = SavingsGoalLifecycle.CLOSED),
            goal("cuenta", linkedAssetIds = setOf("a1")),
        )

        val usage = holdingUsage(goals, groups)

        assertEquals(HoldingUsage(listOf("reforma", "retiro", "entrada"), listOf("Casa", "Jubilación")), usage["h1"])
        assertEquals(HoldingUsage(listOf("reforma", "entrada"), listOf("Casa")), usage["h2"])
        assertEquals(HoldingUsage(emptyList(), listOf("Sin metas")), usage["h3"])
        assertEquals(setOf("h1", "h2", "h3"), usage.keys)
    }

    @Test
    fun `a goal that follows several groups of a holding is listed once`() {
        val goals = listOf(goal("reforma", linkedGroupId = "g1"))
        val overlapping = groups + AccountGroup("g4", "Copia", true, 4, memberHoldingIds = setOf("h1"))

        assertEquals(listOf("reforma"), holdingUsage(goals, overlapping).getValue("h1").goalNames)
    }

    @Test
    fun `the builtin group is never counted`() {
        val builtinWithHolding = AccountGroup.allAccounts().copy(memberHoldingIds = setOf("h9"))

        assertEquals(
            emptyMap(),
            holdingUsage(
                listOf(goal("todo", linkedGroupId = AccountGroup.ALL_ACCOUNTS_ID)),
                listOf(builtinWithHolding),
            ),
        )
    }

    @Test
    fun `the goal or group being edited is left out`() {
        val goals = listOf(goal("reforma", linkedGroupId = "g1"), goal("retiro", linkedGroupId = "g2"))

        assertEquals(listOf("retiro"), holdingUsage(goals, groups, excludeGoalId = "reforma").getValue("h1").goalNames)
        val withoutCasa = holdingUsage(goals, groups, excludeGroupId = "g1")
        assertEquals(HoldingUsage(listOf("retiro"), listOf("Jubilación")), withoutCasa["h1"])
        assertEquals(null, withoutCasa["h2"])
    }

    @Test
    fun `no groups or no holdings in groups give no usage`() {
        assertEquals(emptyMap(), holdingUsage(emptyList(), emptyList()))
        assertEquals(
            emptyMap(),
            holdingUsage(
                listOf(goal("viaje", linkedAssetIds = setOf("a1"))),
                listOf(groups[3].copy(memberHoldingIds = emptySet())),
            ),
        )
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
        linkedGroupIds = setOfNotNull(linkedGroupId),
    )
}
