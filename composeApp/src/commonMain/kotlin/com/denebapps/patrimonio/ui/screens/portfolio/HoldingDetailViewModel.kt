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
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Price
import com.denebapps.patrimonio.domain.model.Units
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
 * of a manual one, in [currency] for the latter; [preview] is what the units come to at the last price.
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
    val canSave: Boolean = false,
)

/**
 * Detail sheet of the holding [holdingId]: shows it, edits its units (quoted) or its value (manual), and deletes
 * it. What the user types survives price refreshes; the sheet closes through [navigateBack] after a save or a
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

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal to close the sheet. */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private var closed = false

    val state: StateFlow<HoldingDetailUiState> = combine(
        portfolioRepository.observeAll(),
        fxRepository.observeRates(),
        edit,
    ) { holdings, rates, typed ->
        holdings.firstOrNull { it.id == holdingId }?.let { buildState(it, rates, typed) } ?: HoldingDetailUiState()
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

    fun onSave() {
        val form = state.value
        if (!form.loaded || !form.canSave) return
        viewModelScope.launch {
            try {
                if (form.isQuoted) {
                    portfolioRepository.updateUnits(holdingId, parseUnitsEs(form.editText) ?: return@launch)
                } else {
                    val minor = parseAmountToMinor(form.editText, form.currency) ?: return@launch
                    portfolioRepository.updateManualValue(holdingId, CurrencyAmount(Money(minor), form.currency))
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

    private fun close() {
        if (closed) return
        closed = true
        navigateBackChannel.trySend(Unit)
    }

    private fun buildState(holding: PortfolioHolding, rates: FxRates, typed: String?): HoldingDetailUiState {
        val (valueText, valueEurText) = holdingValueTexts(holding, rates)
        val base = HoldingDetailUiState(
            loaded = true,
            name = holding.name,
            typeLabel = instrumentTypeLabel(holding.type),
            identifier = identifierText(holding.isin, holding.ticker),
            valueText = valueText,
            valueEurText = valueEurText,
        )
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
                    canSave = units != null && units != valuation.units,
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
                    canSave = minor != null && minor != valuation.value.amount.minorUnits,
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
