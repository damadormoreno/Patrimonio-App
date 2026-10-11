package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.domain.calc.toEurSeries
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.DatedFxRate
import com.denebapps.patrimonio.domain.model.FxHistory
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.repository.HistoryUnavailableException
import com.denebapps.patrimonio.domain.repository.PreviewFailure
import com.denebapps.patrimonio.domain.repository.PreviewUnavailableException
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakePriceHistorySource
import com.denebapps.patrimonio.testing.FxSyncCall
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class InstrumentPreviewLoaderTest {
    private val source = FakePriceHistorySource()
    private val fxHistory = FakeFxHistoryRepository()
    private val loader = InstrumentPreviewLoaderImpl(source, fxHistory)

    private val fundRef = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6")
    private val etfRef = InstrumentRef(QuoteProvider.YAHOO, "SPY")
    private val fundamentals = InstrumentFundamentals(ongoingChargePercentScaled = 1_800L, riskLevel = 5)

    private fun candidate(ref: InstrumentRef, currency: Currency?, fundamentals: InstrumentFundamentals? = null) =
        InstrumentCandidate(
            ref = ref,
            name = "Candidate ${ref.code}",
            isin = null,
            ticker = null,
            type = InstrumentType.FUND,
            currency = currency,
            exchange = null,
            fundamentals = fundamentals,
        )

    private fun history(currency: Currency, vararg prices: Double): List<PricePoint> =
        prices.mapIndexed { index, price ->
            PricePoint(LocalDate(2026, 10, 5 + index), Price.fromDouble(price, currency))
        }

    private val usdRates = FxHistory(
        mapOf(Currency.USD to (5..9).map { DatedFxRate(LocalDate(2026, 10, it), 900_000L) }),
    )

    @Test
    fun `a Morningstar fund in EUR is downloaded whole and needs no exchange rates`() = runTest {
        val points = history(Currency.EUR, 100.0, 101.0, 102.5)
        source.series[fundRef] = points

        val preview = loader.load(candidate(fundRef, Currency.EUR, fundamentals))

        assertEquals(listOf<Pair<InstrumentRef, LocalDate?>>(fundRef to null), source.calls)
        assertEquals(Currency.EUR, preview.currency)
        assertEquals(points, preview.history)
        assertEquals(points, preview.eurHistory)
        assertEquals(fundamentals, preview.fundamentals)
        assertEquals(emptyList(), fxHistory.syncCalls)
    }

    @Test
    fun `a Yahoo candidate without currency takes it from the prices it is quoted in`() = runTest {
        val points = history(Currency.USD, 100.0, 110.0, 120.0)
        source.series[etfRef] = points
        fxHistory.set(usdRates)

        val preview = loader.load(candidate(etfRef, currency = null))

        assertEquals(Currency.USD, preview.currency)
        assertNull(preview.fundamentals)
        assertEquals(points, preview.history)
        assertEquals(toEurSeries(points, usdRates), preview.eurHistory)
    }

    @Test
    fun `a foreign candidate syncs the exchange rates from its first day, without forcing`() = runTest {
        source.series[etfRef] = history(Currency.USD, 100.0, 110.0)
        fxHistory.set(usdRates)

        val preview = loader.load(candidate(etfRef, Currency.USD))

        assertEquals(
            listOf(FxSyncCall(setOf(Currency.USD), LocalDate(2026, 10, 5), force = false)),
            fxHistory.syncCalls,
        )
        assertEquals(listOf(90.0, 99.0), preview.eurHistory.map { it.price.scaled / 1_000_000.0 })
        assertEquals(listOf(LocalDate(2026, 10, 5), LocalDate(2026, 10, 6)), preview.eurHistory.map { it.date })
    }

    @Test
    fun `the candidate's own fundamentals travel with the preview`() = runTest {
        source.series[fundRef] = history(Currency.EUR, 100.0, 101.0)

        val preview = loader.load(candidate(fundRef, Currency.EUR, fundamentals))

        assertSame(fundamentals, preview.fundamentals)
        assertEquals(fundRef, preview.candidate.ref)
    }

    @Test
    fun `a provider without prices is reported as no data`() = runTest {
        source.series[fundRef] = emptyList()

        val failure = assertFailsWith<PreviewUnavailableException> { loader.load(candidate(fundRef, Currency.EUR)) }

        assertEquals(PreviewFailure.NO_DATA, failure.failure)
    }

    @Test
    fun `a failed download is reported as unavailable and keeps its cause`() = runTest {
        val cause = QuoteUnavailableException("HTTP 503")
        source.failures[fundRef] = cause

        val failure = assertFailsWith<PreviewUnavailableException> { loader.load(candidate(fundRef, Currency.EUR)) }

        assertEquals(PreviewFailure.UNAVAILABLE, failure.failure)
        assertSame(cause, failure.cause)
    }

    @Test
    fun `failing exchange rates make the preview unavailable`() = runTest {
        source.series[etfRef] = history(Currency.USD, 100.0, 110.0)
        fxHistory.syncFailure = HistoryUnavailableException("frankfurter down")

        val failure = assertFailsWith<PreviewUnavailableException> { loader.load(candidate(etfRef, Currency.USD)) }

        assertEquals(PreviewFailure.UNAVAILABLE, failure.failure)
    }

    @Test
    fun `cancellation is not turned into a preview failure`() = runTest {
        source.failures[fundRef] = CancellationException("scope cancelled")

        assertFailsWith<CancellationException> { loader.load(candidate(fundRef, Currency.EUR)) }
    }
}
