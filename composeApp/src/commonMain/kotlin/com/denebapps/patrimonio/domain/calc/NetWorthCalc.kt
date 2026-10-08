package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.GroupTotal
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money

/** Net worth = total assets (EUR) minus total liabilities (EUR). */
fun netWorth(assets: List<Asset>, liabs: List<Liability>, rates: FxRates): Money =
    totalAssetsEur(assets, rates) - totalLiabilitiesEur(liabs, rates)

/** [previous] is the most recent snapshot strictly BEFORE the current month (not necessarily
 *  calendar month-1). Null only when no earlier snapshot exists at all. */
fun monthDelta(current: Money, previous: Money?): Money? = previous?.let { current - it }

/** Grouped by type id (built-in or custom), sorted by total descending. */
fun assetsByGroup(assets: List<Asset>, rates: FxRates): List<GroupTotal<String>> = assets.groupBy { it.group }
    .map { (group, items) -> GroupTotal(group, items.fold(Money.ZERO) { acc, a -> acc + a.amount.toEur(rates) }) }
    .sortedByDescending { it.total.minorUnits }

/** Grouped by type id (built-in or custom), sorted by total descending. */
fun liabilitiesByGroup(liabs: List<Liability>, rates: FxRates): List<GroupTotal<String>> = liabs.groupBy { it.group }
    .map { (group, items) -> GroupTotal(group, items.fold(Money.ZERO) { acc, l -> acc + l.amount.toEur(rates) }) }
    .sortedByDescending { it.total.minorUnits }

private fun totalAssetsEur(assets: List<Asset>, rates: FxRates): Money =
    assets.fold(Money.ZERO) { acc, a -> acc + a.amount.toEur(rates) }

private fun totalLiabilitiesEur(liabs: List<Liability>, rates: FxRates): Money =
    liabs.fold(Money.ZERO) { acc, l -> acc + l.amount.toEur(rates) }
