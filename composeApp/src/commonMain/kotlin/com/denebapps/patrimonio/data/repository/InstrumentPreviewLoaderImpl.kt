package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.domain.calc.toEurSeries
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentPreviewData
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import com.denebapps.patrimonio.domain.repository.InstrumentPreviewLoader
import com.denebapps.patrimonio.domain.repository.PreviewFailure
import com.denebapps.patrimonio.domain.repository.PreviewUnavailableException
import com.denebapps.patrimonio.domain.repository.PriceHistorySource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * [InstrumentPreviewLoader] over the [PriceHistorySource] port. It depends on no price cache, so the downloaded history
 * cannot be stored by accident. The currency of the product is the one its prices are quoted in, which the history
 * itself carries (a Yahoo search result has none until it is priced), so no separate quote request is needed. The
 * exchange rates of a foreign currency are brought up to date from the product's first day (an already fresh cache is
 * left alone) and read from [FxHistoryRepository].
 */
class InstrumentPreviewLoaderImpl(
    private val source: PriceHistorySource,
    private val fxHistory: FxHistoryRepository,
) : InstrumentPreviewLoader {
    override suspend fun load(candidate: InstrumentCandidate): InstrumentPreviewData {
        val history = download(candidate)
        val currency = history.first().price.currency
        val fx = if (currency == Currency.EUR) FxHistory(emptyMap()) else rates(currency, history.first().date)
        return InstrumentPreviewData(
            candidate = candidate,
            currency = currency,
            history = history,
            eurHistory = toEurSeries(history, fx),
            fundamentals = candidate.fundamentals,
        )
    }

    private suspend fun download(candidate: InstrumentCandidate): List<PricePoint> {
        val history = try {
            source.priceHistory(candidate.ref, from = null)
        } catch (failure: QuoteUnavailableException) {
            throw PreviewUnavailableException(
                PreviewFailure.UNAVAILABLE,
                "The price history of ${candidate.ref.code} could not be downloaded",
                failure,
            )
        }
        if (history.isEmpty()) {
            throw PreviewUnavailableException(PreviewFailure.NO_DATA, "${candidate.ref.code} has no price history")
        }
        return history
    }

    private suspend fun rates(currency: Currency, from: LocalDate): FxHistory {
        try {
            fxHistory.sync(setOf(currency), from, force = false)
        } catch (failure: HistoryUnavailableException) {
            throw PreviewUnavailableException(
                PreviewFailure.UNAVAILABLE,
                "The ${currency.code} exchange rates could not be downloaded",
                failure,
            )
        }
        return fxHistory.observe().first()
    }
}
