package com.denebapps.patrimonio.data.db

import com.denebapps.patrimonio.data.db.entity.FxHistoryEntity
import com.denebapps.patrimonio.data.db.entity.FxHistorySyncEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceEntity
import com.denebapps.patrimonio.data.db.entity.InstrumentPriceSyncEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.testing.TEST_PORTFOLIO_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RoomLocalDataChangesTest {
    private suspend fun AtomicInteger.awaitAtLeast(expected: Int) = withTimeout(TIMEOUT_MS) {
        while (get() < expected) delay(POLL_MS)
    }

    @Test
    fun `the re-downloadable caches never signal a backup change but a holding does`() = runBlocking {
        val db = buildInMemoryTestDatabase()
        db.insertTestPortfolio()
        val signals = AtomicInteger()
        val collector =
            launch(Dispatchers.Default) { RoomLocalDataChanges(db).observe().collect { signals.incrementAndGet() } }
        signals.awaitAtLeast(1)

        db.instrumentPriceDao().upsertAll(listOf(InstrumentPriceEntity("MORNINGSTAR", "X", 20_000L, 5L, "EUR")))
        db.instrumentPriceDao().upsertSync(InstrumentPriceSyncEntity("MORNINGSTAR", "X", 1L))
        db.fxHistoryDao().upsertAll(listOf(FxHistoryEntity("USD", 20_000L, 920_000L)))
        db.fxHistoryDao().upsertSync(FxHistorySyncEntity("USD", 20_000L, 1L))
        delay(QUIET_MS)
        assertEquals(1, signals.get(), "a cache write was reported as a data change")

        db.portfolioHoldingDao().insert(
            PortfolioHoldingEntity(
                "h", TEST_PORTFOLIO_ID, "Cuadro", "OTHER", null, null, "MANUAL", null, null, "EUR",
                null, null, null, null, 250_000, 1L,
            ),
        )
        signals.awaitAtLeast(2)

        collector.cancel()
        db.close()
    }

    @Test
    fun `a portfolio being created renamed or deleted signals a backup change`() = runBlocking {
        val db = buildInMemoryTestDatabase()
        val signals = AtomicInteger()
        val collector =
            launch(Dispatchers.Default) { RoomLocalDataChanges(db).observe().collect { signals.incrementAndGet() } }
        signals.awaitAtLeast(1)

        db.portfolioDao().insert(PortfolioEntity("pf", "Hijos", 0))
        signals.awaitAtLeast(2)
        db.portfolioDao().updateName("pf", "Jubilación")
        signals.awaitAtLeast(3)
        db.portfolioDao().delete("pf")
        signals.awaitAtLeast(4)

        collector.cancel()
        db.close()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val QUIET_MS = 500L
    }
}
