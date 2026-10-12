package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.testing.manualTestHolding
import com.denebapps.patrimonio.testing.quotedTestHolding
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountGroupHoldingsTest {
    private val rates = FxRates(mapOf(Currency.USD to 500_000L)) // 1 USD = 0.5 EUR

    private val account =
        Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(10_000), Currency.EUR))

    private fun group(id: String, assetIds: Set<String>, holdingIds: Set<String>) = AccountGroup(
        id = id,
        name = "Group $id",
        showBalance = true,
        sortOrder = 0,
        memberAssetIds = assetIds,
        memberHoldingIds = holdingIds,
    )

    @Test
    fun `group total adds member holdings in EUR to its accounts`() {
        val holdings = listOf(
            // 50.00 EUR
            quotedTestHolding("fund", units = 10, price = 5),
            // 400.00 USD = 200.00 EUR
            manualTestHolding("art", minor = 40_000, currency = Currency.USD),
            manualTestHolding("outside", minor = 99_999),
        )
        val group = group("g1", setOf("a1"), setOf("fund", "art"))

        assertEquals(Money(10_000 + 5_000 + 20_000), groupTotal(group, listOf(account), rates, holdings))
    }

    @Test
    fun `a group of only holdings totals their values`() {
        val holdings = listOf(manualTestHolding("h1", 7_000), manualTestHolding("h2", 3_000))

        assertEquals(
            Money(10_000),
            groupTotal(group("g1", emptySet(), setOf("h1", "h2")), emptyList(), rates, holdings),
        )
    }

    @Test
    fun `a holding with no price yet counts as zero`() {
        val holdings = listOf(quotedTestHolding("unpriced", units = 10, price = null), manualTestHolding("h2", 3_000))
        val group = group("g1", setOf("a1"), setOf("unpriced", "h2"))

        assertEquals(Money(13_000), groupTotal(group, listOf(account), rates, holdings))
    }

    @Test
    fun `a closed position counts as zero even with a last price`() {
        val holdings = listOf(quotedTestHolding("closed", units = 0, price = 5))
        val group = group("g1", setOf("a1"), setOf("closed"))

        assertEquals(Money(10_000), groupTotal(group, listOf(account), rates, holdings))
    }

    @Test
    fun `a holding in several groups counts in each total`() {
        val holdings = listOf(manualTestHolding("h1", 2_500))
        val one = group("g1", emptySet(), setOf("h1"))
        val two = group("g2", setOf("a1"), setOf("h1"))

        assertEquals(Money(2_500), groupTotal(one, listOf(account), rates, holdings))
        assertEquals(Money(12_500), groupTotal(two, listOf(account), rates, holdings))
    }

    @Test
    fun `holdings that are not members or no longer exist are ignored`() {
        val holdings = listOf(manualTestHolding("h1", 2_500), manualTestHolding("h2", 9_000))
        val group = group("g1", setOf("a1"), setOf("h1", "deleted"))

        assertEquals(listOf("h1"), groupHoldings(group, holdings).map { it.id })
        assertEquals(Money(12_500), groupTotal(group, listOf(account), rates, holdings))
    }

    @Test
    fun `the builtin group stays accounts only`() {
        val holdings = listOf(manualTestHolding("h1", 2_500))

        assertEquals(emptyList(), groupHoldings(AccountGroup.allAccounts(), holdings))
        assertEquals(Money(10_000), groupTotal(AccountGroup.allAccounts(), listOf(account), rates, holdings))
    }

    @Test
    fun `group total without holdings is unchanged`() {
        assertEquals(Money(10_000), groupTotal(group("g1", setOf("a1"), emptySet()), listOf(account), rates))
    }
}
