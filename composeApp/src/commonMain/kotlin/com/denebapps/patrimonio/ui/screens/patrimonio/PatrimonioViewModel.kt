package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.AccountAssignment
import com.denebapps.patrimonio.domain.calc.AccountFilter
import com.denebapps.patrimonio.domain.calc.AccountUsage
import com.denebapps.patrimonio.domain.calc.accountUsage
import com.denebapps.patrimonio.domain.calc.assetsByGroup
import com.denebapps.patrimonio.domain.calc.liabilitiesByGroup
import com.denebapps.patrimonio.domain.calc.monthDelta
import com.denebapps.patrimonio.domain.calc.monthLabelEs
import com.denebapps.patrimonio.domain.calc.netWorth
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
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
 *  underlying [Asset.AssetGroup]/[Liability.LiabilityGroup] enum `.name` — the UI layer resolves
 *  icon/tone from it. */
data class GroupShareUi(val groupId: String, val label: String, val total: Money, val sharePct: Int)

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
)

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
)

/** One asset-type chip of the account filter. */
data class TypeFilterOptionUi(val group: Asset.AssetGroup, val label: String, val selected: Boolean)

/** Total (EUR) and number of the accounts the active filter shows. */
data class FilterSummaryUi(val total: Money, val count: Int)

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
)

private data class PatrimonioData(
    val assets: List<Asset>,
    val liabilities: List<Liability>,
    val snapshots: List<NetWorthSnapshot>,
    val accountGroups: List<AccountGroup>,
    val rates: FxRates,
)

/**
 * Hero (net worth, delta, sparkline), activos/pasivos toggle, composition, and per-group lists
 * for the Patrimonio tab (design.md Data Flow). Combines [AssetRepository]/[LiabilityRepository]/
 * [NetWorthRepository]/[AccountGroupRepository]/[FxRepository] `Flow`s into a nested `combine` data
 * bundle (5-arity, per the `StatsViewModel.dataFlow` precedent), then combines that bundle with the
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

    fun onTypeFilterToggle(type: Asset.AssetGroup) {
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
        val totalAssets = assets.fold(Money.ZERO) { acc, a -> acc + a.amount.toEur(rates) }
        val totalLiabs = liabilities.fold(Money.ZERO) { acc, l -> acc + l.amount.toEur(rates) }
        val currentNetWorth = netWorth(assets, liabilities, rates)

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
                PatrimonioView.ACTIVOS -> assetsByGroup(assets, rates).map {
                    GroupShareUi(it.group.name, assetGroupLabel(it.group), it.total, percentage(it.total, viewTotal))
                }
                PatrimonioView.PASIVOS -> liabilitiesByGroup(liabilities, rates).map {
                    GroupShareUi(
                        it.group.name,
                        liabilityGroupLabel(it.group),
                        it.total,
                        percentage(it.total, viewTotal),
                    )
                }
            }
        }

        val usage = accountUsage(goals, accountGroups)
        val filtering = selectedView == PatrimonioView.ACTIVOS && accountFilter.isActive
        val visibleAssets = if (filtering) assets.filter { accountFilter.matches(it, usage[it.id]) } else assets
        val groups = when (selectedView) {
            PatrimonioView.ACTIVOS -> assetsByGroup(assets, rates).mapNotNull { groupTotal ->
                val items = assets.filter { it.group == groupTotal.group }
                val visible = visibleAssets.filter { it.group == groupTotal.group }
                if (visible.isEmpty()) return@mapNotNull null
                // A type chip alone keeps whole groups; only a partially hidden group reads "2 de 5".
                val partial = visible.size < items.size
                PatrimonioGroupUi(
                    groupId = groupTotal.group.name,
                    label = assetGroupLabel(groupTotal.group),
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
                    groupId = groupTotal.group.name,
                    label = liabilityGroupLabel(groupTotal.group),
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
            isEmpty = if (selectedView == PatrimonioView.ACTIVOS) assets.isEmpty() else liabilities.isEmpty(),
            filter = accountFilter,
            typeFilterOptions = typeFilterOptions(assets, accountFilter),
            filterSummary = FilterSummaryUi(eurTotal(visibleAssets, rates), visibleAssets.size).takeIf { filtering },
        )
    }
}

private fun Asset.toItemUi(usage: AccountUsage?) = PatrimonioItemUi(
    id = id,
    name = name,
    subtitle = subtitle,
    amount = amount.amount,
    currency = amount.currency,
    inGoal = usage?.goalNames.orEmpty().isNotEmpty(),
    inGroup = usage?.groupNames.orEmpty().isNotEmpty(),
)

private fun Liability.toItemUi() = PatrimonioItemUi(id, name, subtitle, amount.amount, amount.currency)

private fun eurTotal(assets: List<Asset>, rates: FxRates): Money =
    assets.fold(Money.ZERO) { acc, asset -> acc + asset.amount.toEur(rates) }

/** One chip per type the user has, plus any selected type that no longer has accounts so it can be
 *  unselected; in the enum's order. */
private fun typeFilterOptions(assets: List<Asset>, filter: AccountFilter): List<TypeFilterOptionUi> {
    val present = assets.mapTo(mutableSetOf()) { it.group } + filter.types
    return Asset.AssetGroup.entries
        .filter { it in present }
        .map { TypeFilterOptionUi(it, typeChipLabel(it), it in filter.types) }
}

/** Shorter than [assetGroupLabel] so the chips fit in a row. */
private fun typeChipLabel(group: Asset.AssetGroup): String = when (group) {
    Asset.AssetGroup.BANK -> "Bancos"
    else -> assetGroupLabel(group)
}

private fun itemCountLabel(count: Int): String = if (count == 1) "1 elemento" else "$count elementos"

private fun percentage(value: Money, total: Money): Int = if (total.minorUnits <= 0L) {
    0
} else {
    (value.minorUnits.toDouble() * 100.0 / total.minorUnits.toDouble()).roundToInt()
}

/** Labels ported 1:1 from `design-reference/shared.jsx`'s `ASSET_GROUPS`. */
private fun assetGroupLabel(group: Asset.AssetGroup): String = when (group) {
    Asset.AssetGroup.BANK -> "Cuentas bancarias"
    Asset.AssetGroup.INVEST -> "Inversión"
    Asset.AssetGroup.REALESTATE -> "Inmuebles"
    Asset.AssetGroup.CRYPTO -> "Cripto"
    Asset.AssetGroup.CASH -> "Efectivo"
}

/** Labels ported 1:1 from `design-reference/shared.jsx`'s `LIAB_GROUPS`. */
private fun liabilityGroupLabel(group: Liability.LiabilityGroup): String = when (group) {
    Liability.LiabilityGroup.MORTGAGE -> "Hipotecas"
    Liability.LiabilityGroup.LOAN -> "Préstamos"
    Liability.LiabilityGroup.CARD -> "Tarjetas"
}
