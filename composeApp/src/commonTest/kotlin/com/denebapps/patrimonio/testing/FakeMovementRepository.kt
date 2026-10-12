package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.calc.computeLedgers
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.inProcessingOrder
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.HoldingMovementsSummary
import com.denebapps.patrimonio.domain.repository.InvalidMovementException
import com.denebapps.patrimonio.domain.repository.MovementDraft
import com.denebapps.patrimonio.domain.repository.MovementNotFoundException
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.buildMovement
import com.denebapps.patrimonio.domain.repository.isQuotedFund
import com.denebapps.patrimonio.domain.repository.noMovementsError
import com.denebapps.patrimonio.domain.repository.normalizeMovementNote
import com.denebapps.patrimonio.domain.repository.notPurchasesOnNewHoldingError
import com.denebapps.patrimonio.domain.repository.notQuotedMovementError
import com.denebapps.patrimonio.domain.repository.requireTransferBetweenFunds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * `MutableStateFlow`-backed [MovementRepository] fake for ViewModel unit tests, with the rules of the real one: the
 * whole history is replayed through the ledger before every write (an impossible one throws and changes nothing), the
 * EUR amounts are filled or required, transfers are between two different funds and delete as a pair. It writes the
 * units the movements decide into [holdings], and tells it which holdings have movements or transfers, so
 * [FakePortfolioRepository.updateUnits] and [FakePortfolioRepository.delete] refuse as the real ones do and a deleted
 * holding takes its movements with it.
 */
