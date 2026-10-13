package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HoldingUsage
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.testing.FakeAccountGroupRepository
import com.denebapps.patrimonio.testing.FakeSavingsGoalRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoldingUsageCopyTest {
    @Test
    fun `the usage line names the groups and the goals that follow them`() {
        assertEquals(
            "En el grupo Casa · Cuenta para la meta Casa",
            holdingUsageLine(HoldingUsage(goalNames = listOf("Casa"), groupNames = listOf("Casa"))),
        )
    }

    @Test
    fun `the usage line is plural for several groups and goals`() {
        assertEquals(
            "En los grupos Casa y Coche · Cuenta para las metas Entrada, Reforma y Viaje",
            holdingUsageLine(
                HoldingUsage(goalNames = listOf("Entrada", "Reforma", "Viaje"), groupNames = listOf("Casa", "Coche")),
            ),
        )
    }

    @Test
    fun `a holding in a group no goal follows has only the group half`() {
        assertEquals("En el grupo Ahorro", holdingUsageLine(HoldingUsage(groupNames = listOf("Ahorro"))))
    }

    @Test
    fun `a holding that is used nowhere has no usage line`() {
        assertNull(holdingUsageLine(HoldingUsage()))
        assertNull(holdingUsageLine(null))
    }

    @Test
    fun `the delete warning names the groups and the goals and says it will be removed`() {
        assertEquals(
            "Está en la meta «Entrada» y en el grupo «Casa»; se quitará de ellos.",
            holdingDeleteWarning(HoldingUsage(goalNames = listOf("Entrada"), groupNames = listOf("Casa"))),
        )
        assertEquals(
            "Está en los grupos «Casa» y «Ahorro»; se quitará de ellos.",
            holdingDeleteWarning(HoldingUsage(groupNames = listOf("Casa", "Ahorro"))),
        )
        assertEquals(
            "Está en el grupo «Ahorro»; se quitará de él.",
            holdingDeleteWarning(HoldingUsage(groupNames = listOf("Ahorro"))),
        )
    }

    @Test
    fun `the delete warning is absent for a holding that is used nowhere`() {
        assertNull(holdingDeleteWarning(HoldingUsage()))
        assertNull(holdingDeleteWarning(null))
    }

    @Test
    fun `usage is observed from the groups and the open goals that follow them`() = runTest {
        val group = AccountGroup("g1", "Casa", true, 1, memberAssetIds = emptySet(), memberHoldingIds = setOf("h1"))
        val goal = SavingsGoal(
            id = "goal-1",
            name = "Entrada",
            target = CurrencyAmount(Money(100_000), Currency.EUR),
            targetDate = null,
            linkedAssetIds = emptySet(),
            linkedGroupIds = setOf("g1"),
            lifecycle = SavingsGoalLifecycle.OPEN,
            progress = Money.ZERO,
        )

        val usage = observeHoldingUsage(
            FakeAccountGroupRepository(listOf(AccountGroup.allAccounts(), group)),
            FakeSavingsGoalRepository(listOf(goal)),
        ).first()

        assertEquals(mapOf("h1" to HoldingUsage(goalNames = listOf("Entrada"), groupNames = listOf("Casa"))), usage)
    }
}
