package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.AccountAssignment
import com.denebapps.patrimonio.domain.calc.AccountFilter
import com.denebapps.patrimonio.domain.calc.AccountUsage
import com.denebapps.patrimonio.domain.calc.HoldingUsage
import com.denebapps.patrimonio.domain.calc.accountUsage
import com.denebapps.patrimonio.domain.calc.assetsByGroup
import com.denebapps.patrimonio.domain.calc.holdingUsage
import com.denebapps.patrimonio.domain.calc.liabilitiesByGroup
import com.denebapps.patrimonio.domain.calc.monthDelta
import com.denebapps.patrimonio.domain.calc.monthLabelEs
import com.denebapps.patrimonio.domain.calc.netWorth
import com.denebapps.patrimonio.domain.calc.portfolioTotalEur
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.ui.components.PORTFOLIO_LOOK
import com.denebapps.patrimonio.ui.components.PORTFOLIO_TYPE_ID
import com.denebapps.patrimonio.ui.components.TypeLook
import com.denebapps.patrimonio.ui.components.typeLook
import com.denebapps.patrimonio.ui.components.typeOptions
import com.denebapps.patrimonio.ui.screens.portfolio.instrumentTypeLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.roundToInt

private const val PATRIMONIO_STOP_TIMEOUT_MS = 5_000L

/** Active stat toggle driving composition/list content (spec: Activos/Pasivos Stat Toggle). */
enum class PatrimonioView { ACTIVOS, PASIVOS }

/** One composition-bar segment / legend entry (spec: Composition Bar by Group). [groupId] is the
 *  account type id — the UI layer resolves icon/tone from it. */
data class GroupShareUi(
    val groupId: String,
    val label: String,
    val total: Money,
    val sharePct: Int,
    val type: TypeLook = typeLook(groupId),
)

/** One member item row inside a [PatrimonioGroupUi] card. [amount]/[currency] are the item's
 *  ORIGINAL (non-EUR-converted) currency amount — the UI shows a currency badge when non-EUR. */
data class PatrimonioItemUi(
    val id: String,
    val name: String,
    val subtitle: String?,
    val amount: Money,
    val currency: Currency,
    /** The account is followed by an open goal / held by one of the user's groups (liabilities: never). */
    val inGoal: Boolean = false,
    val inGroup: Boolean = false,
    /** The account's own emoji, shown before its name. */
    val emoji: String? = null,
)

/**
 * What the composition header says about the [composition] it heads: how many account groups it has ("2 grupos") and,
 * when the portfolio is one of its shares, "Cartera" after them. The portfolio is not an account group, so it is named
 * rather than counted.
 */
fun compositionCountLabel(composition: List<GroupShareUi>): String {
    val groupCount = composition.count { it.groupId != PORTFOLIO_TYPE_ID }
    val hasPortfolio = composition.size > groupCount
    val groups = when {
        groupCount == 1 -> "1 grupo"
        groupCount == 0 && hasPortfolio -> null
        else -> "$groupCount grupos"
    }
    val portfolio = PORTFOLIO_LOOK.label.takeIf { hasPortfolio }
    return listOfNotNull(groups, portfolio).joinToString(" · ")
}

/** One per-group card (spec: Per-Group List Rows). */
data class PatrimonioGroupUi(
    val groupId: String,
    val label: String,
    val items: List<PatrimonioItemUi>,
    val itemCountLabel: String,
    val sharePct: Int,
    val total: Money,
    /** The account filter hides some of the group's items: [items], [total] and [itemCountLabel] cover
     *  only the visible ones ("2 de 5 elementos") and [sharePct] is not shown. */
    val filtered: Boolean = false,
    val type: TypeLook = typeLook(groupId),
)

/** One asset-type chip of the account filter. */
data class TypeFilterOptionUi(val group: String, val label: String, val selected: Boolean)

/** Total (EUR) of what the active filter shows, with the number of accounts ([count]) and of portfolio products
 *  ([productCount]) it is made of. */
data class FilterSummaryUi(val total: Money, val count: Int, val productCount: Int = 0)

/**
 * One product of the portfolio inside the Cartera card. [valueEur] is `null` for one with no price yet; a [closed]
 * position is worth zero. [inGoal]/[inGroup] are the usage icons: the product is in a group, and a goal follows it.
 */
data class PatrimonioHoldingUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val valueEur: Money?,
    val closed: Boolean = false,
    val inGoal: Boolean = false,
    val inGroup: Boolean = false,
)

