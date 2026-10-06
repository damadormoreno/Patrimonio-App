package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
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
)

/** One per-group card (spec: Per-Group List Rows). */
data class PatrimonioGroupUi(
    val groupId: String,
    val label: String,
    val items: List<PatrimonioItemUi>,
    val itemCountLabel: String,
    val sharePct: Int,
    val total: Money,
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
 * internal [view] toggle and the injected [monthFlow] (design.md Decision 1 & 3).
 */
class PatrimonioViewModel(
    assetRepository: AssetRepository,
    liabilityRepository: LiabilityRepository,
    netWorthRepository: NetWorthRepository,
    accountGroupRepository: AccountGroupRepository,
    fxRepository: FxRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
    monthFlow: Flow<YearMonth>,
) : ViewModel() {
    private val view = MutableStateFlow(PatrimonioView.ACTIVOS)

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
        view,
        monthFlow,
    ) { data, selectedView, currentMonth ->
        buildState(data, selectedView, currentMonth)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(PATRIMONIO_STOP_TIMEOUT_MS),
        initialValue = buildState(
            PatrimonioData(emptyList(), emptyList(), emptyList(), emptyList(), FxRates(emptyMap())),
            PatrimonioView.ACTIVOS,
            initialMonth,
        ),
    )

    fun onViewSelect(value: PatrimonioView) {
        view.value = value
    }

    private fun buildState(
        data: PatrimonioData,
        selectedView: PatrimonioView,
        currentMonth: YearMonth,
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

        val groups = when (selectedView) {
            PatrimonioView.ACTIVOS -> assetsByGroup(assets, rates).map { groupTotal ->
                val items = assets.filter { it.group == groupTotal.group }
                PatrimonioGroupUi(
                    groupId = groupTotal.group.name,
                    label = assetGroupLabel(groupTotal.group),
                    items = items.map { it.toItemUi() },
                    itemCountLabel = itemCountLabel(items.size),
                    sharePct = percentage(groupTotal.total, viewTotal),
                    total = groupTotal.total,
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
        )
    }
}

private fun Asset.toItemUi() = PatrimonioItemUi(id, name, subtitle, amount.amount, amount.currency)

private fun Liability.toItemUi() = PatrimonioItemUi(id, name, subtitle, amount.amount, amount.currency)

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
