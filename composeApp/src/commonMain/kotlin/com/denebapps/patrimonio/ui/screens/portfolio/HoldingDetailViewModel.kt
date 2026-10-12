package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.calc.quotedValue
import com.denebapps.patrimonio.domain.calc.unitsInputText
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.MAX_BROKER_LENGTH
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.normalizeBroker
import com.denebapps.patrimonio.domain.model.toBalance
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.MovementRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.ui.components.amountInputText
import com.denebapps.patrimonio.ui.components.formatMoney
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

private const val HOLDING_DETAIL_STOP_TIMEOUT_MS = 5_000L

private const val MANUAL_SOURCE = "Valor manual"

private const val UNITS_FROM_MOVEMENTS_NOTE = "Las participaciones salen de tus movimientos."

/**
 * One holding in detail. [valueText]/[valueEurText] are its current value; [priceText]/[priceDateText] the last
 * price (for a manual holding only the day it was valued). [editText] is the units of a quoted holding or the value
 * of a manual one, in [currency] for the latter; [preview] is what the units come to at the last price. [broker] is
 * the stored bank or broker (shown with the identity) and [brokerText] what the field holds. [portfolios] are the
 * portfolios to move it to and [portfolioId] the one chosen (its own until the user picks another). [assetClass] is
 * the state of the "Clase de activo" picker: its [AssetClassChoiceUi.explicit] is the class chosen (the stored one
 * until the user picks another, `null` for automatic). A quoted holding with movements has its units read-only
 * ([unitsLocked], with the reason in [unitsNote]): they come from the movements. [error] is why the last delete was
 * refused.
 */
data class HoldingDetailUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val typeLabel: String = "",
    val identifier: String = "",
    val source: String = "",
    val valueText: String = "",
    val valueEurText: String? = null,
    val priceText: String? = null,
    val priceDateText: String? = null,
    val isQuoted: Boolean = true,
    val currency: Currency = Currency.EUR,
    val editText: String = "",
    val preview: String? = null,
    val broker: String? = null,
    val brokerText: String = "",
    val portfolios: List<PortfolioOptionUi> = emptyList(),
    val portfolioId: String = "",
    val assetClass: AssetClassChoiceUi = AssetClassChoiceUi(),
    val unitsLocked: Boolean = false,
    val unitsNote: String? = null,
    val error: String? = null,
    val canSave: Boolean = false,
)

/** A class picked in the form; `null` inside means "automatic", so it needs a wrapper to tell it from untouched. */
private data class ClassPick(val assetClass: AssetClass?)

/** What the user typed or picked; `null` for a field left untouched, so it follows the stored value. */
private data class HoldingEdits(
    val value: String? = null,
    val broker: String? = null,
    val portfolioId: String? = null,
    val assetClass: ClassPick? = null,
    val error: String? = null,
)

/**
 * Detail sheet of the holding [holdingId]: shows it, edits its units (quoted) or its value (manual), its bank or
 * broker and the portfolio it is in, and deletes it. What the user types survives price refreshes; the sheet closes through [navigateBack] after a save or a
 * delete, and also if the holding disappears while it is open.
 */