/**
 * The investment portfolio as a block of the assets view: [total] in EUR (holdings with no value yet count as zero)
 * and how many [holdingCount] positions it shows, with or without a value, as [items] (open ones first). While the
 * account filter narrows the list it covers only the products that match ([filtered], out of [totalCount]).
 */
data class PortfolioSummaryUi(
    val total: Money,
    val holdingCount: Int,
    val items: List<PatrimonioHoldingUi> = emptyList(),
    val filtered: Boolean = false,
    val totalCount: Int = holdingCount,
)

data class PatrimonioUiState(
    val monthLabel: String,
    val netWorth: Money,
    val delta: Money?,
    val totalAssets: Money,
    val totalLiabs: Money,
    val assetCount: Int,
    val liabCount: Int,
    val view: PatrimonioView,
    val sparkValues: List<Long>,
    val composition: List<GroupShareUi>,
    val groups: List<PatrimonioGroupUi>,
    val groupsCount: Int,
    val isEmpty: Boolean,
    /** Account filter (assets view only): current choice, chips for the types the user has, and the
     *  summary of what it shows (null while no filter is active or in the liabilities view). */
    val filter: AccountFilter = AccountFilter(),
    val typeFilterOptions: List<TypeFilterOptionUi> = emptyList(),
    val filterSummary: FilterSummaryUi? = null,
    /** `null` while the user has no holdings. */
    val portfolio: PortfolioSummaryUi? = null,
)

private data class PatrimonioData(
    val assets: List<Asset>,
    val liabilities: List<Liability>,
    val snapshots: List<NetWorthSnapshot>,
    val accountGroups: List<AccountGroup>,
    val rates: FxRates,
    val customTypes: Map<String, CustomAccountType> = emptyMap(),
    val portfolio: List<PortfolioHolding> = emptyList(),
)

/**
 * Hero (net worth, delta, sparkline), activos/pasivos toggle, composition, and per-group lists
 * for the Patrimonio tab (design.md Data Flow). Combines [AssetRepository]/[LiabilityRepository]/
 * [NetWorthRepository]/[AccountGroupRepository]/[FxRepository]/[PortfolioRepository] `Flow`s into a nested
 * `combine` data bundle (5-arity, per the `StatsViewModel.dataFlow` precedent), then combines that bundle with the
 * savings goals (they tag the accounts they follow), the internal [view] toggle and the injected
 * [monthFlow] (design.md Decision 1 & 3).
 */
