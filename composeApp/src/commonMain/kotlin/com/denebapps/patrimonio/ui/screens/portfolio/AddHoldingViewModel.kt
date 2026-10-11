package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.calc.quotedValue
import com.denebapps.patrimonio.domain.calc.unitsFromTotal
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.ui.components.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** How long the query must stay unchanged before the quote source is asked. */
internal const val SEARCH_DEBOUNCE_MS = 400L

/** Shorter queries (after trimming) are not searched: they match far too much. */
internal const val MIN_SEARCH_LENGTH = 2

/** Where the add form is: searching, fetching a price, price unavailable, entering what is held, or a manual value. */
enum class AddHoldingStage { SEARCH, LOADING_QUOTE, QUOTE_FAILED, ENTRY, MANUAL }

/** State of the search list under the search field. */
enum class SearchStatus { IDLE, LOADING, RESULTS, EMPTY, ERROR }

/** How the user tells what they hold: the number of units, or what the position is worth now. */
enum class EntryMode { UNITS, AMOUNT }

/** A search result row. [identifier] is the ISIN and/or ticker; [source] is the exchange or the data provider. */
data class CandidateUi(
    val ref: InstrumentRef,
    val name: String,
    val identifier: String,
    val typeLabel: String,
    val source: String,
)

/** The price just fetched, ready to show: the unit price and the day it is from. */
data class QuoteUi(val priceText: String, val dateText: String, val currency: Currency)

/**
 * Everything the add form shows. Only the fields of the current [stage] matter: the search ones in `SEARCH`, the
 * entry ones in `ENTRY`, the manual ones (name, type, currency and [amountText] as the value) in `MANUAL`. [preview]
 * is the live "≈ ..." line of the entry form and [canSave] says whether Save is enabled.
 */
data class AddHoldingUiState(
    val stage: AddHoldingStage = AddHoldingStage.SEARCH,
    val query: String = "",
    val searchStatus: SearchStatus = SearchStatus.IDLE,
    val results: List<CandidateUi> = emptyList(),
    val picked: CandidateUi? = null,
    val quote: QuoteUi? = null,
    val entryMode: EntryMode = EntryMode.UNITS,
    val unitsText: String = "",
    val amountText: String = "",
    val preview: String? = null,
    val name: String = "",
    val type: InstrumentType = InstrumentType.OTHER,
    val currency: Currency = Currency.EUR,
    val canSave: Boolean = false,
)

/** What the entry form resolves to: the units that will be stored and the preview line that says so. */
private class EntryResult(val units: Units, val preview: String)

/**
 * Add-a-holding form. The user searches a product (debounced, [MIN_SEARCH_LENGTH] characters at least), picks one,
 * and its latest price is fetched; then they enter either the units held or what the position is worth now (the units
 * are derived from the price). A product that cannot be found, or whose price cannot be fetched, is added by hand
 * with a value of the user's own. Save stores the holding and emits one [navigateBack].
 */
