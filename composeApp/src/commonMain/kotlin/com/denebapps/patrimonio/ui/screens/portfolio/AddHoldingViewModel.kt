package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.calc.quotedValue
import com.denebapps.patrimonio.domain.calc.unitsFromTotal
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.LastQuote
import com.denebapps.patrimonio.domain.model.MAX_BROKER_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.normalizeBroker
import com.denebapps.patrimonio.domain.repository.FxHistoryRepository
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
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

/** What the user tells about a new product: what they hold now (no movements), or one purchase (a position with them). */
enum class EntryKind { CURRENT, PURCHASE }

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
 * The "Una compra" entry: the purchase that creates the position. [amountText] and [eurText] are what the two amount
 * fields hold (what the user typed in each, or what follows from the other at the rate of the day); the euro field is
 * only there when [showEur] (the product is not quoted in euros). [eurHint] and [amountHint] say which one needs to be
 * typed because the day has no rate, [impliedPriceText] is the price per unit the amount and units come to and
 * [impliedRateText] the rate the two amounts come to. [amountPlaceholder] and [eurPlaceholder] are the value a field
 * follows, to show muted inside it while it is empty (a field held blank while the user edits it).
 * [feeText] is in euros when [feeInEur] (the default for a product not quoted in euros, which shows the switch
 * [showFeeCurrency]) and in the product currency otherwise; [feePreviewText] is the fee in the other one.
 */
data class PurchaseUi(
    val date: LocalDate,
    val unitsText: String = "",
    val amountText: String = "",
    val feeText: String = "",
    val feeInEur: Boolean = false,
    val showFeeCurrency: Boolean = false,
    val feePreviewText: String? = null,
    val eurText: String = "",
    val showEur: Boolean = false,
    val eurHint: String? = null,
    val amountHint: String? = null,
    val amountPlaceholder: String? = null,
    val eurPlaceholder: String? = null,
    val impliedPriceText: String? = null,
    val impliedRateText: String? = null,
)

/** What the user typed in the purchase entry; an amount field that is not in [amounts] follows the other one. */
private data class PurchaseTyped(
    val date: LocalDate,
    val unitsText: String = "",
    val amounts: TypedAmounts = TypedAmounts(),
    val feeText: String = "",
    val feeInEur: Boolean = true,
)

/**
 * Everything the add form shows. Only the fields of the current [stage] matter: the search ones in `SEARCH`, the
 * entry ones in `ENTRY`, the manual ones (name, type, currency and [amountText] as the value) in `MANUAL`. [preview]
 * is the live "≈ ..." line of the entry form and [canSave] says whether Save is enabled. [broker] is the optional bank
 * or broker field, as typed, shared by the entry and the manual forms. [portfolios] are the portfolios to choose from
 * and [portfolioId] the one the holding is added to; the picker is only shown ([canPickPortfolio]) with several.
 * [assetClass] is the state of the "Clase de activo" picker, shared by the entry and the manual forms. The entry is
 * what is held now or, with [entryKind] [EntryKind.PURCHASE], the [purchase] that creates the position; [saveError] is
 * why the last save was refused.
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
    val broker: String = "",
    val portfolios: List<PortfolioOptionUi> = emptyList(),
    val portfolioId: String? = null,
    val assetClass: AssetClassChoiceUi = AssetClassChoiceUi(),
    val entryKind: EntryKind = EntryKind.CURRENT,
    val purchase: PurchaseUi = PurchaseUi(date = LocalDate(1970, 1, 1)),
    val saveError: String? = null,
    val canSave: Boolean = false,
) {
    val canPickPortfolio: Boolean get() = portfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE
}

/** What the entry form resolves to: the units that will be stored and the preview line that says so. */
private class EntryResult(val units: Units, val preview: String)

/**
 * Add-a-holding form. The user searches a product (debounced, [MIN_SEARCH_LENGTH] characters at least), picks one,
 * and its latest price is fetched; then they enter either the units held or what the position is worth now (the units
 * are derived from the price). A product that cannot be found, or whose price cannot be fetched, is added by hand
 * with a value of the user's own. Save stores the holding, with the fundamentals (cost, risk, category, manager) the
 * search already knew about the picked product, and emits one [navigateBack]. The holding goes to the portfolio chosen
 * with [onPortfolioChange], which starts as the one selected in Cartera (the first one when all are shown). The asset
 * class starts as the one the product's category or type implies; [onAssetClassChange] picks another one. Only a class
 * different from the inferred one is stored with the holding, so that a category that changes later keeps classifying it.
 */
