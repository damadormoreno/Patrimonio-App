package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FUND_A
import com.denebapps.patrimonio.domain.model.FUND_B
import com.denebapps.patrimonio.domain.model.FUND_C
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.buy
import com.denebapps.patrimonio.domain.model.date
import com.denebapps.patrimonio.domain.model.dividend
import com.denebapps.patrimonio.domain.model.feeMovement
import com.denebapps.patrimonio.domain.model.opening
import com.denebapps.patrimonio.domain.model.sell
import com.denebapps.patrimonio.domain.model.transferIn
import com.denebapps.patrimonio.domain.model.transferOut
import com.denebapps.patrimonio.domain.model.units
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The own-return figures of a position and of a scope, from the movements. Money in EUR cents; with `today` one year
// after the first flow, a plain ratio is also the XIRR.
class OwnReturnsTest {
    private val today = date(2026, 1, 1)

    private fun returns(
        holding: String,
        movements: List<Movement>,
        value: Long?,
        transferValue: (Movement) -> Money? = { null },
    ): PositionReturns {
        val ledger = computeLedgers(movements).getValue(holding)
        return positionReturns(
            movements = movements.filter { it.holdingId == holding },
            ledger = ledger,
            today = today,
            valueEur = value?.let { Money(it) },
            transferValueEur = transferValue,
        )
    }

    // ---- priceOn -----------------------------------------------------------------------------------------------

    private fun point(day: Int, month: Int, euros: Long) =
        PricePoint(date(2026, month, day), Price(euros * 1_000_000, Currency.EUR))

    @Test
    fun `the price on a date is its own or the latest one within a week before`() {
        val prices = listOf(point(2, 1, 10), point(9, 1, 11))

        assertEquals(Price(10_000_000, Currency.EUR), prices.priceOn(date(2026, 1, 2)))
        assertEquals(Price(10_000_000, Currency.EUR), prices.priceOn(date(2026, 1, 5)))
        assertEquals(Price(11_000_000, Currency.EUR), prices.priceOn(date(2026, 1, 16)))
    }

    @Test
    fun `there is no price before the history or too long after the last one`() {
        val prices = listOf(point(2, 1, 10), point(9, 1, 11))

        assertNull(prices.priceOn(date(2026, 1, 1)))
        assertNull(prices.priceOn(date(2026, 1, 17)))
        assertNull(emptyList<PricePoint>().priceOn(date(2026, 1, 2)))
    }

    @Test
    fun `the market value of a transfer leg is its units at the price of that day`() {
        val prices = listOf(point(2, 1, 12))
        val leg = transferOut("o", date(2026, 1, 3), units(10), "t")

        assertEquals(Money(12_000), transferMarketValue(prices)(leg))
        assertNull(transferMarketValue(emptyList())(leg))
    }

    // ---- one position ------------------------------------------------------------------------------------------

    @Test
    fun `a position with a known cost has its gain, ratio and annual return`() {
        // 10 units for 1000.00 + 5.00 fee = cost 1005.00 on 2025-01-01; worth 1200.00 a year later.
        val position = returns(FUND_A, listOf(buy("b", date(2025, 1, 1), units(10), 100_000, feeCents = 500)), 120_000)

        val gain = assertNotNull(position.unrealized)
        assertEquals(Money(19_500), gain.totalGainEur)
        assertEquals(Money(100_500), gain.knownCostEur)
        assertEquals(19_500.0 / 100_500.0, assertNotNull(gain.returnRatio), 1e-12)
        assertEquals(120_000.0 / 100_500.0 - 1, assertNotNull(position.xirr), 1e-6)
        assertTrue(position.isComplete)
        assertFalse(position.isClosed)
        assertEquals(Money(500), position.feesEur)
    }

    @Test
    fun `a position whose cost is unknown has no gain and no annual return`() {
        val position = returns(FUND_A, listOf(opening("o", date(2025, 1, 1), units(10), costCents = null)), 100_000)

        val gain = assertNotNull(position.unrealized)
        assertNull(gain.totalGainEur)
        assertEquals(Money.ZERO, gain.knownCostEur)
        assertEquals(UnitsBalance(units(10).scaled), gain.unknownUnits)
        assertNull(position.flows)
        assertNull(position.xirr)
        assertFalse(position.isComplete)
    }

    @Test
    fun `a position with a known and an unknown part keeps the known gain apart`() {
        // 4 units of unknown cost + 6 units bought for 600.00, all worth 1100.00: the known 6/10 are worth 660.00.
        val position = returns(
            FUND_A,
            listOf(
                opening("o", date(2025, 1, 1), units(4), costCents = null),
                buy("b", date(2025, 6, 1), units(6), 60_000),
            ),
            110_000,
        )

        val gain = assertNotNull(position.unrealized)
        assertNull(gain.totalGainEur)
        assertEquals(Money(6_000), gain.knownGainEur)
        assertEquals(Money(60_000), gain.knownCostEur)
        assertEquals(0.1, assertNotNull(gain.returnRatio), 1e-12)
        assertNull(position.xirr)
        assertFalse(position.isComplete)
    }

    @Test
    fun `selling units of unknown cost leaves the realized gain unknown`() {
        val position = returns(
            FUND_A,
            listOf(
                opening("o", date(2025, 1, 1), units(10), costCents = null),
                sell("s", date(2025, 6, 1), units(4), 40_000),
            ),
            60_000,
        )

        assertNull(position.ledger.realized.totalGainEur)
        assertFalse(position.isComplete)
    }