class HoldingDetailViewModel(
    private val holdingId: String,
    private val portfolioRepository: PortfolioRepository,
    private val portfolios: PortfolioCollectionRepository,
    fxRepository: FxRepository,
    movementRepository: MovementRepository,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    private val edits = MutableStateFlow(HoldingEdits())

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the sheet. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private var closed = false

    val state: StateFlow<HoldingDetailUiState> = combine(
        portfolioRepository.observeAll(),
        portfolios.observePortfolios(),
        fxRepository.observeRates(),
        movementRepository.observeHoldingIdsWithMovements(),
        edits,
    ) { holdings, allPortfolios, rates, withMovements, typed ->
        holdings.firstOrNull { it.id == holdingId }
            ?.let { buildState(it, allPortfolios.toOptions(), rates, holdingId in withMovements, typed) }
            ?: HoldingDetailUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(HOLDING_DETAIL_STOP_TIMEOUT_MS),
        initialValue = HoldingDetailUiState(),
    )

    init {
        // Gone meanwhile (deleted elsewhere, or never existed): there is nothing left to show.
        viewModelScope.launch {
            portfolioRepository.observeAll().first { holdings -> holdings.none { it.id == holdingId } }
            close()
        }
    }

    fun onEditChange(text: String) = edits.update { it.copy(value = text, error = null) }

    /** The field holds at most [MAX_BROKER_LENGTH] characters; trimming happens once, when saving. */
    fun onBrokerChange(text: String) = edits.update { it.copy(broker = text.take(MAX_BROKER_LENGTH)) }

    /** Sets the class when saved; `null` clears the choice, leaving the class to the product's category or type. */
    fun onAssetClassChange(assetClass: AssetClass?) = edits.update { it.copy(assetClass = ClassPick(assetClass)) }

    /** Moves the holding to [portfolioId] when saved; ignored if there is no such portfolio. */
    fun onPortfolioChange(portfolioId: String) {
        if (state.value.portfolios.none { it.id == portfolioId }) return
        edits.update { it.copy(portfolioId = portfolioId) }
    }

    fun onSave() {
        val form = state.value
        if (!form.loaded || !form.canSave) return
        viewModelScope.launch {
            try {
                // Only what changed is written: saving a new broker must not restamp a manual value's date.
                val stored = portfolioRepository.observeAll().first().firstOrNull { it.id == holdingId }
                    ?: throw PortfolioHoldingNotFoundException(holdingId)
                saveValue(stored, form)
                if (normalizeBroker(form.brokerText) != stored.broker) {
                    portfolioRepository.updateBroker(holdingId, form.brokerText)
                }
                if (form.assetClass.explicit != stored.assetClass) {
                    portfolioRepository.updateAssetClass(holdingId, form.assetClass.explicit)
                }
                if (form.portfolioId != stored.portfolioId) portfolios.moveHolding(holdingId, form.portfolioId)
            } catch (e: PortfolioHoldingNotFoundException) {
                // Deleted meanwhile: closing is all that is left to do.
            } catch (e: PortfolioNotFoundException) {
                // The chosen portfolio was deleted meanwhile: the rest was saved and the holding stays where it was.
            }
            close()
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            try {
                portfolioRepository.delete(holdingId)
            } catch (e: PortfolioHoldingNotFoundException) {
                // Already gone.
            } catch (e: HoldingHasTransfersException) {
                edits.update { it.copy(error = e.message) }
                return@launch
            }
            close()
        }
    }

    private suspend fun saveValue(stored: PortfolioHolding, form: HoldingDetailUiState) {
        when (val valuation = stored.valuation) {
            is HoldingValuation.Quoted -> {
                // The units of a position with movements come from them and are not edited here.
                if (form.unitsLocked) return
                val units = parseUnitsEs(form.editText) ?: return
                if (units.toBalance() != valuation.units) portfolioRepository.updateUnits(holdingId, units)
            }
            is HoldingValuation.Manual -> {
                val minor = parseAmountToMinor(form.editText, form.currency) ?: return
                if (minor != valuation.value.amount.minorUnits) {
                    portfolioRepository.updateManualValue(holdingId, CurrencyAmount(Money(minor), form.currency))
                }
            }
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        navigateBackChannel.trySend(Unit)
    }

    private fun buildState(
        holding: PortfolioHolding,
        options: List<PortfolioOptionUi>,
        rates: FxRates,
        hasMovements: Boolean,
        typed: HoldingEdits,
    ): HoldingDetailUiState {
        val (valueText, valueEurText) = holdingValueTexts(holding, rates)
        val brokerText = typed.broker ?: holding.broker.orEmpty()
        // A portfolio picked and deleted since is no longer a choice: the holding's own stands.
        val portfolioId = typed.portfolioId?.takeIf { id -> options.any { it.id == id } } ?: holding.portfolioId
        val picked = typed.assetClass
        val assetClass = assetClassChoice(
            explicit = if (picked != null) picked.assetClass else holding.assetClass,
            category = holding.fundamentals?.category,
            type = holding.type,
        )
        val base = HoldingDetailUiState(
            loaded = true,
            name = holding.name,
            typeLabel = instrumentTypeLabel(holding.type),
            identifier = identifierText(holding.isin, holding.ticker),
            valueText = valueText,
            valueEurText = valueEurText,
            broker = holding.broker,
            brokerText = brokerText,
            portfolios = options,
            portfolioId = portfolioId,
            assetClass = assetClass,
            error = typed.error,
        )
        // Editing the broker, the portfolio or the class alone may be saved, but never while the units or the value
        // being typed are invalid.
        val otherChanged = normalizeBroker(brokerText) != holding.broker ||
            portfolioId != holding.portfolioId ||
            assetClass.explicit != holding.assetClass
        return when (val valuation = holding.valuation) {
            is HoldingValuation.Quoted -> {
                val locked = hasMovements
                val editText = if (locked) {
                    formatUnits(
                        valuation.units,
                    )
                } else {
                    typed.value ?: unitsInputText(valuation.units)
                }
                val units = parseUnitsEs(editText)
                base.copy(
                    source = providerLabel(valuation.ref.provider),
                    priceText = valuation.lastQuote?.let { formatPrice(it.price) },
                    priceDateText = valuation.lastQuote?.let { formatShortDate(it.date) },
                    isQuoted = true,
                    currency = valuation.currency,
                    editText = editText,
                    preview = units?.takeIf { !locked }?.let { entered ->
                        valuation.lastQuote?.let { quotedPreview(entered, it.price) }
                    },
                    unitsLocked = locked,
                    unitsNote = UNITS_FROM_MOVEMENTS_NOTE.takeIf { locked },
                    canSave = if (locked) {
                        otherChanged
                    } else {
                        units != null && (units.toBalance() != valuation.units || otherChanged)
                    },
                )
            }
            is HoldingValuation.Manual -> {
                val currency = valuation.value.currency
                val editText = typed.value ?: amountInputText(valuation.value.amount, currency)
                val minor = parseAmountToMinor(editText, currency)
                base.copy(
                    source = MANUAL_SOURCE,
                    priceDateText = formatShortDate(dayOf(valuation.valuedAtEpochMs, zoneProvider())),
                    isQuoted = false,
                    currency = currency,
                    editText = editText,
                    canSave = minor != null && (minor != valuation.value.amount.minorUnits || otherChanged),
                )
            }
        }
    }

    private fun quotedPreview(units: Units, price: Price): String? = try {
        val value = quotedValue(units, price)
        "≈ ${formatMoney(value.amount, value.currency)}"
    } catch (e: ArithmeticException) {
        null
    }
}
