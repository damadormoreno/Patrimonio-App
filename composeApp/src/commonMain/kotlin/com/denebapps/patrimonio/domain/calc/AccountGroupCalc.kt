package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding

/** Resolves [group]'s membership: every asset when [AccountGroup.memberAssetIds] is null (the
 *  builtin "all accounts" group), otherwise only the assets whose id is in the member set
 *  (overlapping membership is allowed — an asset may belong to multiple groups). */
fun groupMembers(group: AccountGroup, assets: List<Asset>): List<Asset> {
    val memberIds = group.memberAssetIds ?: return assets
    return assets.filter { it.id in memberIds }
}

/** The portfolio holdings in [group] ([AccountGroup.memberHoldingIds]) that exist in [holdings]. The builtin "all
 *  accounts" group (null [AccountGroup.memberAssetIds]) stays accounts only: it never has holdings. */
fun groupHoldings(group: AccountGroup, holdings: List<PortfolioHolding>): List<PortfolioHolding> {
    if (group.memberAssetIds == null) return emptyList()
    return holdings.filter { it.id in group.memberHoldingIds }
}

/**
 * Sum, in EUR, of [group]'s resolved members: its accounts plus its [holdings] valued at [rates]. A holding with
 * no price yet is worth nothing yet and adds zero.
 */
fun groupTotal(
    group: AccountGroup,
    assets: List<Asset>,
    rates: FxRates,
    holdings: List<PortfolioHolding> = emptyList(),
): Money {
    val accounts = groupMembers(group, assets).fold(Money.ZERO) { acc, a -> acc + a.amount.toEur(rates) }
    return groupHoldings(group, holdings).fold(accounts) { acc, h -> acc + (h.value()?.toEur(rates) ?: Money.ZERO) }
}
