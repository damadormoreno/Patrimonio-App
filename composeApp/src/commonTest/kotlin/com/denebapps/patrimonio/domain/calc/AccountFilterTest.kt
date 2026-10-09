package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountFilterTest {
    private val bank = asset("bank", Asset.AssetGroup.BANK)
    private val broker = asset("broker", Asset.AssetGroup.INVEST)
    private val usage = mapOf(
        "bank" to AccountUsage(goalNames = listOf("Viaje")),
        "broker" to AccountUsage(groupNames = listOf("Ahorro")),
    )

    @Test
    fun `assignment keeps the accounts in goals in groups or in neither`() {
        val free = asset("free", Asset.AssetGroup.CASH)
        val all = listOf(bank, broker, free)

        fun kept(assignment: AccountAssignment) =
            all.filter { AccountFilter(assignment).matches(it, usage[it.id]) }.map { it.id }

        assertEquals(listOf("bank", "broker", "free"), kept(AccountAssignment.ALL))
        assertEquals(listOf("bank"), kept(AccountAssignment.IN_GOALS))
        assertEquals(listOf("broker"), kept(AccountAssignment.IN_GROUPS))
        assertEquals(listOf("free"), kept(AccountAssignment.UNASSIGNED))
    }

    @Test
    fun `types and assignment combine`() {
        val investInGroups = AccountFilter(AccountAssignment.IN_GROUPS, setOf(Asset.AssetGroup.INVEST))
        val investInGoals = AccountFilter(AccountAssignment.IN_GOALS, setOf(Asset.AssetGroup.INVEST))

        assertTrue(investInGroups.matches(broker, usage["broker"]))
        assertFalse(investInGroups.matches(bank, usage["bank"]))
        assertFalse(investInGoals.matches(broker, usage["broker"]))
    }

    @Test
    fun `only a non default choice makes the filter active`() {
        assertFalse(AccountFilter().isActive)
        assertTrue(AccountFilter(AccountAssignment.UNASSIGNED).isActive)
        assertTrue(AccountFilter(types = setOf(Asset.AssetGroup.CASH)).isActive)
    }

    private fun asset(id: String, group: String) = Asset(id, group, id, null, CurrencyAmount(Money(100), Currency.EUR))
}
