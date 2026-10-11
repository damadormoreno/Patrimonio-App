package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.parseUnitsEs
import com.denebapps.patrimonio.domain.calc.quotedValue
import com.denebapps.patrimonio.domain.calc.unitsInputText
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
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
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
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

private const val HOLDING_DETAIL_STOP_TIMEOUT_MS = 5_000L

private const val MANUAL_SOURCE = "Valor manual"

/**
 * One holding in detail. [valueText]/[valueEurText] are its current value; [priceText]/[priceDateText] the last
 * price (for a manual holding only the day it was valued). [editText] is the units of a quoted holding or the value
 * of a manual one, in [currency] for the latter; [preview] is what the units come to at the last price. [broker] is
 * the stored bank or broker (shown with the identity) and [brokerText] what the field holds.
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
    val canSave: Boolean = false,
)

/**
 * Detail sheet of the holding [holdingId]: shows it, edits its units (quoted) or its value (manual) and its bank or
 * broker, and deletes it. What the user types survives price refreshes; the sheet closes through [navigateBack] after a save or a
 * delete, and also if the holding disappears while it is open.
 */
class HoldingDetailViewModel(
    private val holdingId: String,
    private val portfolioRepository: PortfolioRepository,
    fxRepository: FxRepository,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    /** What the user typed, `null` while untouched so the field follows the stored value. */
    private val edit = MutableStateFlow<String?>(null)

    /** Same for the bank or broker field. */
    private val brokerEdit = MutableStateFlow<String?>(null)

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the sheet. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private var closed = false

    val state: StateFlow<HoldingDetailUiState> = combine(
        portfolioRepository.observeAll(),
        fxRepository.observeRates(),
        edit,
        brokerEdit,
    ) { holdings, rates, typed, typedBroker ->
        holdings.firstOrNull { it.id == holdingId }?.let { buildState(it, rates, typed, typedBroker) }
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

    fun onEditChange(text: String) {
        edit.value = text
    }

    /** The field holds at most [MAX_BROKER_LENGTH] characters; trimming happens once, when saving. */
    fun onBrokerChange(text: String) {
        brokerEdit.value = text.take(MAX_BROKER_LENGTH)
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
            } catch (e: PortfolioHoldingNotFoundException) {
                // Deleted meanwhile: closing is all that is left to do.
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
            }
            close()
        }
    }

    private suspend fun saveValue(stored: PortfolioHolding, form: HoldingDetailUiState) {
        when (val valuation = stored.valuation) {
            is HoldingValuation.Quoted -> {
                val units = parseUnitsEs(form.editText) ?: return
                if (units != valuation.units) portfolioRepository.updateUnits(holdingId, units)
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
        rates: FxRates,
        typed: String?,
        typedBroker: String?,
    ): HoldingDetailUiState {
        val (valueText, valueEurText) = holdingValueTexts(holding, rates)
        val brokerText = typedBroker ?: holding.broker.orEmpty()
        val base = HoldingDetailUiState(
            loaded = true,
            name = holding.name,
            typeLabel = instrumentTypeLabel(holding.type),
            identifier = identifierText(holding.isin, holding.ticker),
            valueText = valueText,
            valueEurText = valueEurText,
            broker = holding.broker,
            brokerText = brokerText,
        )
        // Editing the broker alone may be saved, but never while the units or the value being typed are invalid.
        val brokerChanged = normalizeBroker(brokerText) != holding.broker
        return when (val valuation = holding.valuation) {
            is HoldingValuation.Quoted -> {
                val editText = typed ?: unitsInputText(valuation.units)
                val units = parseUnitsEs(editText)
                base.copy(
                    source = providerLabel(valuation.ref.provider),
                    priceText = valuation.lastQuote?.let { formatPrice(it.price) },
                    priceDateText = valuation.lastQuote?.let { formatShortDate(it.date) },
                    isQuoted = true,
                    currency = valuation.currency,
                    editText = editText,
                    preview = units?.let { entered ->
                        valuation.lastQuote?.let { quotedPreview(entered, it.price) }
                    },
                    canSave = units != null && (units != valuation.units || brokerChanged),
                )
            }
            is HoldingValuation.Manual -> {
                val currency = valuation.value.currency
                val editText = typed ?: amountInputText(valuation.value.amount, currency)
                val minor = parseAmountToMinor(editText, currency)
                base.copy(
                    source = MANUAL_SOURCE,
                    priceDateText = formatShortDate(dayOf(valuation.valuedAtEpochMs, zoneProvider())),
                    isQuoted = false,
                    currency = currency,
                    editText = editText,
                    canSave = minor != null && (minor != valuation.value.amount.minorUnits || brokerChanged),
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
