package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.entity.HoldingKind
import com.denebapps.patrimonio.data.db.entity.MovementEntity
import com.denebapps.patrimonio.data.db.entity.PortfolioHoldingEntity
import com.denebapps.patrimonio.data.db.writeTransaction
import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.inProcessingOrder
import com.denebapps.patrimonio.domain.repository.InvalidMovementException
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.domain.repository.MovementNotFoundException
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import com.denebapps.patrimonio.domain.repository.buildMovement
import com.denebapps.patrimonio.domain.repository.isQuotedFund
import com.denebapps.patrimonio.domain.repository.normalizeMovementNote
import com.denebapps.patrimonio.domain.repository.notQuotedMovementError
import com.denebapps.patrimonio.domain.repository.requireTransferBetweenFunds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [MovementRepository] over Room. Every write runs in one transaction with the same four steps: rebuild the whole set
 * of movements as it would be after the change, replay it through `computeLedgers` (which refuses an impossible
 * history before anything is written), write the rows, then materialize the units of the positions it touched on their
 * holdings (zero when a position is closed) and refresh the current month's net-worth snapshot. Units are written with
 * the targeted `updateUnits` query only, so a price refresh running meanwhile is never overwritten.
 *
 * Positions without movements are not touched here, and a manual (hand-valued) holding takes no movements: it has no
 * units. [newId] makes the ids of movements and transfers; tests may fix it.
 */
class MovementRepositoryImpl(
    private val appDatabase: AppDatabase,
    private val snapshotUpserter: NetWorthSnapshotUpserter,
    private val seedingGate: SeedingGate,
    private val clock: Clock,
    private val newId: () -> String = ::randomMovementId,
) : MovementRepository {
    private val dao get() = appDatabase.movementDao()
    private val holdingDao get() = appDatabase.portfolioHoldingDao()

    override fun observeMovements(holdingId: String): Flow<List<Movement>> = flow {
        seedingGate.await()
        emitAll(dao.observeByHolding(holdingId).map { rows -> rows.map(::movementToDomain).inProcessingOrder() })
    }

    override fun observeAllMovements(): Flow<List<Movement>> = flow {
        seedingGate.await()
        emitAll(dao.observeAll().map { rows -> rows.map(::movementToDomain).inProcessingOrder() })
    }

    override fun observeHoldingIdsWithMovements(): Flow<Set<String>> = flow {
        seedingGate.await()
        emitAll(dao.observeHoldingIdsWithMovements().map { it.toSet() })
    }

    override suspend fun addMovement(holdingId: String, draft: MovementDraft): Movement {
        seedingGate.await()
        return appDatabase.writeTransaction { record(holdingId, draft) }
    }

    override suspend fun updateMovement(id: String, draft: MovementDraft): Movement {
        seedingGate.await()
        return appDatabase.writeTransaction {
            val existing = dao.find(id)?.let(::movementToDomain) ?: throw MovementNotFoundException(id)
            val holding = requireQuotedHolding(existing.holdingId)
            val isLeg = existing.transferId != null
            if (isLeg && draft.type != existing.type) {
                throw InvalidMovementException("Un traspaso no puede cambiar de tipo; elimínalo y crea otro.")
            }
            if (!isLeg) requireNotTransferLeg(draft.type)
            val currency = Currency.valueOf(holding.currency)
            val rebuilt =
                buildMovement(draft, existing.id, holding.id, currency, existing.sequence, existing.transferId)
            commit(updated = listOf(rebuilt))
            rebuilt
        }
    }

    override suspend fun deleteMovement(id: String) {
        seedingGate.await()
        appDatabase.writeTransaction {
            val existing = dao.find(id) ?: throw MovementNotFoundException(id)
            val removed = existing.transferId?.let { dao.listByTransfer(it) } ?: listOf(existing)
            commit(removed = removed.map(::movementToDomain))
        }
    }

    override suspend fun addTransfer(
        fromHoldingId: String,
        toHoldingId: String,
        outDate: LocalDate,
        inDate: LocalDate,
        unitsOut: Units,
        unitsIn: Units,
        note: String?,
    ): String {
        seedingGate.await()
        return appDatabase.writeTransaction {
            val origin = requireHolding(fromHoldingId)
            val destination = requireHolding(toHoldingId)
            requireTransferBetweenFunds(
                fromId = fromHoldingId,
                toId = toHoldingId,
                fromIsQuotedFund = origin.takesFundTransfers(),
                toIsQuotedFund = destination.takesFundTransfers(),
            )
            val transferId = newId()
            val sequence = nextSequence()
            val normalizedNote = normalizeMovementNote(note)
            val out = Movement(
                id = newId(),
                holdingId = fromHoldingId,
                type = MovementType.TRANSFER_OUT,
                date = outDate,
                units = unitsOut,
                transferId = transferId,
                note = normalizedNote,
                sequence = sequence,
            )
            val inn = out.copy(
                id = newId(),
                holdingId = toHoldingId,
                type = MovementType.TRANSFER_IN,
                date = inDate,
                units = unitsIn,
                sequence = sequence + 1,
            )
            commit(added = listOf(out, inn))
            transferId
        }
    }

    override suspend fun addOpeningBalance(
        holdingId: String,
        date: LocalDate,
        units: Units,
        cost: CurrencyAmount?,
        costEur: Money?,
        note: String?,
    ): Movement = addMovement(
        holdingId,
        MovementDraft(
            type = MovementType.OPENING_BALANCE,
            date = date,
            units = units,
            amount = cost,
            amountEur = costEur,
            note = note,
        ),
    )

    override suspend fun addHoldingWithPurchase(holding: PortfolioHolding, buy: MovementDraft): Movement {
        seedingGate.await()
        return appDatabase.writeTransaction {
            val valuation = holding.valuation as? HoldingValuation.Quoted ?: throw notQuotedMovementError()
            if (buy.type != MovementType.BUY) {
                throw InvalidMovementException("La primera operación de un producto nuevo debe ser una compra.")
            }
            if (appDatabase.portfolioDao().find(holding.portfolioId) == null) {
                throw PortfolioNotFoundException(holding.portfolioId)
            }
            // Its units are those of the purchase, which `commit` materializes; until then it holds none.
            val empty = holding.copy(valuation = valuation.copy(units = UnitsBalance.ZERO))
            holdingDao.insert(portfolioHoldingToEntity(empty, clock.now().toEpochMilliseconds()))
            record(holding.id, buy)
        }
    }

    /** Builds and commits one new movement on [holdingId]. Runs inside a transaction. */
    private suspend fun record(holdingId: String, draft: MovementDraft): Movement {
        val holding = requireQuotedHolding(holdingId)
        requireNotTransferLeg(draft.type)
        val movement = buildMovement(draft, newId(), holding.id, Currency.valueOf(holding.currency), nextSequence())
        commit(added = listOf(movement))
        return movement
    }

    /**
     * Replays the history as it would be with the change, and only when the ledger accepts it writes the rows, the
     * units of the touched positions and the net-worth snapshot. Runs inside a transaction.
     */
    private suspend fun commit(
        added: List<Movement> = emptyList(),
        updated: List<Movement> = emptyList(),
        removed: List<Movement> = emptyList(),
    ) {
        val removedIds = removed.map { it.id }.toSet()
        val updatedById = updated.associateBy { it.id }
        val history = dao.list().map(::movementToDomain)
            .filter { it.id !in removedIds }
            .map { updatedById[it.id] ?: it } + added
        val ledgers = computeLedgers(history) // throws before anything is written
        added.forEach { dao.insert(it.toEntity()) }
        updated.forEach { dao.update(it.toEntity()) }
        removed.forEach { dao.delete(it.id) }
        // A position's units depend on its own movements only, so these are the only ones that can have changed.
        (added + updated + removed).map { it.holdingId }.toSet().forEach { holdingId ->
            holdingDao.updateUnits(holdingId, ledgers[holdingId]?.unitsHeld?.scaled ?: 0L)
        }
        snapshotUpserter.refreshCurrentMonth()
    }

    private suspend fun nextSequence(): Long = (dao.maxSequence() ?: 0L) + 1

    private suspend fun requireHolding(id: String): PortfolioHoldingEntity =
        holdingDao.find(id) ?: throw PortfolioHoldingNotFoundException(id)

    private suspend fun requireQuotedHolding(id: String): PortfolioHoldingEntity =
        requireHolding(id).also { if (it.kind != HoldingKind.QUOTED.name) throw notQuotedMovementError() }

    private fun requireNotTransferLeg(type: MovementType) {
        if (type == MovementType.TRANSFER_OUT || type == MovementType.TRANSFER_IN) {
            throw InvalidMovementException("Los traspasos se crean con los dos productos a la vez.")
        }
    }

    private fun PortfolioHoldingEntity.takesFundTransfers() =
        isQuotedFund(InstrumentType.valueOf(type), kind == HoldingKind.QUOTED.name)
}

