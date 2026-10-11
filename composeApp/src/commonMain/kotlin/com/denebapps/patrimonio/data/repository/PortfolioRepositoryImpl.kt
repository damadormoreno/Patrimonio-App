package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.PortfolioHoldingDao
import com.denebapps.patrimonio.data.db.entity.HoldingKind
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.normalizeBroker
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PriceRefreshFailure
import com.denebapps.patrimonio.domain.repository.PriceRefreshResult
import com.denebapps.patrimonio.domain.repository.QuoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate

/** A price fetched more than this long ago is refreshed on the next non-forced refresh. */
private const val STALE_WINDOW_MS = 6L * 60 * 60 * 1000

/** Quote requests in flight at once: enough to be quick, few enough to stay polite to the public endpoints. */
private const val MAX_CONCURRENT_QUOTES = 4

/**
 * [PortfolioRepository] over Room and the [QuoteSource] port. A price refresh writes only the price columns of its
 * own row, so units edited while the requests are in flight are never overwritten.
 */
class PortfolioRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val dao: PortfolioHoldingDao,
    private val quoteSource: QuoteSource,
    private val snapshotUpserter: NetWorthSnapshotUpserter,
    private val seedingGate: SeedingGate,
    private val clock: Clock,
) : PortfolioRepository {
    override fun observeAll(): Flow<List<PortfolioHolding>> = flow {
        seedingGate.await()
        emitAll(dao.observeAll().map { rows -> rows.map(::portfolioHoldingToDomain) })
    }

    override suspend fun add(holding: PortfolioHolding) {
        seedingGate.await()
        appDatabase.writeTransaction {
            dao.insert(toEntity(holding))
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun updateUnits(id: String, units: Units) {
        seedingGate.await()
        appDatabase.writeTransaction {
            val row = dao.find(id) ?: throw PortfolioHoldingNotFoundException(id)
            check(row.kind == HoldingKind.QUOTED.name) { "Holding '$id' is a manual holding and has no units" }
            dao.updateUnits(id, units.scaled)
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun updateManualValue(id: String, value: CurrencyAmount) {
        seedingGate.await()
        appDatabase.writeTransaction {
            val row = dao.find(id) ?: throw PortfolioHoldingNotFoundException(id)
            check(row.kind == HoldingKind.MANUAL.name) { "Holding '$id' is priced by a quote source, not by hand" }
            dao.updateManualValue(id, value.amount.minorUnits, value.currency.code, clock.now().toEpochMilliseconds())
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun updateBroker(id: String, broker: String?) {
        val normalized = normalizeBroker(broker)
        seedingGate.await()
        appDatabase.writeTransaction {
            if (dao.updateBroker(id, normalized) == 0) throw PortfolioHoldingNotFoundException(id)
        }
    }

    override suspend fun delete(id: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            if (dao.delete(id) == 0) throw PortfolioHoldingNotFoundException(id)
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun refreshPrices(force: Boolean): PriceRefreshResult {
        seedingGate.await()
        val now = clock.now().toEpochMilliseconds()
        val quoted = dao.list().filter { it.kind == HoldingKind.QUOTED.name }
        val due = if (force) quoted else quoted.filter { isStale(it, now) }
        val limiter = Semaphore(MAX_CONCURRENT_QUOTES)
        val failures = coroutineScope {
            due.map { row -> async { limiter.withPermit { refresh(row, now) } } }.awaitAll()
        }.filterNotNull()
        val refreshed = due.size - failures.size
        // Once per refresh, not per holding, and only when a price actually changed.
        if (refreshed > 0) appDatabase.writeTransaction { snapshotUpserter.refreshCurrentMonth() }
        return PriceRefreshResult(
            refreshed = refreshed,
            upToDate = quoted.size - due.size,
            failures = failures,
        )
    }

    /** @return `null` when the price was stored, the failure otherwise (the previous price stays untouched). */
    private suspend fun refresh(row: PortfolioHoldingEntity, now: Long): PriceRefreshFailure? {
        val quote = try {
            quoteSource.latestQuote(instrumentRef(row))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return failed(row, error.message ?: error::class.simpleName.orEmpty(), error)
        }
        // A holding never changes currency under the user: its units and any later total are in the original one.
        if (quote.price.currency.code != row.currency) {
            return failed(row, "quoted in ${quote.price.currency.code}, the holding is in ${row.currency}")
        }
        dao.updatePrice(row.id, quote.price.scaled, quote.date.toEpochDays().toLong(), now)
        return null
    }

    private fun failed(row: PortfolioHoldingEntity, reason: String, cause: Throwable? = null): PriceRefreshFailure {
        AppLogger.error("PortfolioRepository", "price refresh of '${row.name}' failed: $reason", cause)
        return PriceRefreshFailure(row.id, row.name, reason)
    }
}

private fun instrumentRef(row: PortfolioHoldingEntity) =
    InstrumentRef(QuoteProvider.valueOf(row.provider.orEmpty()), row.providerCode.orEmpty())

/** Never fetched, fetched too long ago, or "fetched in the future" (the device clock went backwards). */
private fun isStale(row: PortfolioHoldingEntity, now: Long): Boolean {
    val fetchedAt = row.priceFetchedAtEpochMs ?: return true
    val age = now - fetchedAt
    return age < 0 || age > STALE_WINDOW_MS
}

private fun toEntity(holding: PortfolioHolding): PortfolioHoldingEntity = when (val valuation = holding.valuation) {
    is HoldingValuation.Quoted -> PortfolioHoldingEntity(
        id = holding.id,
        name = holding.name,
        type = holding.type.name,
        isin = holding.isin,
        ticker = holding.ticker,
        kind = HoldingKind.QUOTED.name,
        provider = valuation.ref.provider.name,
        providerCode = valuation.ref.code,
        currency = valuation.currency.code,
        unitsScaled = valuation.units.scaled,
        priceScaled = valuation.lastQuote?.price?.scaled,
        priceDateEpochDay = valuation.lastQuote?.date?.toEpochDays()?.toLong(),
        priceFetchedAtEpochMs = valuation.lastQuote?.fetchedAtEpochMs,
        valueMinor = null,
        valuedAtEpochMs = null,
        broker = holding.broker,
    )
    is HoldingValuation.Manual -> PortfolioHoldingEntity(
        id = holding.id,
        name = holding.name,
        type = holding.type.name,
        isin = holding.isin,
        ticker = holding.ticker,
        kind = HoldingKind.MANUAL.name,
        provider = null,
        providerCode = null,
        currency = valuation.value.currency.code,
        unitsScaled = null,
        priceScaled = null,
        priceDateEpochDay = null,
        priceFetchedAtEpochMs = null,
        valueMinor = valuation.value.amount.minorUnits,
        valuedAtEpochMs = valuation.valuedAtEpochMs,
        broker = holding.broker,
    )
}

internal fun portfolioHoldingToDomain(row: PortfolioHoldingEntity): PortfolioHolding {
    val currency = Currency.valueOf(row.currency)
    val valuation = when (HoldingKind.valueOf(row.kind)) {
        HoldingKind.QUOTED -> HoldingValuation.Quoted(
            ref = instrumentRef(row),
            currency = currency,
            units = Units(checkNotNull(row.unitsScaled) { "quoted holding '${row.id}' has no units" }),
            lastQuote = row.priceScaled?.let { priceScaled ->
                LastQuote(
                    price = Price(priceScaled, currency),
                    date = LocalDate.fromEpochDays(checkNotNull(row.priceDateEpochDay).toInt()),
                    fetchedAtEpochMs = checkNotNull(row.priceFetchedAtEpochMs),
                )
            },
        )
        HoldingKind.MANUAL -> HoldingValuation.Manual(
            value = CurrencyAmount(
                Money(checkNotNull(row.valueMinor) { "manual holding '${row.id}' has no value" }),
                currency,
            ),
            valuedAtEpochMs = checkNotNull(row.valuedAtEpochMs),
        )
    }
    return PortfolioHolding(
        id = row.id,
        name = row.name,
        type = InstrumentType.valueOf(row.type),
        isin = row.isin,
        ticker = row.ticker,
        valuation = valuation,
        broker = row.broker,
    )
}
