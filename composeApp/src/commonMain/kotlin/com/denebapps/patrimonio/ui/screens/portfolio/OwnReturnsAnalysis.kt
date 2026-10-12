package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.calc.MovementValidationException
import com.denebapps.patrimonio.domain.calc.PositionLedger
import com.denebapps.patrimonio.domain.calc.PositionReturns
import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.calc.positionReturns
import com.denebapps.patrimonio.domain.calc.scopeReturns
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.calc.toEurSeries
import com.denebapps.patrimonio.domain.calc.transferMarketValue
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.calc.valueSeries
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.ui.components.formatEur
import kotlinx.datetime.LocalDate

// The own returns computed from the data alone, without a selected range or a download state. It replays every
// movement of the app (transfers link positions), so the ViewModels run it off the main thread, once per data change.

/** One cent as a price: `PRICE_SCALE` (1e6) per euro over 100 cents. */
private const val CENT_TO_PRICE_SCALE = 10_000L

/**
 * The data of a position's own returns: its [holding], every movement of the app ([movements]), the [history] of its
 * price in its own currency, the exchange-rate [fxHistory] and today's [rates].
 */
internal data class OwnPositionInputs(
    val holding: PortfolioHolding,
    val movements: List<Movement>,
    val history: List<PricePoint>,
    val fxHistory: FxHistory,
    val rates: FxRates,
)

/**
 * The own returns of a position: its [card] without the chart, and [series], the EUR value of its units on each price
 * day since its first movement as price points (euros as the price), from which the chart is drawn.
 */
internal class OwnPositionAnalysis(val card: MyPositionUi, val series: List<PricePoint>)

/** `null` for a holding without movements (including a manual one) and for a history that cannot be replayed. */
internal fun analyzeOwnPosition(inputs: OwnPositionInputs, today: LocalDate): OwnPositionAnalysis? {
    val holding = inputs.holding
    if (holding.valuation !is HoldingValuation.Quoted) return null
    val own = inputs.movements.filter { it.holdingId == holding.id }
    if (own.isEmpty()) return null
    return try {
        val ledger = computeLedgers(inputs.movements)[holding.id] ?: return null
        val eurPrices = toEurSeries(inputs.history, inputs.fxHistory)
        val returns = returnsOf(holding, own, ledger, eurPrices, inputs.rates, today)
        OwnPositionAnalysis(myPositionCard(returns, own), valueSeriesEur(ledger, eurPrices))
    } catch (invalid: MovementValidationException) {
        null
    } catch (overflow: ArithmeticException) {
        null
    }
}

/** The card with the chart of the value of my units over [range], when there are two prices to draw. */
internal fun OwnPositionAnalysis.toUi(range: HistoryRange, today: LocalDate): MyPositionUi {
    if (series.size < MIN_CHART_POINTS) return card
    val (ranges, chart) = priceChartUi(series, range, today)
    val latest = Money(series.last().price.scaled / CENT_TO_PRICE_SCALE)
    return card.copy(ranges = ranges, chart = chart.copy(changeText = formatEur(latest), changeTone = card.chartTone))
}

/** The value of the units held on each day of [eurPrices] since the first movement, in EUR. */
private fun valueSeriesEur(ledger: PositionLedger, eurPrices: List<PricePoint>): List<PricePoint> =
    ledger.valueSeries(eurPrices).map {
        PricePoint(it.date, Price(it.value.amount.minorUnits * CENT_TO_PRICE_SCALE, Currency.EUR))
    }

/**
 * The data of the own returns of a scope: its [holdings], every movement of the app, the EUR price history of each
 * holding by id ([eurSeries]) and today's [rates].
 */
internal data class YourReturnsInputs(
    val holdings: List<PortfolioHolding>,
    val movements: List<Movement>,
    val eurSeries: Map<String, List<PricePoint>>,
    val rates: FxRates,
)

/** `null` when no holding of the scope has movements, or when the history cannot be replayed. */
internal fun analyzeYourReturns(inputs: YourReturnsInputs, today: LocalDate): YourReturnsUi? {
    if (inputs.movements.none { move -> inputs.holdings.any { it.id == move.holdingId } }) return null
    return try {
        val ledgers = computeLedgers(inputs.movements)
        val byHolding = inputs.movements.groupBy { it.holdingId }
        val positions = inputs.holdings.mapNotNull { holding ->
            val ledger = ledgers[holding.id] ?: return@mapNotNull null
            val prices = inputs.eurSeries[holding.id].orEmpty()
            returnsOf(holding, byHolding.getValue(holding.id), ledger, prices, inputs.rates, today)
        }
        yourReturnsUi(scopeReturns(inputs.holdings.size, positions))
    } catch (invalid: MovementValidationException) {
        null
    } catch (overflow: ArithmeticException) {
        null
    }
}

private fun returnsOf(
    holding: PortfolioHolding,
    own: List<Movement>,
    ledger: PositionLedger,
    eurPrices: List<PricePoint>,
    rates: FxRates,
    today: LocalDate,
): PositionReturns = positionReturns(
    movements = own,
    ledger = ledger,
    today = today,
    valueEur = holding.value()?.toEur(rates),
    transferValueEur = transferMarketValue(eurPrices),
)
