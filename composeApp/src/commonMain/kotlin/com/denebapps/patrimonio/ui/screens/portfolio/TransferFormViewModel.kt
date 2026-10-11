package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.calc.unitsInputText
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.toBalance
import com.denebapps.patrimonio.domain.repository.MAX_MOVEMENT_NOTE_LENGTH
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.domain.repository.isQuotedFund
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val NEEDS_OPENING_TEXT = "Para traspasar, registra antes el saldo inicial de este fondo."
private const val EXCLUDED_TEXT =
    "Los fondos con participaciones sin movimientos necesitan un saldo inicial antes de recibir un traspaso."
private const val NO_PRICE_TEXT_FOR_FUND = "No hay precio disponible para este fondo."

/** A fund that can receive the transfer: its [label] names its portfolio too when there are several. */
data class TransferDestinationUi(val id: String, val label: String)

/**
 * The transfer sheet, every text ready to show. When editing ([isEditing]) the two funds are fixed
 * ([fromName], [destinationName]). A new transfer picks its destination among [destinations] ([destinationId]), or
 * searches ([searchingNew]) a fund that is not held yet: [results] of the [query], then the [newFund] picked, which is
 * created in [portfolioId] with the transfer. [blockedText] says why a new transfer cannot be made from this fund,
 * and [excludedHint] why some funds are not offered. [error] is why the last save or delete was refused.
 */
data class TransferFormUiState(
    val loaded: Boolean = false,
    val isEditing: Boolean = false,
    val fromName: String = "",
    val availableText: String = "",
    val blockedText: String? = null,
    val destinations: List<TransferDestinationUi> = emptyList(),
    val destinationId: String? = null,
    val destinationName: String? = null,
    val excludedHint: String? = null,
    val searchingNew: Boolean = false,
    val query: String = "",
    val searchStatus: SearchStatus = SearchStatus.IDLE,
    val results: List<CandidateUi> = emptyList(),
    val newFund: CandidateUi? = null,
    val newFundLoading: Boolean = false,
    val newFundError: String? = null,
    val portfolios: List<PortfolioOptionUi> = emptyList(),
    val portfolioId: String? = null,
    val outDate: LocalDate = LocalDate(1970, 1, 1),
    val inDate: LocalDate = LocalDate(1970, 1, 1),
    val unitsOutText: String = "",
    val unitsInText: String = "",
    val noteText: String = "",
    val error: String? = null,
    val canSave: Boolean = false,
    val canDelete: Boolean = false,
    val deleteMessage: String = "",
) {
    val canPickPortfolio: Boolean get() = portfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE
}

/** A transfer being edited: its two legs and the names of the funds they are on. */
private class EditedTransfer(val out: Movement, val inn: Movement, val fromName: String, val toName: String)

/** What the portfolio says once loaded; it does not change while the sheet is open. */
private class TransferContext(
    val origin: PortfolioHolding,
    val available: UnitsBalance,
    val originHasMovements: Boolean,
    val destinations: List<TransferDestinationUi>,
    val excluded: Int,
    val portfolios: List<Portfolio>,
    val heldRefs: Set<InstrumentRef>,
    val heldIsins: Set<String>,
    val editing: EditedTransfer?,
)

/** A fund picked from the search, with the price that makes it a holding. */
private class PickedFund(val candidate: InstrumentCandidate, val quote: Quote)

/** What the user typed or picked; the incoming side follows the outgoing one until it is touched. */
private data class TransferTyped(
    val destinationId: String? = null,
    val searchingNew: Boolean = false,
    val query: String = "",
    val searchStatus: SearchStatus = SearchStatus.IDLE,
    val results: List<CandidateUi> = emptyList(),
    val loadingFund: Boolean = false,
    val fundError: String? = null,
    val portfolioId: String? = null,
    val outDate: LocalDate = LocalDate(1970, 1, 1),
    val inDate: LocalDate = LocalDate(1970, 1, 1),
    val inDateTouched: Boolean = false,
    val unitsOutText: String = "",
    val unitsInText: String = "",
    val unitsInTouched: Boolean = false,
    val noteText: String = "",
)

