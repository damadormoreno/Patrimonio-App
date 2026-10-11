package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
import com.denebapps.patrimonio.domain.repository.InstrumentPreviewLoader
import com.denebapps.patrimonio.domain.repository.PortfolioScopeSeries
import com.denebapps.patrimonio.domain.repository.PortfolioSeriesLoader
import kotlinx.coroutines.CompletableDeferred

/**
 * Scriptable [InstrumentPreviewLoader]: [data] is what every load answers, [failure] makes it throw, and [gate] holds a
 * load open until completed. Every requested candidate is recorded in [calls].
 */
class FakeInstrumentPreviewLoader : InstrumentPreviewLoader {
    var data: InstrumentPreviewData? = null
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<InstrumentCandidate>()

    override suspend fun load(candidate: InstrumentCandidate): InstrumentPreviewData {
        calls += candidate
        gate?.await()
        failure?.let { throw it }
        return checkNotNull(data) { "No preview scripted" }
    }
}

/** Scriptable [PortfolioSeriesLoader]: [scope] answers every load and the requested portfolio ids are in [requests]. */
class FakePortfolioSeriesLoader(
    var scope: PortfolioScopeSeries = PortfolioScopeSeries(emptyList(), emptyMap(), FxRates(emptyMap())),
) : PortfolioSeriesLoader {
    val requests = mutableListOf<String?>()

    override suspend fun load(portfolioId: String?): PortfolioScopeSeries {
        requests += portfolioId
        return scope
    }
}
