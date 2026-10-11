package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.buildInMemoryTestDatabase
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.PricePoint
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.testing.FakeFxHistoryRepository
import com.denebapps.patrimonio.testing.FakePriceHistorySource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The preview downloads a history to look at it: the price cache of the portfolio must stay as it was. */
@RunWith(RobolectricTestRunner::class)
class InstrumentPreviewLoaderPersistenceTest {
    private lateinit var db: AppDatabase
    private val source = FakePriceHistorySource()
    private val ref = InstrumentRef(QuoteProvider.MORNINGSTAR, "0P00012PP6")

    @Before
    fun setUp() {
        db = buildInMemoryTestDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `previewing a product writes nothing to the price history cache`() = runTest {
        source.series[ref] = listOf(
            PricePoint(LocalDate(2026, 10, 5), Price.fromDouble(100.0, Currency.EUR)),
            PricePoint(LocalDate(2026, 10, 6), Price.fromDouble(101.0, Currency.EUR)),
        )
        val candidate = InstrumentCandidate(
            ref = ref,
            name = "Vanguard Global Stock",
            isin = null,
            ticker = null,
            type = InstrumentType.FUND,
            currency = Currency.EUR,
            exchange = null,
        )
        val history = InstrumentHistoryRepositoryImpl(db, db.instrumentPriceDao(), source, Clock.System)

        val preview = InstrumentPreviewLoaderImpl(source, FakeFxHistoryRepository()).load(candidate)

        assertEquals(2, preview.history.size)
        assertEquals(0, db.instrumentPriceDao().count())
        assertNull(db.instrumentPriceDao().findSync(ref.provider.name, ref.code))
        assertEquals(emptyList(), history.observe(ref).first())
    }
}