@OptIn(ExperimentalUuidApi::class)
private fun randomMovementId(): String = Uuid.random().toString()

internal fun movementToDomain(row: MovementEntity): Movement = Movement(
    id = row.id,
    holdingId = row.holdingId,
    type = MovementType.valueOf(row.type),
    date = LocalDate.fromEpochDays(row.epochDay.toInt()),
    units = row.unitsScaled?.let(::Units),
    amount = row.amountMinor?.let { minor ->
        val currency = checkNotNull(row.currency) { "movement '${row.id}' has an amount but no currency" }
        CurrencyAmount(Money(minor), Currency.valueOf(currency))
    },
    fee = Money(row.feeMinor),
    amountEur = row.amountEurMinor?.let(::Money),
    feeEur = Money(row.feeEurMinor),
    transferId = row.transferId,
    note = row.note,
    sequence = row.sequence,
)

internal fun Movement.toEntity() = MovementEntity(
    id = id,
    holdingId = holdingId,
    type = type.name,
    epochDay = date.toEpochDays().toLong(),
    unitsScaled = units?.scaled,
    amountMinor = amount?.amount?.minorUnits,
    currency = amount?.currency?.code,
    feeMinor = fee.minorUnits,
    amountEurMinor = amountEur?.minorUnits,
    feeEurMinor = feeEur.minorUnits,
    transferId = transferId,
    note = note,
    sequence = sequence,
)
