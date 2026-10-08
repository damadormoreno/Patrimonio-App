package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountGroupTest {
    private val rates = FxRates(emptyMap())

    private fun asset(id: String, group: String, minorUnits: Long) =
        Asset(id, group, "asset-$id", null, CurrencyAmount(Money(minorUnits), Currency.EUR))

    @Test
    fun `asset counted in multiple group totals`() {
        val assets = listOf(
            asset("a1", Asset.AssetGroup.BANK, 10000),
            asset("a2", Asset.AssetGroup.INVEST, 5000),
        )
        val groupOne =
            AccountGroup("g1", "Cuenta conjunta", showBalance = true, sortOrder = 0, memberAssetIds = setOf("a1"))
        val groupTwo =
            AccountGroup("g2", "Ahorro compartido", showBalance = true, sortOrder = 1, memberAssetIds = setOf("a1"))

        assertEquals(Money(10000), groupTotal(groupOne, assets, rates))
        assertEquals(Money(10000), groupTotal(groupTwo, assets, rates))
    }

    @Test
    fun `builtin group includes every asset`() {
        val assets = listOf(
            asset("a1", Asset.AssetGroup.BANK, 10000),
            asset("a2", Asset.AssetGroup.INVEST, 5000),
        )
        assertEquals(assets, groupMembers(AccountGroup.allAccounts(), assets))
    }

    @Test
    fun `groups ordered by total descending`() {
        // 500, 20000, 3000 EUR respectively.
        val assets = listOf(
            asset("a1", Asset.AssetGroup.BANK, 50000),
            asset("a2", Asset.AssetGroup.INVEST, 2000000),
            asset("a3", Asset.AssetGroup.REALESTATE, 300000),
        )
        val result = assetsByGroup(assets, rates)
        assertEquals(
            listOf(Asset.AssetGroup.INVEST, Asset.AssetGroup.REALESTATE, Asset.AssetGroup.BANK),
            result.map { it.group },
        )
        assertEquals(listOf(Money(2000000), Money(300000), Money(50000)), result.map { it.total })
    }
}
