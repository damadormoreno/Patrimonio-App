package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.repository.CarteraFilterSelection

// Filters of the Cartera list: by asset class, by bank or broker and by type of product. Pure functions over the
// holdings in scope, so the ViewModel only wires them and the screen only draws them.

/** What a filter dimension of the Cartera list restricts. */
enum class FilterDimension { ASSET_CLASS, BROKER, TYPE }

/** Key of the holdings that have no effective asset class, next to the `AssetClass` names. */
internal const val UNCLASSIFIED_FILTER_KEY = "UNCLASSIFIED"

/** Key of the holdings without a bank or broker; a real broker is never blank, so it cannot clash. */
internal const val NO_BROKER_FILTER_KEY = ""

private const val NO_BROKER_LABEL = "Sin bróker"

/** One value a dimension can be restricted to: its stable [key], what the user reads and whether it is [selected]. */
data class FilterOptionUi(val key: String, val label: String, val selected: Boolean)

/** A selected value, as the removable chip above the list. */
data class ActiveFilterUi(val dimension: FilterDimension, val key: String, val label: String)

/**
 * The filter of the Cartera list as the screen shows it: the options of each dimension that the holdings in scope
 * offer ([assetClasses], [brokers], [types]) and the [active] chips, in the order class, broker, type.
 */
data class CarteraFilterUi(
    val assetClasses: List<FilterOptionUi> = emptyList(),
    val brokers: List<FilterOptionUi> = emptyList(),
    val types: List<FilterOptionUi> = emptyList(),
    val active: List<ActiveFilterUi> = emptyList(),
) {
    /** Whether there is anything to filter by, which is when the scope has holdings. */
    val hasOptions: Boolean get() = assetClasses.isNotEmpty() || brokers.isNotEmpty() || types.isNotEmpty()

    /** How many values are chosen: the badge of the filter button. */
    val activeCount: Int get() = active.size
}

/** The key of the class of [this] holding: an `AssetClass` name, or [UNCLASSIFIED_FILTER_KEY]. */
internal fun PortfolioHolding.classFilterKey(): String = effectiveAssetClass()?.name ?: UNCLASSIFIED_FILTER_KEY

/** The key of the broker: lower-cased so `MyInvestor` and `myinvestor` are one, [NO_BROKER_FILTER_KEY] without one. */
internal fun PortfolioHolding.brokerFilterKey(): String = broker?.lowercase() ?: NO_BROKER_FILTER_KEY

internal fun PortfolioHolding.typeFilterKey(): String = type.name

/**
 * [this] reduced to what the [holdings] in scope can match: a remembered value that no scope holding has is dropped
 * (not deleted from the preference, only from what applies), so a dimension left without values restricts nothing.
 */
internal fun CarteraFilterSelection.effectiveFor(holdings: List<PortfolioHolding>): CarteraFilterSelection =
    CarteraFilterSelection(
        assetClasses = assetClasses intersect holdings.mapTo(HashSet()) { it.classFilterKey() },
        brokers = brokers intersect holdings.mapTo(HashSet()) { it.brokerFilterKey() },
        types = types intersect holdings.mapTo(HashSet()) { it.typeFilterKey() },
    )

/** The holdings that pass [selection]: any of the chosen values in each dimension, and all dimensions together. */
internal fun List<PortfolioHolding>.matching(selection: CarteraFilterSelection): List<PortfolioHolding> = filter {
    (selection.assetClasses.isEmpty() || it.classFilterKey() in selection.assetClasses) &&
        (selection.brokers.isEmpty() || it.brokerFilterKey() in selection.brokers) &&
        (selection.types.isEmpty() || it.typeFilterKey() in selection.types)
}

/** The options the [holdings] in scope offer and which of them [effective] marks; see [effectiveFor]. */
internal fun filterUi(holdings: List<PortfolioHolding>, effective: CarteraFilterSelection): CarteraFilterUi {
    val classes = classOptions(holdings, effective.assetClasses)
    val brokers = brokerOptions(holdings, effective.brokers)
    val types = typeOptions(holdings, effective.types)
    return CarteraFilterUi(
        assetClasses = classes,
        brokers = brokers,
        types = types,
        active = classes.activeIn(FilterDimension.ASSET_CLASS) +
            brokers.activeIn(FilterDimension.BROKER) +
            types.activeIn(FilterDimension.TYPE),
    )
}

/** Classes in the order of the enum, then the unclassified ones. */
private fun classOptions(holdings: List<PortfolioHolding>, selected: Set<String>): List<FilterOptionUi> {
    val present = holdings.mapTo(HashSet()) { it.classFilterKey() }
    val named = AssetClass.entries.filter { it.name in present }.map { it.name to assetClassLabel(it) }
    val unclassified = (UNCLASSIFIED_FILTER_KEY to UNCLASSIFIED_LABEL).takeIf { UNCLASSIFIED_FILTER_KEY in present }
    return (named + listOfNotNull(unclassified)).map { (key, label) -> FilterOptionUi(key, label, key in selected) }
}

/** Brokers by name ignoring case, written as the first holding that has it wrote it, then "Sin bróker". */
private fun brokerOptions(holdings: List<PortfolioHolding>, selected: Set<String>): List<FilterOptionUi> {
    val names = LinkedHashMap<String, String>()
    holdings.forEach { holding -> holding.broker?.let { names.getOrPut(holding.brokerFilterKey()) { it } } }
    val named = names.entries.sortedBy { it.key }.map { FilterOptionUi(it.key, it.value, it.key in selected) }
    val none = FilterOptionUi(NO_BROKER_FILTER_KEY, NO_BROKER_LABEL, NO_BROKER_FILTER_KEY in selected)
    return if (holdings.any { it.broker == null }) named + none else named
}

/** Types in the order of the enum, named as the pill of each row names them. */
private fun typeOptions(holdings: List<PortfolioHolding>, selected: Set<String>): List<FilterOptionUi> {
    val present = holdings.mapTo(HashSet()) { it.typeFilterKey() }
    return InstrumentType.entries.filter { it.name in present }
        .map { FilterOptionUi(it.name, instrumentTypeLabel(it), it.name in selected) }
}

private fun List<FilterOptionUi>.activeIn(dimension: FilterDimension): List<ActiveFilterUi> =
    filter { it.selected }.map { ActiveFilterUi(dimension, it.key, it.label) }

/** [this] with [key] of [dimension] chosen, or taken out when it already was. */
internal fun CarteraFilterSelection.toggled(dimension: FilterDimension, key: String): CarteraFilterSelection =
    update(dimension) { if (key in it) it - key else it + key }

/** [this] without [key] of [dimension]. */
internal fun CarteraFilterSelection.without(dimension: FilterDimension, key: String): CarteraFilterSelection =
    update(dimension) { it - key }

private fun CarteraFilterSelection.update(
    dimension: FilterDimension,
    change: (Set<String>) -> Set<String>,
): CarteraFilterSelection = when (dimension) {
    FilterDimension.ASSET_CLASS -> copy(assetClasses = change(assetClasses))
    FilterDimension.BROKER -> copy(brokers = change(brokers))
    FilterDimension.TYPE -> copy(types = change(types))
}

internal fun filterSummaryText(shown: Int, total: Int): String = "Filtrado · $shown de $total productos"
