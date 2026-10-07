package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val ADD_PATRIMONIO_STOP_TIMEOUT_MS = 5_000L

/** One selectable group tile in the Add sheet's group picker (spec: Add Patrimonio Item Form). */
data class GroupOptionUi(val id: String, val label: String)

/** Add-only Patrimonio item form state (design.md Interfaces/Contracts). */
data class AddPatrimonioUiState(
    val isLiability: Boolean,
    val groupOptions: List<GroupOptionUi>,
    val selectedGroupId: String?,
    val name: String,
    val amountText: String,
    val currency: Currency,
    val eurHint: String?,
    val canSave: Boolean,
)

/** One user-editable field group — combined with the fx-rates repo [Flow] to build
 *  [AddPatrimonioUiState]'s derived fields ([AddPatrimonioUiState.groupOptions],
 *  [AddPatrimonioUiState.eurHint], [AddPatrimonioUiState.canSave]). */
private data class FormFields(
    val isLiability: Boolean,
    val selectedGroupId: String?,
    val name: String,
    val amountText: String,
    val currency: Currency,
)

/**
 * Add-only Patrimonio item form (spec: Add Patrimonio Item Form). Mode defaults to
 * [initialIsLiability] — the mode the route was opened with, from the FAB (Activos/Pasivos toggle)
 * or a per-group add affordance — but stays user-switchable per spec; switching mode clears
 * [FormFields.selectedGroupId] since [Asset.AssetGroup]/[Liability.LiabilityGroup] are disjoint
 * enums. [initialGroupId] prefills selection when opened from a per-group add affordance
 * (design.md Data Flow: `group "+" -> AddPatrimonio(groupId=…)`). Save creates the asset or
 * liability via the matching repository using [idProvider] for the new String id (Room primary
 * keys for both entities are caller-supplied), then emits one [navigateBack] event.
 */
class AddPatrimonioSheetViewModel(
    private val assetRepository: AssetRepository,
    private val liabilityRepository: LiabilityRepository,
    fxRepository: FxRepository,
    private val idProvider: () -> String = ::newPatrimonioItemId,
    initialIsLiability: Boolean = false,
    initialGroupId: String? = null,
) : ViewModel() {

    private val form = MutableStateFlow(
        FormFields(
            isLiability = initialIsLiability,
            selectedGroupId = initialGroupId,
            name = "",
            amountText = "",
            currency = Currency.EUR,
        ),
    )

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot nav-back signal, emitted after a successful [onSave] (design.md Data Flow). */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    val state: StateFlow<AddPatrimonioUiState> = combine(
        form,
        fxRepository.observeRates(),
    ) { f, rates ->
        buildState(f, rates)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ADD_PATRIMONIO_STOP_TIMEOUT_MS),
        initialValue = buildState(form.value, FxRates(emptyMap())),
    )

    fun onModeChange(isLiability: Boolean) {
        if (form.value.isLiability == isLiability) return
        form.value = form.value.copy(isLiability = isLiability, selectedGroupId = null)
    }

    fun onGroupSelect(groupId: String) {
        form.value = form.value.copy(selectedGroupId = groupId)
    }

    fun onNameChange(text: String) {
        form.value = form.value.copy(name = text)
    }

    fun onAmountChange(text: String) {
        form.value = form.value.copy(amountText = text)
    }

    fun onCurrencyChange(currency: Currency) {
        form.value = form.value.copy(currency = currency)
    }

    fun onSave() {
        val f = form.value
        val groupId = f.selectedGroupId ?: return
        val amountMinor = parseAmountToMinor(f.amountText, f.currency) ?: return
        val name = f.name.trim()
        if (name.isEmpty()) return

        viewModelScope.launch {
            val amount = CurrencyAmount(Money(amountMinor), f.currency)
            if (f.isLiability) {
                liabilityRepository.insert(
                    Liability(
                        id = idProvider(),
                        group = Liability.LiabilityGroup.valueOf(groupId),
                        name = name,
                        subtitle = null,
                        amount = amount,
                    ),
                )
            } else {
                assetRepository.insert(
                    Asset(
                        id = idProvider(),
                        group = Asset.AssetGroup.valueOf(groupId),
                        name = name,
                        subtitle = null,
                        amount = amount,
                    ),
                )
            }
            navigateBackChannel.send(Unit)
        }
    }

    private fun buildState(f: FormFields, rates: FxRates): AddPatrimonioUiState {
        val amountMinor = parseAmountToMinor(f.amountText, f.currency)
        return AddPatrimonioUiState(
            isLiability = f.isLiability,
            groupOptions = if (f.isLiability) liabilityGroupOptions() else assetGroupOptions(),
            selectedGroupId = f.selectedGroupId,
            name = f.name,
            amountText = f.amountText,
            currency = f.currency,
            eurHint = eurHintFor(f.currency, amountMinor, rates),
            canSave = f.selectedGroupId != null && f.name.isNotBlank() && amountMinor != null,
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newPatrimonioItemId(): String = Uuid.random().toString()

private fun assetGroupOptions(): List<GroupOptionUi> = Asset.AssetGroup.entries.map {
    GroupOptionUi(it.name, assetGroupLabelFor(it))
}

private fun liabilityGroupOptions(): List<GroupOptionUi> = Liability.LiabilityGroup.entries.map {
    GroupOptionUi(it.name, liabilityGroupLabelFor(it))
}

/** Labels ported 1:1 from `design-reference/shared.jsx`'s `ASSET_GROUPS` (matches
 *  [PatrimonioViewModel]'s private `assetGroupLabel`, duplicated here since the enum→label mapping
 *  is presentation-layer and each sheet/screen owns its own). */
private fun assetGroupLabelFor(group: Asset.AssetGroup): String = when (group) {
    Asset.AssetGroup.BANK -> "Cuentas bancarias"
    Asset.AssetGroup.INVEST -> "Inversión"
    Asset.AssetGroup.REALESTATE -> "Inmuebles"
    Asset.AssetGroup.CRYPTO -> "Cripto"
    Asset.AssetGroup.CASH -> "Efectivo"
}

/** Labels ported 1:1 from `design-reference/shared.jsx`'s `LIAB_GROUPS`. */
private fun liabilityGroupLabelFor(group: Liability.LiabilityGroup): String = when (group) {
    Liability.LiabilityGroup.MORTGAGE -> "Hipotecas"
    Liability.LiabilityGroup.LOAN -> "Préstamos"
    Liability.LiabilityGroup.CARD -> "Tarjetas"
}

/** `null` when [currency] is EUR or [amountMinor] failed to parse; otherwise `"≈ X,XX € al cambio"`. */
private fun eurHintFor(currency: Currency, amountMinor: Long?, rates: FxRates): String? {
    if (currency == Currency.EUR || amountMinor == null) return null
    val eur = CurrencyAmount(Money(amountMinor), currency).toEur(rates)
    return "≈ ${formatMoneyEs(eur)} al cambio"
}

private fun formatMoneyEs(money: Money): String {
    val intPart = money.minorUnits / 100L
    val decPart = (kotlin.math.abs(money.minorUnits) % 100L).toString().padStart(2, '0')
    val grouped = kotlin.math.abs(intPart).toString().reversed().chunked(3).joinToString(".").reversed()
    val sign = if (intPart < 0) "-" else ""
    return "$sign$grouped,$decPart €"
}
