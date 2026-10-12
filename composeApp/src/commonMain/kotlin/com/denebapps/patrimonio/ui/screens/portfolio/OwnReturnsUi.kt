package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.CostBasis
import com.denebapps.patrimonio.domain.calc.GainBreakdown
import com.denebapps.patrimonio.domain.calc.PositionReturns
import com.denebapps.patrimonio.domain.calc.ScopeReturns
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.ui.components.formatEur

// What the user earned with their own purchases, as texts (es-ES, neutral Spanish): the "Mi posición" card of a
// position and the "Tu rentabilidad" block of a portfolio. Pure; the figures come from `OwnReturns`.

internal const val MY_POSITION_TITLE = "Mi posición"
internal const val MY_POSITION_SUBTITLE = "Tus compras, ventas y traspasos; no la rentabilidad del producto."
internal const val MY_POSITION_CHART_TITLE = "Evolución de mi posición"
internal const val MY_POSITION_CHART_LABEL = "VALOR DE TU POSICIÓN"
internal const val YOUR_RETURNS_TITLE = "Tu rentabilidad"
internal const val YOUR_RETURNS_SUBTITLE = "Sobre tus compras, ventas y traspasos; no la rentabilidad de los productos."
internal const val LOTS_TITLE = "Lotes abiertos"
internal const val LOTS_TRANSFER_NOTE = "Los lotes recibidos por traspaso conservan su fecha de compra original."
internal const val UNKNOWN_COST_HINT = "Añade el coste del saldo inicial para calcularla."

private const val COST_LABEL = "Aportado"
private const val PARTIAL_COST_LABEL = "Aportado (parte conocida)"
private const val VALUE_LABEL = "Valor actual"
private const val UNREALIZED_LABEL = "Plusvalía latente"
private const val REALIZED_LABEL = "Plusvalía realizada"
private const val DIVIDENDS_LABEL = "Dividendos cobrados"
private const val FEES_LABEL = "Comisiones"
private const val XIRR_LABEL = "Rentabilidad anual (XIRR)"

private const val GAIN_RATIO_DECIMALS = 1
private const val MINUS = "−"

/**
 * One line of the own-returns card: its [label], the figure ([valueText], "—" when it cannot be given) with its
 * [tone] and, below, what qualifies it ([detailText]).
 */
data class OwnFigureUi(
    val label: String,
    val valueText: String,
    val tone: PerformanceTone = PerformanceTone.NEUTRAL,
    val detailText: String? = null,
)

/** An open FIFO lot: the day it was bought, its units and what it cost ("coste desconocido" when not known). */
data class LotUi(val dateText: String, val unitsText: String, val costText: String)

/**
 * "Mi posición": the [figures], the [hint] that says how to complete an unknown cost, the open [lots] (with a
 * [lotsNote] when some came in a transfer) and the chart of the value of my units ([ranges] and [chart], absent
 * without two prices since the first movement). [chartTone] colors the chart: the sign of the unrealized gain.
 */
data class MyPositionUi(
    val figures: List<OwnFigureUi>,
    val hint: String?,
    val lots: List<LotUi>,
    val lotsNote: String?,
    val ranges: List<RangeChipUi> = emptyList(),
    val chart: ChartUi? = null,
    val chartTone: PerformanceTone = PerformanceTone.NEUTRAL,
)

/** "Tu rentabilidad": the [figures] of a scope and [coverageText], which says how many products they cover. */
data class YourReturnsUi(val figures: List<OwnFigureUi>, val coverageText: String)

// ---- one position -----------------------------------------------------------------------------------------

/** The card of a position; [movements] are its own (they decide whether lots arrived by transfer). */
internal fun myPositionCard(returns: PositionReturns, movements: List<Movement>): MyPositionUi {
    val ledger = returns.ledger
    val open = !returns.isClosed
    val figures = buildList {
        if (open) add(costFigure(ledger.costBasis))
        add(OwnFigureUi(VALUE_LABEL, returns.valueEur?.let(::formatEur) ?: NO_PRICE_TEXT))
        if (open) add(gainFigure(UNREALIZED_LABEL, returns.unrealized, showRatio = true))
        add(gainFigure(REALIZED_LABEL, ledger.realized, showRatio = false))
        addIfPaid(DIVIDENDS_LABEL, ledger.dividendsEur)
        addIfPaid(FEES_LABEL, returns.feesEur)
        add(xirrFigure(returns.xirr))
    }
    val costUnknown = !ledger.costBasis.isComplete || !ledger.realized.isComplete
    return MyPositionUi(
        figures = figures,
        hint = UNKNOWN_COST_HINT.takeIf { costUnknown },
        lots = ledger.lots.map {
            LotUi(
                dateText = formatShortDate(it.acquiredOn),
                unitsText = "${formatUnits(it.units)} part.",
                costText = it.costEur?.let(::formatEur) ?: UNKNOWN_COST_TEXT,
            )
        },
        lotsNote = LOTS_TRANSFER_NOTE.takeIf { movements.any { it.type == MovementType.TRANSFER_IN } },
        chartTone = returns.unrealized?.totalGainEur?.let(::toneOfMoney) ?: PerformanceTone.NEUTRAL,
    )
}