class AddHoldingViewModel(
    private val quoteSource: QuoteSource,
    private val portfolioRepository: PortfolioRepository,
    private val portfolios: PortfolioCollectionRepository,
    private val preferences: PreferencesRepository,
    private val movementRepository: MovementRepository,
    fxHistoryRepository: FxHistoryRepository,
    private val clock: Clock,
    zoneProvider: () -> TimeZone,
    private val idProvider: () -> String = ::newHoldingId,
) : ViewModel() {
    private val today = clock.todayIn(zoneProvider())
    private var purchase = PurchaseTyped(date = today)
    private val mutableState = MutableStateFlow(AddHoldingUiState(purchase = PurchaseUi(date = today)))
    val state: StateFlow<AddHoldingUiState> = mutableState.asStateFlow()

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the form, emitted after a successful [onSave]. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val openPreviewChannel = Channel<InstrumentRef>(Channel.BUFFERED)

    /** One-shot request to show the preview of a search result, emitted by [onOpenPreview]. */
    val openPreview: Flow<InstrumentRef> = openPreviewChannel.receiveAsFlow()

    private var candidates: List<InstrumentCandidate> = emptyList()
    private var pickedCandidate: InstrumentCandidate? = null
    private var pickedQuote: Quote? = null
    private var pickedClass: AssetClass? = null
    private var searchJob: Job? = null
    private var quoteJob: Job? = null
    private var saving = false
    private val fx = FxRateLookup(fxHistoryRepository, viewModelScope) { update { it } }

    init {
        fx.start()
        viewModelScope.launch {
            val remembered = preferences.observeSelectedPortfolioId().first()
            portfolios.observePortfolios().collect { all -> showPortfolios(all, remembered) }
        }
    }

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

    /** The search result with [ref] of the current search, or null once the search has changed. */
    fun candidate(ref: InstrumentRef): InstrumentCandidate? = candidates.firstOrNull { it.ref == ref }

    /**
     * Tapping a search result: asks for its preview ([openPreview]). The form stays as it is, so coming back from the
     * preview finds the same query and results.
     */
    fun onOpenPreview(ref: InstrumentRef) {
        if (mutableState.value.stage != AddHoldingStage.SEARCH || candidate(ref) == null) return
        openPreviewChannel.trySend(ref)
    }

    /**
     * Picks a search result to add and fetches its latest price. [portfolioId] is the portfolio the preview compared the
     * product with: the form starts on it (when it exists); `null` keeps the default.
     */
    fun onPick(ref: InstrumentRef, portfolioId: String? = null) {
        if (mutableState.value.stage != AddHoldingStage.SEARCH) return
        val candidate = candidate(ref) ?: return
        quoteJob?.cancel()
        pickedCandidate = candidate
        pickedQuote = null
        pickedClass = null
        purchase = PurchaseTyped(date = today)
        update {
            it.copy(
                stage = AddHoldingStage.LOADING_QUOTE,
                picked = candidate.toUi(),
                quote = null,
                entryKind = EntryKind.CURRENT,
                purchase = PurchaseUi(date = today),
                saveError = null,
                entryMode = EntryMode.UNITS,
                unitsText = "",
                amountText = "",
                portfolioId = it.portfolios.firstOrNull { option -> option.id == portfolioId }?.id ?: it.portfolioId,
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

    /** Switches between what is held now and one purchase ("Una compra"). */
    fun onEntryKindChange(kind: EntryKind) {
        update { it.copy(entryKind = kind, saveError = null) }
        if (kind == EntryKind.PURCHASE) pickedQuote?.let { fx.ensure(it.price.currency, purchase.date) }
    }

    fun onPurchaseDateChange(date: LocalDate) {
        purchase = purchase.copy(date = date, amounts = purchase.amounts.settled())
        pickedQuote?.let { fx.ensure(it.price.currency, date) }
        update { it.copy(saveError = null) }
    }

    fun onPurchaseUnitsChange(text: String) = changePurchase { it.copy(unitsText = text) }

    /** The amount as typed; a blank field follows the euro equivalent again. */
    fun onPurchaseAmountChange(text: String) = changePurchase { it.copy(amounts = it.amounts.withAmount(text)) }

    fun onPurchaseFeeChange(text: String) = changePurchase { it.copy(feeText = text) }

    /**
     * The fee is typed in euros (`true`) or in the currency of a product that is not in euros. The typed number stays as
     * it is and is read in the new currency.
     */
    fun onPurchaseFeeCurrencyChange(inEur: Boolean) {
        if (pickedQuote?.price?.currency.let { it == null || it == Currency.EUR }) return
        changePurchase { it.copy(feeInEur = inEur) }
    }

    /** The euro equivalent as typed; a blank field follows the amount again. */
    fun onPurchaseEurChange(text: String) = changePurchase { it.copy(amounts = it.amounts.withEur(text)) }

    private fun changePurchase(transform: (PurchaseTyped) -> PurchaseTyped) {
        // Any input but the amount fields lets one held blank follow the other again.
        purchase = transform(purchase.copy(amounts = purchase.amounts.settled()))
        update { it.copy(saveError = null) }
    }

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
        purchase = PurchaseTyped(date = today)
        update {
            it.copy(
                entryKind = EntryKind.CURRENT,
                purchase = PurchaseUi(date = today),
                saveError = null,
                stage = AddHoldingStage.SEARCH,
                picked = null,
                quote = null,
                entryMode = EntryMode.UNITS,
                unitsText = "",
                amountText = "",
                name = "",
                type = InstrumentType.OTHER,
                currency = Currency.EUR,
                broker = "",
            )
        }
    }

    fun onNameChange(text: String) = update { it.copy(name = text) }

    fun onTypeChange(type: InstrumentType) = update { it.copy(type = type) }

    /** Picks the class of the holding; `null` leaves it to the app (the inferred one, if any). */
    fun onAssetClassChange(assetClass: AssetClass?) {
        pickedClass = assetClass
        update { it }
    }

    fun onCurrencyChange(currency: Currency) = update { it.copy(currency = currency) }

    /** The field holds at most [MAX_BROKER_LENGTH] characters; trimming happens once, when saving. */
    fun onBrokerChange(text: String) = update { it.copy(broker = text.take(MAX_BROKER_LENGTH)) }

    /** Adds the holding to [portfolioId]; ignored if there is no such portfolio. */
    fun onPortfolioChange(portfolioId: String) {
        if (mutableState.value.portfolios.none { it.id == portfolioId }) return
        update { it.copy(portfolioId = portfolioId) }
    }

    fun onSave() {
        val form = mutableState.value
        if (saving || !form.canSave) return
        if (form.stage == AddHoldingStage.ENTRY && form.entryKind == EntryKind.PURCHASE) return savePurchase(form)
        val holdingIn = when (form.stage) {
            AddHoldingStage.ENTRY -> entryResult(form)?.let { quotedHolding(form, it.units) }
            AddHoldingStage.MANUAL -> manualHolding(form)
            else -> null
        } ?: return
        saving = true
        viewModelScope.launch {
            portfolioRepository.add(holdingIn(mutableState.value.portfolioId ?: defaultPortfolio()))
            navigateBackChannel.send(Unit)
        }
    }

    /** Stores the holding with its first purchase in one step; a refused purchase shows its reason and stores nothing. */
    private fun savePurchase(form: AddHoldingUiState) {
        val draft = purchaseEntry()?.draft ?: return
        val holdingIn = quotedHolding(form, checkNotNull(draft.units)) ?: return
        saving = true
        update { it.copy(saveError = null) }
        viewModelScope.launch {
            try {
                movementRepository.addHoldingWithPurchase(
                    holdingIn(mutableState.value.portfolioId ?: defaultPortfolio()),
                    draft,
                )
                navigateBackChannel.send(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                saving = false
                update { it.copy(saveError = movementErrorMessage(failure)) }
            }
        }
    }

    /** The holding the form describes, as a function of the portfolio it is added to; `null` when it is incomplete. */
    private fun quotedHolding(form: AddHoldingUiState, units: Units): ((String) -> PortfolioHolding)? {
        val candidate = pickedCandidate ?: return null
        val quote = pickedQuote ?: return null
        val id = idProvider()
        val valuation = HoldingValuation.Quoted(
            ref = candidate.ref,
            currency = quote.price.currency,
            units = units,
            lastQuote = LastQuote(quote.price, quote.date, clock.now().toEpochMilliseconds()),
        )
        val broker = normalizeBroker(form.broker)
        return { portfolioId ->
            PortfolioHolding(
                id = id,
                portfolioId = portfolioId,
                name = candidate.holdingName(),
                type = candidate.type,
                isin = candidate.isin,
                ticker = candidate.ticker,
                valuation = valuation,
                broker = broker,
                fundamentals = candidate.fundamentals,
                assetClass = storedClass(form.assetClass),
            )
        }
    }

    private fun classChoice(type: InstrumentType) =
        assetClassChoice(pickedClass, pickedCandidate?.fundamentals?.category, type)

    /** The class to store: the one picked, unless it is what the product implies anyway. */
    private fun storedClass(choice: AssetClassChoiceUi): AssetClass? = choice.explicit?.takeIf { it != choice.inferred }

    private fun manualHolding(form: AddHoldingUiState): ((String) -> PortfolioHolding)? {
        val minor = parseAmountToMinor(form.amountText, form.currency) ?: return null
        val name = form.name.trim().ifBlank { return null }
        val id = idProvider()
        val valuation = HoldingValuation.Manual(
            value = CurrencyAmount(Money(minor), form.currency),
            valuedAtEpochMs = clock.now().toEpochMilliseconds(),
        )
        val broker = normalizeBroker(form.broker)
        val candidate = pickedCandidate
        return { portfolioId ->
            PortfolioHolding(
                id = id,
                portfolioId = portfolioId,
                name = name,
                type = form.type,
                isin = candidate?.isin,
                ticker = candidate?.ticker,
                valuation = valuation,
                broker = broker,
                fundamentals = candidate?.fundamentals,
                assetClass = storedClass(form.assetClass),
            )
        }
    }

    /** The portfolio to use when the form has not loaded them yet; the app always has at least one. */
    private suspend fun defaultPortfolio(): String = checkNotNull(
        defaultPortfolioId(portfolios.observePortfolios().first(), preferences.observeSelectedPortfolioId().first()),
    ) { "there is always at least one portfolio" }

    /** Offers [all]; the choice stays while that portfolio exists, otherwise it is the [remembered] one or the first. */
    private fun showPortfolios(all: List<Portfolio>, remembered: String?) {
        update { form ->
            form.copy(
                portfolios = all.toOptions(),
                portfolioId = all.firstOrNull { it.id == form.portfolioId }?.id ?: defaultPortfolioId(all, remembered),
            )
        }
    }

    private fun clearPicked() {
        pickedCandidate = null
        pickedQuote = null
        pickedClass = null
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
        AddHoldingStage.ENTRY -> if (form.entryKind == EntryKind.PURCHASE) {
            val resolved = purchaseEntry()
            form.copy(
                preview = null,
                purchase = purchaseUi(resolved),
                assetClass = classChoice(pickedCandidate?.type ?: form.type),
                canSave = resolved?.draft != null,
            )
        } else {
            val result = entryResult(form)
            form.copy(
                preview = result?.preview,
                assetClass = classChoice(pickedCandidate?.type ?: form.type),
                canSave = result != null,
            )
        }
        AddHoldingStage.MANUAL -> form.copy(
            preview = null,
            assetClass = classChoice(form.type),
            canSave = form.name.isNotBlank() && parseAmountToMinor(form.amountText, form.currency) != null,
        )
        else -> form.copy(preview = null, assetClass = AssetClassChoiceUi(), canSave = false)
    }

    /** The purchase as typed, resolved at the rate of its day; `null` while there is no price (no product picked). */
    private fun purchaseEntry(): MovementEntry? {
        val currency = pickedQuote?.price?.currency ?: return null
        return resolveMovementEntry(
            MovementEntryInput(
                type = MovementType.BUY,
                date = purchase.date,
                currency = currency,
                unitsText = purchase.unitsText,
                amountText = purchase.amounts.amount.orEmpty(),
                feeText = purchase.feeText,
                eurText = purchase.amounts.eur,
                rateScaled = fx.rateOn(currency, purchase.date),
                feeInEur = currency != Currency.EUR && purchase.feeInEur,
            ),
        )
    }

    private fun purchaseUi(resolved: MovementEntry?): PurchaseUi {
        val currency = pickedQuote?.price?.currency ?: Currency.EUR
        val amountText = purchase.amounts.amount ?: resolved?.amountPrefillText.orEmpty()
        val eurText = purchase.amounts.eur ?: resolved?.eurPrefillText.orEmpty()
        val hints = missingRateHints(
            currency = currency,
            rateScaled = fx.rateOn(currency, purchase.date),
            amountText = purchase.amounts.amount.orEmpty(),
            eurText = purchase.amounts.eur,
            costOptional = false,
        )
        return PurchaseUi(
            date = purchase.date,
            unitsText = purchase.unitsText,
            amountText = amountText,
            feeText = purchase.feeText,
            feeInEur = currency != Currency.EUR && purchase.feeInEur,
            showFeeCurrency = currency != Currency.EUR,
            feePreviewText = resolved?.feePreviewText,
            eurText = eurText,
            showEur = currency != Currency.EUR,
            eurHint = hints.eurHint,
            amountHint = hints.amountHint,
            amountPlaceholder = resolved?.amountPrefillText.takeIf { amountText.isEmpty() },
            eurPlaceholder = resolved?.eurPrefillText.takeIf { eurText.isEmpty() },
            impliedPriceText = resolved?.impliedPriceText,
            impliedRateText = resolved?.impliedRateText,
        )
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
internal fun InstrumentCandidate.holdingName(): String = name.trim().ifBlank { isin ?: ticker ?: ref.code }

internal fun InstrumentCandidate.toUi() = CandidateUi(
    ref = ref,
    name = name,
    identifier = identifierText(isin, ticker),
    typeLabel = instrumentTypeLabel(type),
    source = exchange ?: providerLabel(ref.provider),
)

@OptIn(ExperimentalUuidApi::class)
internal fun newHoldingId(): String = Uuid.random().toString()
