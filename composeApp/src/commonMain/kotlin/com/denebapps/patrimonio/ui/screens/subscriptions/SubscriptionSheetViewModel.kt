package com.denebapps.patrimonio.ui.screens.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.SubscriptionRepository
import com.denebapps.patrimonio.ui.components.amountInputText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val SUBSCRIPTION_SHEET_STOP_TIMEOUT_MS = 5_000L

data class PayingAssetOptionUi(val id: String, val name: String)

data class SubscriptionSheetUiState(
    val isEdit: Boolean,
    /** False while an edited subscription is still loading; the form is not shown until then. */
    val loaded: Boolean,
    val name: String,
    val amountText: String,
    val currency: Currency,
    val cycle: BillingCycle,
    val firstChargeDate: LocalDate,
    val paidFromAssetId: String?,
    val active: Boolean,
    val assetOptions: List<PayingAssetOptionUi>,
    val canSave: Boolean,
    val errorMessage: String?,
)

private data class SheetFields(
    val loaded: Boolean,
    val name: String = "",
    val amountText: String = "",
    val currency: Currency = Currency.EUR,
    val cycle: BillingCycle = BillingCycle.MONTHLY,
    val firstChargeDate: LocalDate,
    val paidFromAssetId: String? = null,
    val active: Boolean = true,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Create ([subscriptionId] null) or edit/delete one subscription. In edit mode the stored values are
 * loaded once; a subscription that no longer exists closes the sheet. Save and delete emit one
 * [navigateBack] event on success and leave the sheet open with [SubscriptionSheetUiState.errorMessage]
 * on failure.
 */
class SubscriptionSheetViewModel(
    private val subscriptionRepository: SubscriptionRepository,
    assetRepository: AssetRepository,
    clock: Clock,
    zoneProvider: () -> TimeZone,
    private val subscriptionId: String? = null,
    private val idProvider: () -> String = ::newSubscriptionId,
) : ViewModel() {
    private val fields = MutableStateFlow(
        SheetFields(loaded = subscriptionId == null, firstChargeDate = clock.todayIn(zoneProvider())),
    )

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    val state: StateFlow<SubscriptionSheetUiState> = combine(fields, assetRepository.observeAll()) { f, assets ->
        buildState(f, assets.map { PayingAssetOptionUi(it.id, it.name) })
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_SHEET_STOP_TIMEOUT_MS),
        initialValue = buildState(fields.value, emptyList()),
    )

    init {
        if (subscriptionId != null) {
            viewModelScope.launch {
                val stored = subscriptionRepository.find(subscriptionId)
                if (stored == null) {
                    navigateBackChannel.send(Unit)
                } else {
                    fields.value = fields.value.copy(
                        loaded = true,
                        name = stored.name,
                        amountText = amountInputText(stored.amount.amount, stored.amount.currency),
                        currency = stored.amount.currency,
                        cycle = stored.cycle,
                        firstChargeDate = stored.firstChargeDate,
                        paidFromAssetId = stored.paidFromAssetId,
                        active = stored.active,
                    )
                }
            }
        }
    }

    fun onNameChange(text: String) = edit { copy(name = text) }

    fun onAmountChange(text: String) = edit { copy(amountText = text) }

    fun onCurrencyChange(currency: Currency) = edit { copy(currency = currency) }

    fun onCycleChange(cycle: BillingCycle) = edit { copy(cycle = cycle) }

    fun onFirstChargeDateChange(date: LocalDate) = edit { copy(firstChargeDate = date) }

    fun onPaidFromChange(assetId: String?) = edit { copy(paidFromAssetId = assetId) }

    fun onActiveChange(active: Boolean) = edit { copy(active = active) }

    fun onSave() {
        val f = fields.value
        if (f.saving || !f.loaded) return
        val amountMinor = parseAmountToMinor(f.amountText, f.currency) ?: return
        val name = f.name.trim().ifEmpty { return }
        val subscription = Subscription(
            id = subscriptionId ?: idProvider(),
            name = name,
            amount = CurrencyAmount(Money(amountMinor), f.currency),
            cycle = f.cycle,
            firstChargeDate = f.firstChargeDate,
            paidFromAssetId = f.paidFromAssetId,
            active = f.active,
        )
        runAndClose("No se pudo guardar la suscripción.") {
            if (subscriptionId == null) {
                subscriptionRepository.insert(subscription)
            } else {
                subscriptionRepository.update(subscription)
            }
        }
    }

    fun onDelete() {
        val id = subscriptionId ?: return
        if (fields.value.saving) return
        runAndClose("No se pudo borrar la suscripción.") { subscriptionRepository.deleteById(id) }
    }

    private fun edit(change: SheetFields.() -> SheetFields) {
        fields.value = fields.value.change().copy(errorMessage = null)
    }

    private fun runAndClose(failureMessage: String, block: suspend () -> Unit) {
        fields.value = fields.value.copy(saving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                block()
                navigateBackChannel.send(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.error("SubscriptionSheetViewModel", failureMessage, error)
                fields.value = fields.value.copy(saving = false, errorMessage = failureMessage)
            }
        }
    }

    private fun buildState(f: SheetFields, assetOptions: List<PayingAssetOptionUi>) = SubscriptionSheetUiState(
        isEdit = subscriptionId != null,
        loaded = f.loaded,
        name = f.name,
        amountText = f.amountText,
        currency = f.currency,
        cycle = f.cycle,
        firstChargeDate = f.firstChargeDate,
        paidFromAssetId = f.paidFromAssetId,
        active = f.active,
        assetOptions = assetOptions,
        canSave = f.loaded && !f.saving && f.name.isNotBlank() && parseAmountToMinor(f.amountText, f.currency) != null,
        errorMessage = f.errorMessage,
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun newSubscriptionId(): String = Uuid.random().toString()
