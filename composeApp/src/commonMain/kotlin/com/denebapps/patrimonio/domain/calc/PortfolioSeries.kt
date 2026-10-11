package com.denebapps.patrimonio.domain.calc

import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PricePoint

// Small pieces shared by every screen that reads the price histories of a set of holdings.

/** The instrument of every quoted holding, by holding id. */
fun List<PortfolioHolding>.quotedRefs(): Map<String, InstrumentRef> =
    mapNotNull { holding -> (holding.valuation as? HoldingValuation.Quoted)?.let { holding.id to it.ref } }.toMap()

/** Each history of [histories] (by holding id) converted to EUR with [fx]; see [toEurSeries]. */
fun eurSeriesById(histories: Map<String, List<PricePoint>>, fx: FxHistory): Map<String, List<PricePoint>> =
    histories.mapValues { (_, points) -> toEurSeries(points, fx) }