/**
 * The sheet that makes a fund transfer ("traspaso") out of the fund [holdingId], or edits and deletes the one
 * [transferId] (opened from either of its funds). A new transfer goes to another quoted fund that has movements (or no
 * units) in any portfolio, or to a fund found with the product search that is not held yet, which is created in the
 * same step. It starts moving every unit held, on today, and the incoming side follows what is typed until it is
 * changed (a transfer between funds usually changes the number of units). What the repository refuses is shown in
 * [TransferFormUiState.error] and the sheet stays open; a save or a delete closes it through [navigateBack].
 */
class TransferFormViewModel(
    private val holdingId: String,
    private val transferId: String?,
    private val portfolioRepository: PortfolioRepository,
    private val portfolios: PortfolioCollectionRepository,
    private val movementRepository: MovementRepository,
    private val quoteSource: QuoteSource,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
    private val idProvider: () -> String = ::newTransferHoldingId,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TransferFormUiState())
    val state: StateFlow<TransferFormUiState> = mutableState.asStateFlow()

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the sheet, after a save or a delete, or when there is nothing to show. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private var context: TransferContext? = null
    private var typed = TransferTyped()
    private var candidates: List<InstrumentCandidate> = emptyList()
    private var picked: PickedFund? = null
    private var errorText: String? = null
    private var busy = false
    private var searchJob: Job? = null
    private var quoteJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    fun onDestinationChange(id: String) {
        val offered = context?.destinations ?: return
        if (offered.none { it.id == id }) return
        stopSearching()
        change { it.copy(destinationId = id, searchingNew = false) }
    }

    /** Switches to the search of a fund that is not held yet ("Otro fondo…"). */
    fun onOtherFund() {
        picked = null
        change { it.copy(destinationId = null, searchingNew = true) }
    }

    fun onQueryChange(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.length < MIN_SEARCH_LENGTH) {
            candidates = emptyList()
            change { it.copy(query = text, searchStatus = SearchStatus.IDLE, results = emptyList()) }
            return
        }
        change { it.copy(query = text, searchStatus = SearchStatus.LOADING, results = emptyList()) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                candidates = quoteSource.search(query).filter(::isNewFund)
                change {
                    it.copy(
                        searchStatus = if (candidates.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS,
                        results = candidates.map { candidate -> candidate.toUi() },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                candidates = emptyList()
                change { it.copy(searchStatus = SearchStatus.ERROR, results = emptyList()) }
            }
        }
    }

    /** Picks a search result as the destination and fetches its price, which the new holding needs. */
    fun onPickCandidate(ref: InstrumentRef) {
        val candidate = candidates.firstOrNull { it.ref == ref } ?: return
        quoteJob?.cancel()
        change { it.copy(loadingFund = true, fundError = null) }
        quoteJob = viewModelScope.launch {
            try {
                picked = PickedFund(candidate, quoteSource.latestQuote(ref))
                change { it.copy(loadingFund = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                change { it.copy(loadingFund = false, fundError = NO_PRICE_TEXT_FOR_FUND) }
            }
        }
    }

    /** Forgets the fund picked, back to the search results. */
    fun onClearNewFund() {
        quoteJob?.cancel()
        picked = null
        change { it.copy(loadingFund = false, fundError = null) }
    }

    /** The portfolio the new fund is created in; ignored if there is no such portfolio. */
    fun onPortfolioChange(portfolioId: String) {
        val available = context?.portfolios ?: return
        if (available.none { it.id == portfolioId }) return
        change { it.copy(portfolioId = portfolioId) }
    }

    fun onOutDateChange(date: LocalDate) =
        change { it.copy(outDate = date, inDate = if (it.inDateTouched) it.inDate else date) }

    fun onInDateChange(date: LocalDate) = change { it.copy(inDate = date, inDateTouched = true) }

    fun onUnitsOutChange(text: String) = change {
        it.copy(unitsOutText = text, unitsInText = if (it.unitsInTouched) it.unitsInText else text)
    }

    fun onUnitsInChange(text: String) = change { it.copy(unitsInText = text, unitsInTouched = true) }

    /** Moves every unit there is: puts all of them in the outgoing side. */
    fun onAllUnits() {
        val available = context?.available ?: return
        onUnitsOutChange(unitsInputText(available))
    }

    /** The note holds at most [MAX_MOVEMENT_NOTE_LENGTH] characters. */
    fun onNoteChange(text: String) = change { it.copy(noteText = text.take(MAX_MOVEMENT_NOTE_LENGTH)) }

    fun onSave() {
        val form = context ?: return
        if (busy || !canSave(form)) return
        val unitsOut = parseUnitsEs(typed.unitsOutText) ?: return
        val unitsIn = parseUnitsEs(typed.unitsInText) ?: return
        busy = true
        errorText = null
        render()
        viewModelScope.launch {
            try {
                val editing = form.editing
                val newFund = picked.takeIf { typed.destinationId == null }
                when {
                    editing != null -> movementRepository.updateTransfer(
                        checkNotNull(editing.out.transferId),
                        typed.outDate,
                        typed.inDate,
                        unitsOut,
                        unitsIn,
                        typed.noteText,
                    )
                    newFund != null -> movementRepository.addTransferToNewHolding(
                        holdingId,
                        newHolding(newFund, form),
                        typed.outDate,
                        typed.inDate,
                        unitsOut,
                        unitsIn,
                        typed.noteText,
                    )
                    else -> movementRepository.addTransfer(
                        holdingId,
                        checkNotNull(typed.destinationId),
                        typed.outDate,
                        typed.inDate,
                        unitsOut,
                        unitsIn,
                        typed.noteText,
                    )
                }
                navigateBackChannel.send(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure)
            }
        }
    }

    fun onDelete() {
        val editing = context?.editing ?: return
        if (busy) return
        busy = true
        errorText = null
        render()
        viewModelScope.launch {
            try {
                movementRepository.deleteMovement(editing.out.id)
                navigateBackChannel.send(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(failure)
            }
        }
    }

    private fun fail(failure: Exception) {
        busy = false
        errorText = movementErrorMessage(failure)
        render()
    }

    private fun change(transform: (TransferTyped) -> TransferTyped) {
        typed = transform(typed)
        errorText = null
        render()
    }

    private fun stopSearching() {
        searchJob?.cancel()
        quoteJob?.cancel()
        picked = null
        candidates = emptyList()
        typed = typed.copy(query = "", searchStatus = SearchStatus.IDLE, results = emptyList())
    }

    private suspend fun load() {
        val holdings = portfolioRepository.observeAll().first()
        val movements = movementRepository.observeAllMovements().first()
        val allPortfolios = portfolios.observePortfolios().first()
        val today = clock.todayIn(zoneProvider())
        val edited = transferId?.let { id -> editedTransfer(id, movements, holdings) ?: return close() }
        val originId = edited?.out?.holdingId ?: holdingId
        val origin = holdings.firstOrNull { it.id == originId } ?: return close()
        val valuation = origin.valuation as? HoldingValuation.Quoted ?: return close()
        if (!isQuotedFund(origin.type, isQuoted = true)) return close()
        val withMovements = movements.map { it.holdingId }.toSet()
        val funds = holdings.filter { it.id != originId && it.isQuotedFund() }
        val eligible = funds.filter { it.id in withMovements || it.units().isZero }
        val several = allPortfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE
        context = TransferContext(
            origin = origin,
            available = edited?.let { valuation.units + checkNotNull(it.out.units).toBalance() } ?: valuation.units,
            originHasMovements = originId in withMovements,
            destinations = eligible.map { fund ->
                val portfolioName = allPortfolios.firstOrNull { it.id == fund.portfolioId }?.name
                val label = if (several && portfolioName != null) "${fund.name} · $portfolioName" else fund.name
                TransferDestinationUi(fund.id, label)
            },
            excluded = funds.size - eligible.size,
            portfolios = allPortfolios,
            heldRefs = holdings.mapNotNull { (it.valuation as? HoldingValuation.Quoted)?.ref }.toSet(),
            heldIsins = holdings.mapNotNull { it.isin }.toSet(),
            editing = edited,
        )
        typed = if (edited == null) {
            val all = unitsInputText(valuation.units).takeIf { !valuation.units.isZero }.orEmpty()
            TransferTyped(outDate = today, inDate = today, unitsOutText = all, unitsInText = all)
        } else {
            TransferTyped(
                outDate = edited.out.date,
                inDate = edited.inn.date,
                inDateTouched = true,
                unitsOutText = unitsInputText(checkNotNull(edited.out.units)),
                unitsInText = unitsInputText(checkNotNull(edited.inn.units)),
                unitsInTouched = true,
                noteText = edited.out.note.orEmpty(),
            )
        }
        render()
    }

    private fun editedTransfer(
        id: String,
        movements: List<Movement>,
        holdings: List<PortfolioHolding>,
    ): EditedTransfer? {
        val legs = movements.filter { it.transferId == id }
        val out = legs.singleOrNull { it.type == MovementType.TRANSFER_OUT } ?: return null
        val inn = legs.singleOrNull { it.type == MovementType.TRANSFER_IN } ?: return null
        val from = holdings.firstOrNull { it.id == out.holdingId } ?: return null
        val to = holdings.firstOrNull { it.id == inn.holdingId } ?: return null
        return EditedTransfer(out, inn, from.name, to.name)
    }

    private fun close() {
        navigateBackChannel.trySend(Unit)
    }

    /** Not held yet, by reference or by ISIN, and a fund. */
    private fun isNewFund(candidate: InstrumentCandidate): Boolean {
        val form = context ?: return false
        return candidate.type == InstrumentType.FUND &&
            candidate.ref !in form.heldRefs &&
            (candidate.isin == null || candidate.isin !in form.heldIsins)
    }

    private fun newHolding(fund: PickedFund, form: TransferContext): PortfolioHolding {
        val candidate = fund.candidate
        val portfolioId = typed.portfolioId?.takeIf { id -> form.portfolios.any { it.id == id } }
            ?: form.origin.portfolioId
        return PortfolioHolding(
            id = idProvider(),
            portfolioId = portfolioId,
            name = candidate.holdingName(),
            type = candidate.type,
            isin = candidate.isin,
            ticker = candidate.ticker,
            valuation = HoldingValuation.Quoted(
                ref = candidate.ref,
                currency = fund.quote.price.currency,
                units = UnitsBalance.ZERO,
                lastQuote = LastQuote(fund.quote.price, fund.quote.date, clock.now().toEpochMilliseconds()),
            ),
            fundamentals = candidate.fundamentals,
        )
    }

    private fun canSave(form: TransferContext): Boolean {
        val destinationReady = form.editing != null || typed.destinationId != null || picked != null
        val blocked = form.editing == null && !form.originHasMovements
        return !busy && !blocked && destinationReady &&
            parseUnitsEs(typed.unitsOutText) != null && parseUnitsEs(typed.unitsInText) != null
    }

    private fun render() {
        val form = context ?: return
        val editing = form.editing
        val destination = form.destinations.firstOrNull { it.id == typed.destinationId }
        mutableState.value = TransferFormUiState(
            loaded = true,
            isEditing = editing != null,
            fromName = editing?.fromName ?: form.origin.name,
            availableText = "Disponibles: ${formatUnits(form.available)} part.",
            blockedText = NEEDS_OPENING_TEXT.takeIf { editing == null && !form.originHasMovements },
            destinations = if (editing == null) form.destinations else emptyList(),
            destinationId = typed.destinationId,
            destinationName = editing?.toName ?: destination?.label,
            excludedHint = EXCLUDED_TEXT.takeIf { editing == null && form.excluded > 0 },
            searchingNew = typed.searchingNew,
            query = typed.query,
            searchStatus = typed.searchStatus,
            results = typed.results,
            newFund = picked?.candidate?.toUi(),
            newFundLoading = typed.loadingFund,
            newFundError = typed.fundError,
            portfolios = form.portfolios.toOptions(),
            portfolioId = typed.portfolioId?.takeIf { id -> form.portfolios.any { it.id == id } }
                ?: form.origin.portfolioId,
            outDate = typed.outDate,
            inDate = typed.inDate,
            unitsOutText = typed.unitsOutText,
            unitsInText = typed.unitsInText,
            noteText = typed.noteText,
            error = errorText,
            canSave = canSave(form),
            canDelete = editing != null,
            deleteMessage = editing?.let(::deleteMessageOf).orEmpty(),
        )
    }

    private fun deleteMessageOf(transfer: EditedTransfer) =
        "Se eliminarán las dos partes del traspaso: la salida de «${transfer.fromName}» " +
            "y la entrada en «${transfer.toName}»."

    private fun PortfolioHolding.isQuotedFund() = isQuotedFund(type, valuation is HoldingValuation.Quoted)

    private fun PortfolioHolding.units(): UnitsBalance =
        (valuation as? HoldingValuation.Quoted)?.units ?: UnitsBalance.ZERO
}

@OptIn(ExperimentalUuidApi::class)
private fun newTransferHoldingId(): String = Uuid.random().toString()