class PatrimonioViewModel(
    assetRepository: AssetRepository,
    liabilityRepository: LiabilityRepository,
    netWorthRepository: NetWorthRepository,
    accountGroupRepository: AccountGroupRepository,
    fxRepository: FxRepository,
    savingsGoalRepository: SavingsGoalRepository,
    accountTypeRepository: AccountTypeRepository,
    portfolioRepository: PortfolioRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
    monthFlow: Flow<YearMonth>,
) : ViewModel() {
    private val view = MutableStateFlow(PatrimonioView.ACTIVOS)

    /** Lives as long as this ViewModel; never persisted, so the app always opens on every account. */
    private val filter = MutableStateFlow(AccountFilter())

    private val initialMonth: YearMonth = run {
        val today = clock.todayIn(zoneProvider())
        YearMonth(today.year, today.monthNumber)
    }

    private val dataFlow = combine(
        assetRepository.observeAll(),
        liabilityRepository.observeAll(),
        netWorthRepository.observeSnapshots(),
        accountGroupRepository.observeAll(),
        fxRepository.observeRates(),
    ) { assets, liabilities, snapshots, accountGroups, rates ->
        PatrimonioData(assets, liabilities, snapshots, accountGroups, rates)
    }.combine(accountTypeRepository.observeAll()) { data, types ->
        data.copy(customTypes = types.associateBy { it.id })
    }.combine(portfolioRepository.observeAll()) { data, holdings ->
        data.copy(portfolio = holdings)
    }

    val state: StateFlow<PatrimonioUiState> = combine(
        dataFlow,
        savingsGoalRepository.observeAll(),
        view,
        monthFlow,
        filter,
    ) { data, goals, selectedView, currentMonth, accountFilter ->
        buildState(data, goals, selectedView, currentMonth, accountFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(PATRIMONIO_STOP_TIMEOUT_MS),
        initialValue = buildState(
            PatrimonioData(emptyList(), emptyList(), emptyList(), emptyList(), FxRates(emptyMap())),
            emptyList(),
            PatrimonioView.ACTIVOS,
            initialMonth,
            AccountFilter(),
        ),
    )

    fun onViewSelect(value: PatrimonioView) {
        view.value = value
    }

    fun onAssignmentFilterChange(assignment: AccountAssignment) {
        filter.value = filter.value.copy(assignment = assignment)
    }

    fun onTypeFilterToggle(type: String) {
        val types = filter.value.types
        filter.value = filter.value.copy(types = if (type in types) types - type else types + type)
    }

    fun onClearFilters() {
        filter.value = AccountFilter()
    }

    private fun buildState(
        data: PatrimonioData,
        goals: List<SavingsGoal>,
        selectedView: PatrimonioView,
        currentMonth: YearMonth,
        accountFilter: AccountFilter,
    ): PatrimonioUiState {
        val (assets, liabilities, snapshots, accountGroups, rates) = data
        val customTypes = data.customTypes
        // The portfolio is a block of its own inside the assets: not an account, so no group, goal or filter.
        val portfolioTotal = portfolioTotalEur(data.portfolio, rates)
        val totalAssets = eurTotal(assets, rates) + portfolioTotal
        val totalLiabs = liabilities.fold(Money.ZERO) { acc, l -> acc + l.amount.toEur(rates) }
        val currentNetWorth = netWorth(assets, liabilities, rates) + portfolioTotal

        // Most recent snapshot strictly before currentMonth (design.md Decision 3: reactive
        // delta, no suspend findMostRecentBefore call inside the combine pipeline).
        val previousSnapshot = snapshots.filter { it.yearMonth < currentMonth }.maxByOrNull { it.yearMonth }
        val delta = previousSnapshot?.let { monthDelta(currentNetWorth, it.netWorth) }

        val sparkValues = if (snapshots.size >= 2) {
            snapshots.sortedBy { it.yearMonth }.map { it.netWorth.minorUnits }
        } else {
            emptyList()
        }

        val viewTotal = if (selectedView == PatrimonioView.ACTIVOS) totalAssets else totalLiabs
        val composition = if (viewTotal.minorUnits <= 0L) {
            emptyList()
        } else {
            when (selectedView) {
                PatrimonioView.ACTIVOS -> {
                    val shares = assetsByGroup(assets, rates).map {
                        val type = typeLook(it.group, customTypes)
                        GroupShareUi(it.group, type.label, it.total, percentage(it.total, viewTotal), type)
                    }
                    val portfolioShare = GroupShareUi(
                        PORTFOLIO_LOOK.id,
                        PORTFOLIO_LOOK.label,
                        portfolioTotal,
                        percentage(portfolioTotal, viewTotal),
                        PORTFOLIO_LOOK,
                    ).takeIf { portfolioTotal.minorUnits > 0L }
                    (shares + listOfNotNull(portfolioShare)).sortedByDescending { it.total.minorUnits }
                }
                PatrimonioView.PASIVOS -> liabilitiesByGroup(liabilities, rates).map {
                    val type = typeLook(it.group, customTypes)
                    GroupShareUi(it.group, type.label, it.total, percentage(it.total, viewTotal), type)
                }
            }
        }

        val usage = accountUsage(goals, accountGroups)
        val filtering = selectedView == PatrimonioView.ACTIVOS && accountFilter.isActive
        val visibleAssets = if (filtering) assets.filter { accountFilter.matches(it, usage[it.id]) } else assets
        val holdingUse = holdingUsage(goals, accountGroups)
        val visibleHoldings = if (filtering) {
            data.portfolio.filter { accountFilter.matchesProduct(holdingUse[it.id]) }
        } else {
            data.portfolio
        }
        val portfolioSummary = portfolioSummary(data.portfolio, visibleHoldings, holdingUse, rates)
        val groups = when (selectedView) {
            PatrimonioView.ACTIVOS -> assetsByGroup(assets, rates).mapNotNull { groupTotal ->
                val items = assets.filter { it.group == groupTotal.group }
                val visible = visibleAssets.filter { it.group == groupTotal.group }
                if (visible.isEmpty()) return@mapNotNull null
                // A type chip alone keeps whole groups; only a partially hidden group reads "2 de 5".
                val partial = visible.size < items.size
                PatrimonioGroupUi(
                    groupId = groupTotal.group,
                    label = typeLook(groupTotal.group, customTypes).label,
                    type = typeLook(groupTotal.group, customTypes),
                    items = visible.map { it.toItemUi(usage[it.id]) },
                    itemCountLabel = if (partial) {
                        "${visible.size} de ${itemCountLabel(items.size)}"
                    } else {
                        itemCountLabel(items.size)
                    },
                    sharePct = percentage(groupTotal.total, viewTotal),
                    total = if (partial) eurTotal(visible, rates) else groupTotal.total,
                    filtered = partial,
                )
            }
            PatrimonioView.PASIVOS -> liabilitiesByGroup(liabilities, rates).map { groupTotal ->
                val items = liabilities.filter { it.group == groupTotal.group }
                PatrimonioGroupUi(
                    groupId = groupTotal.group,
                    label = typeLook(groupTotal.group, customTypes).label,
                    type = typeLook(groupTotal.group, customTypes),
                    items = items.map { it.toItemUi() },
                    itemCountLabel = itemCountLabel(items.size),
                    sharePct = percentage(groupTotal.total, viewTotal),
                    total = groupTotal.total,
                )
            }
        }

        return PatrimonioUiState(
            monthLabel = monthLabelEs(currentMonth),
            netWorth = currentNetWorth,
            delta = delta,
            totalAssets = totalAssets,
            totalLiabs = totalLiabs,
            assetCount = assets.size,
            liabCount = liabilities.size,
            view = selectedView,
            sparkValues = sparkValues,
            composition = composition,
            groups = groups,
            groupsCount = accountGroups.size,
            isEmpty = if (selectedView == PatrimonioView.ACTIVOS) {
                assets.isEmpty() && data.portfolio.isEmpty()
            } else {
                liabilities.isEmpty()
            },
            filter = accountFilter,
            typeFilterOptions = typeFilterOptions(assets, accountFilter, customTypes),
            filterSummary = FilterSummaryUi(
                total = eurTotal(visibleAssets, rates) + portfolioTotalEur(visibleHoldings, rates),
                count = visibleAssets.size,
                productCount = visibleHoldings.size,
            ).takeIf { filtering },
            portfolio = portfolioSummary,
        )
    }
}

/**
 * The Cartera card for the [visible] products out of all the [holdings] (the same unless the account filter narrows
 * them), or `null` when there are none to show.
 */
private fun portfolioSummary(
    holdings: List<PortfolioHolding>,
    visible: List<PortfolioHolding>,
    usage: Map<String, HoldingUsage>,
    rates: FxRates,
): PortfolioSummaryUi? {
    if (visible.isEmpty()) return null
    return PortfolioSummaryUi(
        total = portfolioTotalEur(visible, rates),
        holdingCount = visible.size,
        items = visible.closedLast().map { it.toItemUi(usage[it.id], rates) },
        filtered = visible.size < holdings.size,
        totalCount = holdings.size,
    )
}

private fun PortfolioHolding.toItemUi(usage: HoldingUsage?, rates: FxRates): PatrimonioHoldingUi {
    val closed = isClosedPosition
    return PatrimonioHoldingUi(
        id = id,
        name = name,
        subtitle = if (closed) CLOSED_LABEL else instrumentTypeLabel(type),
        valueEur = if (closed) Money.ZERO else value()?.toEur(rates),
        closed = closed,
        inGoal = usage?.goalNames.orEmpty().isNotEmpty(),
        inGroup = usage?.groupNames.orEmpty().isNotEmpty(),
    )
}

private const val CLOSED_LABEL = "Cerrada"

private fun Asset.toItemUi(usage: AccountUsage?) = PatrimonioItemUi(
    id = id,
    name = name,
    subtitle = subtitle,
    amount = amount.amount,
    currency = amount.currency,
    inGoal = usage?.goalNames.orEmpty().isNotEmpty(),
    inGroup = usage?.groupNames.orEmpty().isNotEmpty(),
    emoji = emoji,
)

private fun Liability.toItemUi() = PatrimonioItemUi(id, name, subtitle, amount.amount, amount.currency, emoji = emoji)

private fun eurTotal(assets: List<Asset>, rates: FxRates): Money =
    assets.fold(Money.ZERO) { acc, asset -> acc + asset.amount.toEur(rates) }

/** One chip per type the user has, plus any selected type that no longer has accounts so it can be
 *  unselected; built-in types in their order, then any other. */
private fun typeFilterOptions(
    assets: List<Asset>,
    filter: AccountFilter,
    customTypes: Map<String, CustomAccountType>,
): List<TypeFilterOptionUi> {
    val present = assets.mapTo(mutableSetOf()) { it.group } + filter.types
    return typeOptions(AccountKind.ASSET, customTypes.values.toList())
        .filter { it.id in present }
        .map { TypeFilterOptionUi(it.id, it.chipLabel, it.id in filter.types) }
}

private fun itemCountLabel(count: Int): String = if (count == 1) "1 elemento" else "$count elementos"

private fun percentage(value: Money, total: Money): Int = if (total.minorUnits <= 0L) {
    0
} else {
    (value.minorUnits.toDouble() * 100.0 / total.minorUnits.toDouble()).roundToInt()
}