private fun costFigure(basis: CostBasis): OwnFigureUi {
    if (basis.isComplete) return OwnFigureUi(COST_LABEL, formatEur(basis.knownCostEur))
    val detail = "Coste desconocido de ${formatUnits(basis.unknownCostUnits)} part."
    return if (basis.knownCostEur.minorUnits > 0) {
        OwnFigureUi(PARTIAL_COST_LABEL, formatEur(basis.knownCostEur), detailText = detail)
    } else {
        OwnFigureUi(COST_LABEL, NO_FIGURE_TEXT, detailText = detail)
    }
}

/**
 * A gain: its amount (and its ratio with [showRatio]) when every cost is known, otherwise "—" with the part whose cost
 * is known as a detail.
 */
private fun gainFigure(label: String, gain: GainBreakdown?, showRatio: Boolean): OwnFigureUi {
    gain ?: return OwnFigureUi(label, NO_FIGURE_TEXT)
    val ratio = gain.returnRatio.takeIf { showRatio }
    val total = gain.totalGainEur
    if (total != null) return OwnFigureUi(label, gainText(total, ratio), toneOfMoney(total))
    val partial = gain.knownCostEur.minorUnits > 0
    val detail = "Solo la parte con coste conocido: ${gainText(gain.knownGainEur, ratio)}"
    return OwnFigureUi(label, NO_FIGURE_TEXT, detailText = detail.takeIf { partial })
}

private fun xirrFigure(xirr: Double?): OwnFigureUi = if (xirr == null) {
    OwnFigureUi(XIRR_LABEL, NO_FIGURE_TEXT)
} else {
    OwnFigureUi(XIRR_LABEL, formatPercent(xirr), toneOf(xirr))
}

private fun MutableList<OwnFigureUi>.addIfPaid(label: String, amount: Money) {
    if (amount.minorUnits != 0L) add(OwnFigureUi(label, formatEur(amount)))
}

// ---- a scope ----------------------------------------------------------------------------------------------

/** The block of a scope, `null` when none of its products has movements. */
internal fun yourReturnsUi(scope: ScopeReturns): YourReturnsUi? {
    if (scope.withMovements == 0) return null
    val known = scope.covered > 0
    fun amount(money: Money, text: (Money) -> String = ::formatEur) = if (known) text(money) else NO_FIGURE_TEXT
    fun tone(money: Money) = if (known) toneOfMoney(money) else PerformanceTone.NEUTRAL
    val unrealized = scope.unrealizedGainEur
    val realized = scope.realizedGainEur
    val figures = buildList {
        add(OwnFigureUi(COST_LABEL, amount(scope.knownCostEur)))
        add(OwnFigureUi(VALUE_LABEL, amount(scope.valueEur)))
        add(OwnFigureUi(UNREALIZED_LABEL, amount(unrealized) { gainText(it, scope.unrealizedRatio) }, tone(unrealized)))
        add(OwnFigureUi(REALIZED_LABEL, amount(realized, ::signedEur), tone(realized)))
        if (known) {
            addIfPaid(DIVIDENDS_LABEL, scope.dividendsEur)
            addIfPaid(FEES_LABEL, scope.feesEur)
        }
        add(xirrFigure(scope.xirr))
    }
    return YourReturnsUi(figures, coverageText(scope))
}

/** "Calculado con 3 de 5 productos (2 sin movimientos o con coste desconocido).", or all of them when they are. */
internal fun coverageText(scope: ScopeReturns): String {
    if (scope.covered == scope.productCount) {
        val products = if (scope.productCount == 1) "1 producto" else "los ${scope.productCount} productos"
        return "Calculado con $products."
    }
    val left = scope.productCount - scope.covered
    return "Calculado con ${scope.covered} de ${countLabel(scope.productCount)} (" +
        "$left sin movimientos o con coste desconocido)."
}

// ---- numbers ----------------------------------------------------------------------------------------------

/** "+1.234,56 €" for a gain, "−15,30 €" for a loss, "0,00 €" for none. */
internal fun signedEur(money: Money): String = when {
    money.minorUnits > 0 -> "+${formatEur(money)}"
    money.minorUnits < 0 -> "$MINUS${formatEur(-money)}"
    else -> formatEur(money)
}

/** "+1.234,56 € (+8,4 %)": a gain and, when the cost it is over is known, its ratio. */
internal fun gainText(gain: Money, ratio: Double?): String {
    val percent = ratio?.let { " (${formatPercent(it, signed = true, decimals = GAIN_RATIO_DECIMALS)})" }.orEmpty()
    return signedEur(gain) + percent
}

internal fun toneOfMoney(money: Money): PerformanceTone = when {
    money.minorUnits > 0 -> PerformanceTone.GAIN
    money.minorUnits < 0 -> PerformanceTone.LOSS
    else -> PerformanceTone.NEUTRAL
}