class AddHoldingViewModel(
    private val quoteSource: QuoteSource,
    private val portfolioRepository: PortfolioRepository,
    private val clock: Clock,
    private val idProvider: () -> String = ::newHoldingId,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddHoldingUiState())
    val state: StateFlow<AddHoldingUiState> = mutableState.asStateFlow()

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the form, emitted after a successful [onSave]. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private var candidates: List<InstrumentCandidate> = emptyList()
    private var pickedCandidate: InstrumentCandidate? = null
    private var pickedQuote: Quote? = null
    private var searchJob: Job? = null
    private var quoteJob: Job? = null
    private var saving = false

    fun onQueryChange(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.length < MIN_SEARCH_LENGTH) {
            candidates = emptyList()
            update { it.copy(query = text, searchStatus = SearchStatus.IDLE, results = emptyList()) }
            return
        }
        update { it.copy(query = text, searchStatus = SearchStatus.LOADING, results = emptyList()) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                candidates = quoteSource.search(query)
                update {
                    it.copy(
                        searchStatus = if (candidates.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS,
                        results = candidates.map { candidate -> candidate.toUi() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                candidates = emptyList()
                update { it.copy(searchStatus = SearchStatus.ERROR, results = emptyList()) }
            }
        }
    }

    /** Picks a search result and fetches its latest price. */
    fun onPick(ref: InstrumentRef) {
        if (mutableState.value.stage != AddHoldingStage.SEARCH) return
        val candidate = candidates.firstOrNull { it.ref == ref } ?: return
        quoteJob?.cancel()
        pickedCandidate = candidate
        pickedQuote = null
        update {
            it.copy(
                stage = AddHoldingStage.LOADING_QUOTE,
                picked = candidate.toUi(),
                quote = null,
                entryMode = EntryMode.UNITS,
                unitsText = "",
                amountText = "",
            )
        }
        quoteJob = viewModelScope.launch {
            try {
                val latest = quoteSource.latestQuote(ref)
                pickedQuote = latest
                update {
                    it.copy(
                        stage = AddHoldingStage.ENTRY,
                        quote = QuoteUi(formatPrice(latest.price), formatShortDate(latest.date), latest.price.currency),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(stage = AddHoldingStage.QUOTE_FAILED) }
            }
        }
    }

    fun onEntryModeChange(mode: EntryMode) = update { it.copy(entryMode = mode) }

    fun onUnitsChange(text: String) = update { it.copy(unitsText = text) }

    /** The current total of the position in the entry form, or the value of a manual holding. */
    fun onAmountChange(text: String) = update { it.copy(amountText = text) }

    /** Starts a holding of the user's own value with nothing prefilled. */
    fun onStartManual() {
        quoteJob?.cancel()
        clearPicked()
        update { manualForm(it, name = "", type = InstrumentType.OTHER, currency = Currency.EUR) }
    }

    /** After a failed price: the picked product becomes a manual holding that keeps its name, type and ids. */
    fun onAddManuallyFromPicked() {
        val candidate = pickedCandidate ?: return
        pickedQuote = null
        update { manualForm(it, candidate.name, candidate.type, candidate.currency ?: Currency.EUR) }
    }

    /** Back to the search, which keeps the query and its results; whatever was picked or typed is dropped. */
    fun onBackToSearch() {
        quoteJob?.cancel()
        clearPicked()
        update {
            it.copy(
                stage = AddHoldingStage.SEARCH,
                picked = null,
                quote = null,
                entryMode = EntryMode.UNITS,
                unitsText = "",
                amountText = "",
                name = "",
                type = InstrumentType.OTHER,
                currency = Currency.EUR,
            )
        }
    }

    fun onNameChange(text: String) = update { it.copy(name = text) }

    fun onTypeChange(type: InstrumentType) = update { it.copy(type = type) }

    fun onCurrencyChange(currency: Currency) = update { it.copy(currency = currency) }

    fun onSave() {
        val form = mutableState.value
        if (saving || !form.canSave) return
        val holding = when (form.stage) {
            AddHoldingStage.ENTRY -> quotedHolding(form)
            AddHoldingStage.MANUAL -> manualHolding(form)
            else -> null
        } ?: return
        saving = true
        viewModelScope.launch {
            portfolioRepository.add(holding)
            navigateBackChannel.send(Unit)
        }
    }

    private fun quotedHolding(form: AddHoldingUiState): PortfolioHolding? {
        val candidate = pickedCandidate ?: return null
        val quote = pickedQuote ?: return null
        val units = entryResult(form)?.units ?: return null
        return PortfolioHolding(
            id = idProvider(),
            name = candidate.holdingName(),
            type = candidate.type,
            isin = candidate.isin,
            ticker = candidate.ticker,
            valuation = HoldingValuation.Quoted(
                ref = candidate.ref,
                currency = quote.price.currency,
                units = units,
                lastQuote = LastQuote(quote.price, quote.date, clock.now().toEpochMilliseconds()),
            ),
        )
    }

    private fun manualHolding(form: AddHoldingUiState): PortfolioHolding? {
        val minor = parseAmountToMinor(form.amountText, form.currency) ?: return null
        return PortfolioHolding(
            id = idProvider(),
            name = form.name.trim().ifBlank { return null },
            type = form.type,
            isin = pickedCandidate?.isin,
            ticker = pickedCandidate?.ticker,
            valuation = HoldingValuation.Manual(
                value = CurrencyAmount(Money(minor), form.currency),
                valuedAtEpochMs = clock.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun clearPicked() {
        pickedCandidate = null
        pickedQuote = null
    }

    private fun manualForm(form: AddHoldingUiState, name: String, type: InstrumentType, currency: Currency) = form.copy(
        stage = AddHoldingStage.MANUAL,
        quote = null,
        amountText = "",
        name = name,
        type = type,
        currency = currency,
    )

    private fun update(transform: (AddHoldingUiState) -> AddHoldingUiState) {
        mutableState.update { withDerived(transform(it)) }
    }

    /** Recomputes the preview line and the Save gate from the form fields. */
    private fun withDerived(form: AddHoldingUiState): AddHoldingUiState = when (form.stage) {
        AddHoldingStage.ENTRY -> {
            val result = entryResult(form)
            form.copy(preview = result?.preview, canSave = result != null)
        }
        AddHoldingStage.MANUAL -> form.copy(
            preview = null,
            canSave = form.name.isNotBlank() && parseAmountToMinor(form.amountText, form.currency) != null,
        )
        else -> form.copy(preview = null, canSave = false)
    }

    /** `null` while the entered units or total are invalid, zero, or do not fit the fixed-point range. */
    private fun entryResult(form: AddHoldingUiState): EntryResult? {
        val price = pickedQuote?.price ?: return null
        return try {
            when (form.entryMode) {
                EntryMode.UNITS -> parseUnitsEs(form.unitsText)?.let { units ->
                    val value = quotedValue(units, price)
                    EntryResult(units, "≈ ${formatMoney(value.amount, value.currency)}")
                }
                EntryMode.AMOUNT -> parseAmountToMinor(form.amountText, price.currency)?.let { minor ->
                    val units = unitsFromTotal(Money(minor), price)
                    EntryResult(units, "≈ ${formatUnits(units)} participaciones")
                }
            }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }
}

/** A provider can send a nameless result; the ISIN, ticker or code then stands in for the name. */
private fun InstrumentCandidate.holdingName(): String = name.trim().ifBlank { isin ?: ticker ?: ref.code }

private fun InstrumentCandidate.toUi() = CandidateUi(
    ref = ref,
    name = name,
    identifier = identifierText(isin, ticker),
    typeLabel = instrumentTypeLabel(type),
    source = exchange ?: providerLabel(ref.provider),
)

@OptIn(ExperimentalUuidApi::class)
private fun newHoldingId(): String = Uuid.random().toString()
