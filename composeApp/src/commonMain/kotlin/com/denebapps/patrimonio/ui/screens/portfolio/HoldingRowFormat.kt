package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import kotlinx.datetime.TimeZone

// The muted second line of a row of the Cartera list, as pure functions so the ViewModel only wires them.

/** What a position that sold everything reads instead of its units. */
internal const val CLOSED_TEXT = "Cerrada"

private const val META_SEPARATOR = " · "

/**
 * "Fondo · Renta variable · 12,34 part. · MyInvestor": the type and the class, then [tail] (what the position holds),
 * then the bank or broker and the portfolio — each one only when there is one. What the position holds goes before
 * the broker and the portfolio so a long line cuts those first. Blank parts are left out.
 */
internal fun holdingMetaLine(
    typeLabel: String,
    assetClassText: String?,
    broker: String?,
    portfolioName: String?,
    tail: String,
): String = listOf(typeLabel, assetClassText, tail, broker, portfolioName)
    .filter { !it.isNullOrBlank() }
    .joinToString(META_SEPARATOR)

/** "12,345678 part." for a quoted holding (the units it holds), "Valor manual · 10/10/2026" for a hand-valued one. */
internal fun holdingRowTail(holding: PortfolioHolding, zone: TimeZone): String =
    when (val valuation = holding.valuation) {
        is HoldingValuation.Quoted -> "${formatUnits(valuation.units)} part."
        is HoldingValuation.Manual -> "Valor manual · ${formatShortDate(dayOf(valuation.valuedAtEpochMs, zone))}"
    }