    @Test
    fun `a position without a price has no unrealized gain and no annual return`() {
        val position = returns(FUND_A, listOf(buy("b", date(2025, 1, 1), units(10), 100_000)), value = null)

        assertNull(position.valueEur)
        assertNull(position.unrealized)
        assertNull(position.xirr)
        assertFalse(position.isComplete)
    }

    @Test
    fun `a closed position keeps its realized gain and the annual return of its whole life`() {
        // Bought 1000.00 on 2025-01-01, sold everything for 1100.00 a year later: +100.00, 10 % a year.
        val position = returns(
            FUND_A,
            listOf(
                buy("b", date(2025, 1, 1), units(10), 100_000),
                sell("s", date(2026, 1, 1), units(10), 110_000),
            ),
            value = 0,
        )

        assertTrue(position.isClosed)
        assertEquals(Money(10_000), position.ledger.realized.totalGainEur)
        assertEquals(0.10, assertNotNull(position.xirr), 1e-6)
        assertTrue(position.isComplete)
    }

    @Test
    fun `dividends and fees add up apart from the cost`() {
        val position = returns(
            FUND_A,
            listOf(
                buy("b", date(2025, 1, 1), units(10), 100_000, feeCents = 500),
                dividend("d", date(2025, 6, 1), 2_000),
                feeMovement("f", date(2025, 7, 1), 300),
            ),
            100_000,
        )

        assertEquals(Money(2_000), position.ledger.dividendsEur)
        assertEquals(Money(800), position.feesEur)
    }

    @Test
    fun `a transfer is valued at market in the annual return, or at its carried cost without a price`() {
        // A: 10 units bought 1000.00 on 2025-01-01, all moved to B on 2025-07-01 (184 days before today).
        val movements = listOf(
            buy("b", date(2025, 1, 1), units(10), 100_000),
            transferOut("o", date(2025, 7, 1), units(10), "t"),
            transferIn("i", date(2025, 7, 1), units(10), "t"),
        )

        val atMarket = returns(FUND_B, movements, 130_000) { Money(120_000) }
        val atCost = returns(FUND_B, movements, 130_000)

        assertEquals((130_000.0 / 120_000.0).pow(365.0 / 184.0) - 1, assertNotNull(atMarket.xirr), 1e-6)
        assertEquals((130_000.0 / 100_000.0).pow(365.0 / 184.0) - 1, assertNotNull(atCost.xirr), 1e-6)
        assertEquals(Money(100_000), atCost.ledger.costBasis.knownCostEur)
    }

    // ---- a scope -----------------------------------------------------------------------------------------------

    @Test
    fun `a transfer between two positions of the scope cancels out`() {
        // A: 10 units for 1000.00 on 2025-01-01, moved to B on 2025-07-01; B is worth 1300.00 today.
        val movements = listOf(
            buy("b", date(2025, 1, 1), units(10), 100_000),
            transferOut("o", date(2025, 7, 1), units(10), "t"),
            transferIn("i", date(2025, 7, 1), units(10), "t"),
        )
        val a = returns(FUND_A, movements, value = 0) { Money(120_000) }
        val b = returns(FUND_B, movements, 130_000) { Money(120_000) }

        val scope = scopeReturns(productCount = 2, positions = listOf(a, b))

        assertEquals(2, scope.covered)
        assertEquals(Money(100_000), scope.knownCostEur)
        assertEquals(Money(130_000), scope.valueEur)
        assertEquals(Money(30_000), scope.unrealizedGainEur)
        assertEquals(0.30, assertNotNull(scope.unrealizedRatio), 1e-12)
        assertEquals(Money.ZERO, scope.realizedGainEur)
        assertEquals(0.30, assertNotNull(scope.xirr), 1e-6)
    }

    @Test
    fun `a transfer to a position outside the scope stays as money taken out at market value`() {
        val movements = listOf(
            buy("b", date(2025, 1, 1), units(10), 100_000),
            transferOut("o", date(2025, 7, 1), units(10), "t"),
            transferIn("i", date(2025, 7, 1), units(10), "t"),
        )
        val a = returns(FUND_A, movements, value = 0) { Money(120_000) }

        val scope = scopeReturns(productCount = 1, positions = listOf(a))

        assertEquals((120_000.0 / 100_000.0).pow(365.0 / 181.0) - 1, assertNotNull(scope.xirr), 1e-6)
    }

    @Test
    fun `positions with an unknown cost are left out of the scope figures`() {
        val movements = listOf(
            buy("b", date(2025, 1, 1), units(10), 100_000, holding = FUND_A),
            opening("o", date(2025, 1, 1), units(5), costCents = null, holding = FUND_C),
            dividend("d", date(2025, 6, 1), 1_000, holding = FUND_A),
            dividend("dc", date(2025, 6, 1), 9_999, holding = FUND_C),
        )
        val a = returns(FUND_A, movements, 120_000)
        val c = returns(FUND_C, movements, 50_000)

        val scope = scopeReturns(productCount = 4, positions = listOf(a, c))

        assertEquals(4, scope.productCount)
        assertEquals(2, scope.withMovements)
        assertEquals(1, scope.covered)
        assertEquals(Money(100_000), scope.knownCostEur)
        assertEquals(Money(120_000), scope.valueEur)
        assertEquals(Money(1_000), scope.dividendsEur)
    }

    @Test
    fun `a scope with no usable position has no figures`() {
        val c = returns(
            FUND_C,
            listOf(opening("o", date(2025, 1, 1), units(5), costCents = null, holding = FUND_C)),
            50_000,
        )

        val scope = scopeReturns(productCount = 1, positions = listOf(c))

        assertEquals(0, scope.covered)
        assertEquals(Money.ZERO, scope.valueEur)
        assertNull(scope.xirr)
        assertNull(scope.unrealizedRatio)
    }
}
