package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.PortfolioHoldingDao
import com.denebapps.patrimonio.data.db.dao.pruneUnreferenced
import com.denebapps.patrimonio.data.db.entity.HoldingKind
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.entity.SavingsGoalLinkEventEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentFundamentals
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.normalizeBroker
import com.denebapps.patrimonio.domain.repository.FundamentalsSource
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncFailure
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncResult
import com.denebapps.patrimonio.domain.repository.HoldingHasMovementsException
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
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

/** The fundamentals of a holding are asked at most once in this long, unless forced. */
private const val FUNDAMENTALS_WINDOW_MS = 24L * 60 * 60 * 1000

/** Quote requests in flight at once: enough to be quick, few enough to stay polite to the public endpoints. */
private const val MAX_CONCURRENT_QUOTES = 4

/**
 * [PortfolioRepository] over Room and the [QuoteSource] and [FundamentalsSource] ports. Adding a holding checks that
 * its portfolio exists (the foreign key would refuse it anyway, with an opaque error). A price refresh writes only the
 * price columns of its own row, and a fundamentals sync only the fundamentals columns of its own, so units edited
 * while the requests are in flight are never overwritten.
 */
class PortfolioRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val dao: PortfolioHoldingDao,
    private val quoteSource: QuoteSource,
    private val snapshotUpserter: NetWorthSnapshotUpserter,
    private val seedingGate: SeedingGate,
    private val clock: Clock,
    private val fundamentalsSource: FundamentalsSource,
) : PortfolioRepository {
    override fun observeAll(): Flow<List<PortfolioHolding>> = flow {
        seedingGate.await()
        emitAll(dao.observeAll().map { rows -> rows.map(::portfolioHoldingToDomain) })
    }

    override suspend fun add(holding: PortfolioHolding) {
        seedingGate.await()
        appDatabase.writeTransaction {
            if (appDatabase.portfolioDao().find(holding.portfolioId) == null) {
                throw PortfolioNotFoundException(holding.portfolioId)
            }
            dao.insert(portfolioHoldingToEntity(holding, clock.now().toEpochMilliseconds()))
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    override suspend fun updateUnits(id: String, units: Units) {
        seedingGate.await()
        appDatabase.writeTransaction {
            val row = dao.find(id) ?: throw PortfolioHoldingNotFoundException(id)
            check(row.kind == HoldingKind.QUOTED.name) { "Holding '$id' is a manual holding and has no units" }
            if (appDatabase.movementDao().countForHolding(id) > 0) throw HoldingHasMovementsException(id)
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

    override suspend fun updateAssetClass(id: String, assetClass: AssetClass?) {
        seedingGate.await()
        appDatabase.writeTransaction {
            if (dao.updateAssetClass(id, assetClass?.name) == 0) throw PortfolioHoldingNotFoundException(id)
        }
    }

    override suspend fun delete(id: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            // Its own movements go with it (cascade); a transfer would leave the other holding with a dangling leg.
            if (appDatabase.movementDao().countTransferLegsWithOtherHoldings(id) > 0) {
                throw HoldingHasTransfersException(id)
            }
            unlinkGoalsFrom(id)
            if (dao.delete(id) == 0) throw PortfolioHoldingNotFoundException(id)
            // The cached price history of a product nobody holds any more is useless.
            appDatabase.instrumentPriceDao().pruneUnreferenced()
            snapshotUpserter.refreshCurrentMonth()
        }
    }

    /** Goals that follow the product directly leave a `HOLDING_DELETED` event and lose the link before it goes (the
     *  foreign key would drop the row anyway, silently). Goals that follow a group holding it are untouched. */
    private suspend fun unlinkGoalsFrom(holdingId: String) {
        val goals = appDatabase.savingsGoalDao()
        val linkedGoals = goals.listGoalsLinkedToHolding(holdingId)
        if (linkedGoals.isEmpty()) return
        val timestampEpochMs = clock.now().toEpochMilliseconds()
        linkedGoals.forEach { goal ->
            goals.insertLinkEvent(
                SavingsGoalLinkEventEntity(
                    goalId = goal.id,
                    fromAssetId = null,
                    toAssetId = null,
                    kind = SavingsGoalLinkEventKind.HOLDING_DELETED.name,
                    timestampEpochMs = timestampEpochMs,
                    fromHoldingId = holdingId,
                ),
            )
        }
        check(goals.clearLinkedHolding(holdingId) == linkedGoals.size) {
            "Holding '$holdingId' links changed during deletion"
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

    override suspend fun syncFundamentals(holdingId: String?, force: Boolean): FundamentalsSyncResult {
        seedingGate.await()
        val now = clock.now().toEpochMilliseconds()
        val rows = if (holdingId == null) {
            dao.list()
        } else {
            listOf(dao.find(holdingId) ?: throw PortfolioHoldingNotFoundException(holdingId))
        }
        val eligible = rows.filter { it.kind == HoldingKind.QUOTED.name && !it.isin.isNullOrBlank() }
        val due = if (force) eligible else eligible.filter { fundamentalsAreDue(it, now) }
        val limiter = Semaphore(MAX_CONCURRENT_QUOTES)
        val outcomes = coroutineScope {
            due.map { row -> async { limiter.withPermit { fetchFundamentals(row, now) } } }.awaitAll()
        }
        return FundamentalsSyncResult(
            updated = outcomes.count { it == FundamentalsOutcome.Updated },
            upToDate = eligible.size - due.size,
            failures = outcomes.filterIsInstance<FundamentalsOutcome.Failed>().map { it.failure },
        )
    }

    private sealed interface FundamentalsOutcome {
        data object Updated : FundamentalsOutcome

        data object NothingKnown : FundamentalsOutcome

        data class Failed(val failure: FundamentalsSyncFailure) : FundamentalsOutcome
    }

    private suspend fun fetchFundamentals(row: PortfolioHoldingEntity, now: Long): FundamentalsOutcome {
        val fundamentals = try {
            fundamentalsSource.fundamentals(row.isin.orEmpty(), instrumentRefOrNull(row))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val reason = error.message ?: error::class.simpleName.orEmpty()
            AppLogger.error("PortfolioRepository", "fundamentals of '${row.name}' failed: $reason", error)
            return FundamentalsOutcome.Failed(FundamentalsSyncFailure(row.id, row.name, reason))
        }
        // A source that knows nothing leaves what is stored (a gap never erases data) but counts as asked.
        if (fundamentals == null) {
            dao.touchFundamentals(row.id, now)
            return FundamentalsOutcome.NothingKnown
        }
        dao.updateFundamentals(
            id = row.id,
            ongoingChargePercentScaled = fundamentals.ongoingChargePercentScaled,
            riskLevel = fundamentals.riskLevel,
            category = fundamentals.category,
            managementCompany = fundamentals.managementCompany,
            fetchedAtEpochMs = now,
        )
        return FundamentalsOutcome.Updated
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

/** The instrument a quoted row is priced as, or `null` for a row without a provider. */
private fun instrumentRefOrNull(row: PortfolioHoldingEntity): InstrumentRef? =
    if (row.provider == null || row.providerCode == null) null else instrumentRef(row)

/** Never asked, asked a day or more ago, or "asked in the future" (the device clock went backwards). */
private fun fundamentalsAreDue(row: PortfolioHoldingEntity, now: Long): Boolean {
    val askedAt = row.fundamentalsFetchedAtEpochMs ?: return true
    return now - askedAt !in 0 until FUNDAMENTALS_WINDOW_MS
}

/** Never fetched, fetched too long ago, or "fetched in the future" (the device clock went backwards). */
private fun isStale(row: PortfolioHoldingEntity, now: Long): Boolean {
    val fetchedAt = row.priceFetchedAtEpochMs ?: return true
    val age = now - fetchedAt
    return age < 0 || age > STALE_WINDOW_MS
}

/** [nowMs] stamps the fundamentals of a holding that arrives with them, so they are not asked again right away. */
internal fun portfolioHoldingToEntity(holding: PortfolioHolding, nowMs: Long): PortfolioHoldingEntity = when (
    val valuation = holding.valuation
) {
    is HoldingValuation.Quoted -> PortfolioHoldingEntity(
        id = holding.id,
        portfolioId = holding.portfolioId,
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
        ongoingChargePercentScaled = holding.fundamentals?.ongoingChargePercentScaled,
        riskLevel = holding.fundamentals?.riskLevel,
        category = holding.fundamentals?.category,
        managementCompany = holding.fundamentals?.managementCompany,
        fundamentalsFetchedAtEpochMs = nowMs.takeIf { holding.fundamentals != null },
        assetClass = holding.assetClass?.name,
    )
    is HoldingValuation.Manual -> PortfolioHoldingEntity(
        id = holding.id,
        portfolioId = holding.portfolioId,
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
        ongoingChargePercentScaled = holding.fundamentals?.ongoingChargePercentScaled,
        riskLevel = holding.fundamentals?.riskLevel,
        category = holding.fundamentals?.category,
        managementCompany = holding.fundamentals?.managementCompany,
        fundamentalsFetchedAtEpochMs = nowMs.takeIf { holding.fundamentals != null },
        assetClass = holding.assetClass?.name,
    )
}

internal fun portfolioHoldingToDomain(row: PortfolioHoldingEntity): PortfolioHolding {
    val currency = Currency.valueOf(row.currency)
    val valuation = when (HoldingKind.valueOf(row.kind)) {
        HoldingKind.QUOTED -> HoldingValuation.Quoted(
            ref = instrumentRef(row),
            currency = currency,
            units = UnitsBalance(checkNotNull(row.unitsScaled) { "quoted holding '${row.id}' has no units" }),
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
        portfolioId = row.portfolioId,
        name = row.name,
        type = InstrumentType.valueOf(row.type),
        isin = row.isin,
        ticker = row.ticker,
        valuation = valuation,
        broker = row.broker,
        fundamentals = InstrumentFundamentals(
            ongoingChargePercentScaled = row.ongoingChargePercentScaled,
            riskLevel = row.riskLevel,
            category = row.category,
            managementCompany = row.managementCompany,
        ).takeUnless { it.isEmpty },
        assetClass = row.assetClass?.let(AssetClass::valueOf),
    )
}
