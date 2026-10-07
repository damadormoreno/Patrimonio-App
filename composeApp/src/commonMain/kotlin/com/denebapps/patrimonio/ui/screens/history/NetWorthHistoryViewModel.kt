package com.denebapps.patrimonio.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.MONTHS_ES
import com.denebapps.patrimonio.domain.calc.MonthTrend
import com.denebapps.patrimonio.domain.calc.MonthlyNetWorth
import com.denebapps.patrimonio.domain.calc.monthLabelEs
import com.denebapps.patrimonio.domain.calc.monthlyNetWorth
import com.denebapps.patrimonio.domain.calc.netWorth
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.YearMonth
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
import kotlin.math.absoluteValue

private const val HISTORY_STOP_TIMEOUT_MS = 5_000L

enum class HistoryRange(val months: Int?, val label: String) {
    HALF_YEAR(6, "6 meses"),
    YEAR(12, "12 meses"),
    ALL(null, "Todo"),
}

/** One month of the chart and the table. [changePercentTenths] is the change against the previous
 *  month in tenths of a percent (null when there is no previous month or it was zero). */
data class MonthRowUi(
    val month: YearMonth,
    val shortLabel: String,
    val longLabel: String,
    val netWorth: Money,
    val change: Money?,
    val changePercentTenths: Long?,
    val trend: MonthTrend?,
    val recorded: Boolean,
)

data class NetWorthHistoryUiState(
    val range: HistoryRange,
    /** Months in [range], oldest first. */
    val months: List<MonthRowUi>,
    val currentNetWorth: Money,
    /** Sum of the monthly changes shown; null when the range has no change to sum. */
    val periodChange: Money?,
    val upMonths: Int,
    val downMonths: Int,
    val stableMonths: Int,
    val selected: MonthRowUi?,
)

/**
 * Month-by-month net worth for the "Evolución mensual" screen: one entry per calendar month from the
 * first snapshot to today ([monthlyNetWorth]), the current month using the live total like the
 * Patrimonio hero. [onRangeSelect] narrows it to the last 6 or 12 months; [onMonthSelect] picks the
 * month shown in detail (the latest one by default).
 */
class NetWorthHistoryViewModel(
    assetRepository: AssetRepository,
    liabilityRepository: LiabilityRepository,
    netWorthRepository: NetWorthRepository,
    fxRepository: FxRepository,
    monthFlow: Flow<YearMonth>,
) : ViewModel() {
    private val range = MutableStateFlow(HistoryRange.YEAR)
    private val selectedMonth = MutableStateFlow<YearMonth?>(null)

    private val history = combine(
        assetRepository.observeAll(),
        liabilityRepository.observeAll(),
        netWorthRepository.observeSnapshots(),
        fxRepository.observeRates(),
        monthFlow,
    ) { assets, liabilities, snapshots, rates, currentMonth ->
        monthlyNetWorth(snapshots, currentMonth, netWorth(assets, liabilities, rates))
    }

    val state: StateFlow<NetWorthHistoryUiState> = combine(history, range, selectedMonth, ::buildState).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(HISTORY_STOP_TIMEOUT_MS),
        initialValue = buildState(emptyList(), HistoryRange.YEAR, null),
    )

    fun onRangeSelect(value: HistoryRange) {
        range.value = value
    }

    fun onMonthSelect(month: YearMonth) {
        selectedMonth.value = month
    }
}

private fun buildState(
    history: List<MonthlyNetWorth>,
    range: HistoryRange,
    selectedMonth: YearMonth?,
): NetWorthHistoryUiState {
    val previousByMonth = history.zipWithNext().associate { (before, after) -> after.month to before.netWorth }
    val inRange = range.months?.let { history.takeLast(it) } ?: history
    val months = inRange.map { it.toRowUi(previousByMonth[it.month]) }
    val changes = months.mapNotNull { it.change }
    return NetWorthHistoryUiState(
        range = range,
        months = months,
        currentNetWorth = history.lastOrNull()?.netWorth ?: Money.ZERO,
        periodChange = changes.takeIf { it.isNotEmpty() }?.fold(Money.ZERO, Money::plus),
        upMonths = months.count { it.trend == MonthTrend.UP },
        downMonths = months.count { it.trend == MonthTrend.DOWN },
        stableMonths = months.count { it.trend == MonthTrend.STABLE },
        selected = months.firstOrNull { it.month == selectedMonth } ?: months.lastOrNull(),
    )
}

private fun MonthlyNetWorth.toRowUi(previous: Money?) = MonthRowUi(
    month = month,
    shortLabel = MONTHS_ES[month.month - 1].take(3),
    longLabel = monthLabelEs(month),
    netWorth = netWorth,
    change = change,
    changePercentTenths = change?.let { delta ->
        val base = previous?.minorUnits?.absoluteValue ?: 0L
        if (base == 0L) null else delta.minorUnits * 1_000 / base
    },
    trend = trend,
    recorded = recorded,
)