class FakeMovementRepository(
    private val holdings: FakePortfolioRepository,
    initial: List<Movement> = emptyList(),
) : MovementRepository {
    private val backing = MutableStateFlow(initial)
    private var nextId = 1

    /** The movements as stored, in no particular order. */
    val current: List<Movement> get() = backing.value

    init {
        holdings.onHoldingDeleted = { id ->
            backing.value = backing.value.filter { it.holdingId != id }
            publishHoldingFlags()
        }
        publishHoldingFlags()
    }

    override fun observeMovements(holdingId: String): Flow<List<Movement>> =
        backing.map { all -> all.filter { it.holdingId == holdingId }.inProcessingOrder() }

    override fun observeAllMovements(): Flow<List<Movement>> = backing.map { it.inProcessingOrder() }

    override fun observeHoldingIdsWithMovements(): Flow<Set<String>> =
        backing.map { all -> all.map { it.holdingId }.toSet() }

    override suspend fun addMovement(holdingId: String, draft: MovementDraft): Movement {
        val holding = quotedHolding(holdingId)
        if (draft.type == MovementType.TRANSFER_OUT || draft.type == MovementType.TRANSFER_IN) {
            throw InvalidMovementException("Los traspasos se crean con los dos productos a la vez.")
        }
        val movement = buildMovement(draft, newId(), holdingId, currencyOf(holding), nextSequence())
        apply(added = listOf(movement))
        return movement
    }

    override suspend fun updateMovement(id: String, draft: MovementDraft): Movement {
        val existing = backing.value.firstOrNull { it.id == id } ?: throw MovementNotFoundException(id)
        val isLeg = existing.transferId != null
        if (isLeg && draft.type != existing.type) {
            throw InvalidMovementException("Un traspaso no puede cambiar de tipo; elimínalo y crea otro.")
        }
        if (!isLeg && draft.type in TRANSFER_TYPES) {
            throw InvalidMovementException("Los traspasos se crean con los dos productos a la vez.")
        }
        val holding = quotedHolding(existing.holdingId)
        val rebuilt =
            buildMovement(draft, id, existing.holdingId, currencyOf(holding), existing.sequence, existing.transferId)
        apply(updated = listOf(rebuilt))
        return rebuilt
    }

    override suspend fun deleteMovement(id: String) {
        val existing = backing.value.firstOrNull { it.id == id } ?: throw MovementNotFoundException(id)
        val removed = existing.transferId?.let { transfer -> backing.value.filter { it.transferId == transfer } }
            ?: listOf(existing)
        apply(removed = removed)
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
        val origin = holding(fromHoldingId)
        val destination = holding(toHoldingId)
        requireTransferBetweenFunds(
            fromId = fromHoldingId,
            toId = toHoldingId,
            fromIsQuotedFund = origin.isTransferableFund(),
            toIsQuotedFund = destination.isTransferableFund(),
        )
        val transferId = newId()
        apply(
            added = transferLegs(transferId, fromHoldingId, toHoldingId, outDate, inDate, unitsOut, unitsIn, note),
        )
        return transferId
    }

    override suspend fun addTransferToNewHolding(
        fromHoldingId: String,
        newHolding: PortfolioHolding,
        outDate: LocalDate,
        inDate: LocalDate,
        unitsOut: Units,
        unitsIn: Units,
        note: String?,
    ): String {
        val origin = holding(fromHoldingId)
        requireTransferBetweenFunds(
            fromId = fromHoldingId,
            toId = newHolding.id,
            fromIsQuotedFund = origin.isTransferableFund(),
            toIsQuotedFund = newHolding.isTransferableFund(),
        )
        // The ledger is replayed before the holding exists, so a refused transfer leaves nothing behind.
        val transferId = newId()
        val legs = transferLegs(transferId, fromHoldingId, newHolding.id, outDate, inDate, unitsOut, unitsIn, note)
        computeLedgers(backing.value + legs)
        val valuation = newHolding.valuation as HoldingValuation.Quoted
        holdings.add(newHolding.copy(valuation = valuation.copy(units = UnitsBalance.ZERO)))
        apply(added = legs)
        return transferId
    }

    override suspend fun updateTransfer(
        transferId: String,
        outDate: LocalDate,
        inDate: LocalDate,
        unitsOut: Units,
        unitsIn: Units,
        note: String?,
    ) {
        val legs = backing.value.filter { it.transferId == transferId }
        val out = legs.singleOrNull { it.type == MovementType.TRANSFER_OUT }
        val inn = legs.singleOrNull { it.type == MovementType.TRANSFER_IN }
        if (out == null || inn == null) throw MovementNotFoundException(transferId)
        val normalizedNote = normalizeMovementNote(note)
        apply(
            updated = listOf(
                out.copy(date = outDate, units = unitsOut, note = normalizedNote),
                inn.copy(date = inDate, units = unitsIn, note = normalizedNote),
            ),
        )
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
        MovementDraft(MovementType.OPENING_BALANCE, date, units, cost, amountEur = costEur, note = note),
    )

    override suspend fun addHoldingWithPurchase(holding: PortfolioHolding, buy: MovementDraft): Movement =
        addHoldingWithPurchases(holding, listOf(buy)).single()

    override suspend fun addHoldingWithPurchases(holding: PortfolioHolding, buys: List<MovementDraft>): List<Movement> {
        val valuation = holding.valuation as? HoldingValuation.Quoted ?: throw notQuotedMovementError()
        if (buys.isEmpty()) throw noMovementsError()
        if (buys.any { it.type != MovementType.BUY }) throw notPurchasesOnNewHoldingError()
        // Built before the holding exists in the fake, so a refused purchase leaves nothing behind.
        val movements = build(buys, holding.id, valuation.currency)
        computeLedgers(backing.value + movements)
        holdings.add(holding.copy(valuation = valuation.copy(units = UnitsBalance.ZERO)))
        apply(added = movements)
        return movements
    }

    override suspend fun replaceHoldingMovements(holdingId: String, drafts: List<MovementDraft>): List<Movement> {
        val holding = quotedHolding(holdingId)
        val existing = backing.value.filter { it.holdingId == holdingId }
        if (existing.any { it.transferId != null || it.type in TRANSFER_TYPES }) {
            throw HoldingHasTransfersException(holdingId)
        }
        if (drafts.isEmpty()) throw noMovementsError()
        if (drafts.any { it.type in TRANSFER_TYPES }) {
            throw InvalidMovementException("Los traspasos se crean con los dos productos a la vez.")
        }
        val movements = build(drafts, holdingId, currencyOf(holding))
        apply(added = movements, removed = existing)
        return movements
    }

    override suspend fun movementsSummary(holdingId: String): HoldingMovementsSummary {
        holding(holdingId)
        val own = backing.value.filter { it.holdingId == holdingId }
        return HoldingMovementsSummary(own.size, hasTransfers = own.any { it.transferId != null })
    }

    private fun build(drafts: List<MovementDraft>, holdingId: String, currency: Currency): List<Movement> {
        val first = nextSequence()
        return drafts.mapIndexed { index, draft -> buildMovement(draft, newId(), holdingId, currency, first + index) }
    }

    private fun transferLegs(
        transferId: String,
        fromHoldingId: String,
        toHoldingId: String,
        outDate: LocalDate,
        inDate: LocalDate,
        unitsOut: Units,
        unitsIn: Units,
        note: String?,
    ): List<Movement> {
        val sequence = nextSequence()
        val out = Movement(
            id = newId(),
            holdingId = fromHoldingId,
            type = MovementType.TRANSFER_OUT,
            date = outDate,
            units = unitsOut,
            transferId = transferId,
            note = normalizeMovementNote(note),
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
        return listOf(out, inn)
    }

    private fun apply(
        added: List<Movement> = emptyList(),
        updated: List<Movement> = emptyList(),
        removed: List<Movement> = emptyList(),
    ) {
        val removedIds = removed.map { it.id }.toSet()
        val updatedById = updated.associateBy { it.id }
        val history = backing.value.filter { it.id !in removedIds }.map { updatedById[it.id] ?: it } + added
        val ledgers = computeLedgers(history)
        backing.value = history
        (added + updated + removed).map { it.holdingId }.toSet().forEach { holdingId ->
            holdings.materializeUnits(holdingId, ledgers[holdingId]?.unitsHeld ?: UnitsBalance.ZERO)
        }
        publishHoldingFlags()
    }

    private fun publishHoldingFlags() {
        holdings.holdingsWithMovements = backing.value.map { it.holdingId }.toSet()
        holdings.holdingsWithTransfers = backing.value
            .filter { it.transferId != null }
            .groupBy { it.transferId }
            .values
            .flatMap { legs -> legs.map { it.holdingId } }
            .toSet()
    }

    private fun holding(id: String): PortfolioHolding =
        holdings.current.firstOrNull { it.id == id } ?: throw PortfolioHoldingNotFoundException(id)

    private fun quotedHolding(id: String): PortfolioHolding =
        holding(id).also { if (it.valuation !is HoldingValuation.Quoted) throw notQuotedMovementError() }

    private fun currencyOf(holding: PortfolioHolding) = (holding.valuation as HoldingValuation.Quoted).currency

    private fun PortfolioHolding.isTransferableFund() = isQuotedFund(type, valuation is HoldingValuation.Quoted)

    private fun nextSequence(): Long = (backing.value.maxOfOrNull { it.sequence } ?: 0L) + 1

    private fun newId(): String = "movement-${nextId++}"

    private companion object {
        val TRANSFER_TYPES = setOf(MovementType.TRANSFER_OUT, MovementType.TRANSFER_IN)
    }
}
