package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money

/** Resolves [group]'s membership: every asset when [AccountGroup.memberAssetIds] is null (the
 *  builtin "all accounts" group), otherwise only the assets whose id is in the member set
 *  (overlapping membership is allowed — an asset may belong to multiple groups). */
fun groupMembers(group: AccountGroup, assets: List<Asset>): List<Asset> {
    val memberIds = group.memberAssetIds ?: return assets
    return assets.filter { it.id in memberIds }
}

/** Sum, in EUR, of [group]'s resolved members. */
fun groupTotal(group: AccountGroup, assets: List<Asset>, rates: FxRates): Money =
    groupMembers(group, assets).fold(Money.ZERO) { acc, a -> acc + a.amount.toEur(rates) }
