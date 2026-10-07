package com.denebapps.patrimonio.ui.screens.history

import com.denebapps.patrimonio.domain.calc.MonthTrend
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.testing.FakeAssetRepository
import com.denebapps.patrimonio.testing.FakeFxRepository
import com.denebapps.patrimonio.testing.FakeLiabilityRepository
import com.denebapps.patrimonio.testing.FakeNetWorthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class NetWorthHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val currentMonth = YearMonth(2026, 10)

    /** Monthly net worth 1 000, 1 100, … from [first] up to September, then October from the live assets. */
    private fun viewModel(first: YearMonth, liveMinor: Long): NetWorthHistoryViewModel {
        var month = first
        var value = 1_000_00L
        val snapshots = buildList {
            while (month < currentMonth) {
                add(NetWorthSnapshot(month, assets = Money(value), liabilities = Money.ZERO))
                value += 100_00
                month = if (month.month == 12) YearMonth(month.year + 1, 1) else YearMonth(month.year, month.month + 1)
            }
        }
        return NetWorthHistoryViewModel(
            assetRepository = FakeAssetRepository(
                listOf(
                    Asset("a1", Asset.AssetGroup.BANK, "Cuenta", null, CurrencyAmount(Money(liveMinor), Currency.EUR)),
                ),
            ),
            liabilityRepository = FakeLiabilityRepository(),
            netWorthRepository = FakeNetWorthRepository(snapshots),
            fxRepository = FakeFxRepository(),
            monthFlow = flowOf(currentMonth),
        )
    }

    @Test
    fun `the default range shows the last twelve months ending with the live current month`() = runTest(dispatcher) {
        val vm = viewModel(first = YearMonth(2025, 1), liveMinor = 2_000_00)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(HistoryRange.YEAR, state.range)
        assertEquals(12, state.months.size)
        assertEquals(YearMonth(2025, 11), state.months.first().month)
        assertEquals(currentMonth, state.months.last().month)
        assertEquals("Oct", state.months.last().shortLabel)
        assertEquals("Octubre 2026", state.months.last().longLabel)
        assertEquals(Money(2_000_00), state.currentNetWorth)
        // September 2026 is the 21st snapshot: 1 000 + 20 × 100 = 3 000, so October loses 1 000 (-33,3 %).
        assertEquals(Money(-1_000_00), state.months.last().change)
        assertEquals(-333L, state.months.last().changePercentTenths)
        assertEquals(currentMonth, state.selected?.month)
        job.cancel()
    }

    @Test
    fun `the period change sums the monthly changes and counts each trend`() = runTest(dispatcher) {
        val vm = viewModel(first = YearMonth(2026, 6), liveMinor = 1_400_00)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        // Jun 1 000, Jul 1 100, Aug 1 200, Sep 1 300, Oct live 1 400.
        assertEquals(5, state.months.size)
        assertNull(state.months.first().change)
        assertEquals(Money(400_00), state.periodChange)
        assertEquals(4, state.upMonths)
        assertEquals(0, state.downMonths)
        assertEquals(0, state.stableMonths)
        assertEquals(
            listOf(null, MonthTrend.UP, MonthTrend.UP, MonthTrend.UP, MonthTrend.UP),
            state.months.map { it.trend },
        )
        job.cancel()
    }

    @Test
    fun `changing the range and the selected month`() = runTest(dispatcher) {
        val vm = viewModel(first = YearMonth(2025, 1), liveMinor = 2_000_00)
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onRangeSelect(HistoryRange.HALF_YEAR)
        vm.onMonthSelect(YearMonth(2026, 7))
        advanceUntilIdle()
        assertEquals(6, vm.state.value.months.size)
        assertEquals(YearMonth(2026, 7), vm.state.value.selected?.month)

        vm.onRangeSelect(HistoryRange.ALL)
        advanceUntilIdle()
        assertEquals(22, vm.state.value.months.size)
        assertEquals(YearMonth(2025, 1), vm.state.value.months.first().month)

        // A selected month outside the range falls back to the latest one.
        vm.onMonthSelect(YearMonth(2025, 2))
        vm.onRangeSelect(HistoryRange.HALF_YEAR)
        advanceUntilIdle()
        assertEquals(currentMonth, vm.state.value.selected?.month)
        job.cancel()
    }

    @Test
    fun `descriptions and percentages read naturally`() {
        val row = MonthRowUi(
            month = currentMonth,
            shortLabel = "Oct",
            longLabel = "Octubre 2026",
            netWorth = Money(4_120_00),
            change = Money(1_230_00),
            changePercentTenths = 28,
            trend = MonthTrend.UP,
            recorded = true,
        )

        assertEquals("Ganas +1.230,00 € (+2,8 %) respecto al mes anterior", changeDescription(row))
        assertEquals("Primer mes con datos", changeDescription(row.copy(change = null, trend = null)))
        assertEquals("-0,4 %", formatPercentTenths(-4))
        assertEquals("-12,00 €", formatSigned(Money(-12_00)))
    }
}
