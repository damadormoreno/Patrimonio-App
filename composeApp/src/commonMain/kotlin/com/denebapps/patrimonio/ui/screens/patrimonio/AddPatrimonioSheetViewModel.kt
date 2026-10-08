package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.AccountUsage
import com.denebapps.patrimonio.domain.calc.accountUsage
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.ui.components.MAX_EMOJI_LENGTH
import com.denebapps.patrimonio.ui.components.TypeLook
import com.denebapps.patrimonio.ui.components.amountInputText
import com.denebapps.patrimonio.ui.components.typeLook
import com.denebapps.patrimonio.ui.components.typeOptions
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val ADD_PATRIMONIO_STOP_TIMEOUT_MS = 5_000L

/** One selectable type tile in the Add sheet's type picker (spec: Add Patrimonio Item Form). */
data class GroupOptionUi(val id: String, val label: String, val type: TypeLook = typeLook(id))

/**
 * Patrimonio item form state, for a new item or for [isEditing] an existing one. [deleteWarning] says what
 * else deleting the edited account changes (the goals and groups it leaves), null when nothing.
 */
data class AddPatrimonioUiState(
    val isLiability: Boolean,
    val isEditing: Boolean = false,
    val deleteWarning: String? = null,
    val groupOptions: List<GroupOptionUi>,
    val selectedGroupId: String?,
    val name: String,
    val amountText: String,
    val currency: Currency,
    val eurHint: String?,
    val canSave: Boolean,
    /** The account's own emoji, blank for none (then its type's icon or emoji stands for it). */
    val emoji: String = "",
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
    val emoji: String = "",
)

/**
 * Patrimonio item form (spec: Add Patrimonio Item Form), also the editor of an existing item: with
 * [editingItemId] the item is loaded into the form, its mode is fixed (assets and liabilities are separate
 * tables, so switching would be a delete plus an insert that drops its goal and group links), Save updates it
 * in place and [onDelete] removes it. A zero balance is allowed (an emptied account, a paid-off loan).
 *
 * Mode defaults to
 * [initialIsLiability] — the mode the route was opened with, from the FAB (Activos/Pasivos toggle)
 * or a per-group add affordance — but stays user-switchable per spec; switching mode clears
 * [FormFields.selectedGroupId] since asset and liability types are disjoint. [initialGroupId] prefills
 * selection when opened from a per-group add affordance (design.md Data Flow: `group "+" -> AddPatrimonio(groupId=…)`). Save creates the asset or
 * liability via the matching repository using [idProvider] for the new String id (Room primary
 * keys for both entities are caller-supplied), then emits one [navigateBack] event.
 */
class AddPatrimonioSheetViewModel(
    private val assetRepository: AssetRepository,
    private val liabilityRepository: LiabilityRepository,
    fxRepository: FxRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val accountGroupRepository: AccountGroupRepository,
    private val accountTypeRepository: AccountTypeRepository,
    private val idProvider: () -> String = ::newPatrimonioItemId,
    initialIsLiability: Boolean = false,
    initialGroupId: String? = null,
    private val editingItemId: String? = null,
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

    /** The edited item's subtitle, which the form does not show but must keep. */
    private var editingSubtitle: String? = null

    private val deleteWarning = MutableStateFlow<String?>(null)

    init {
        if (editingItemId != null) viewModelScope.launch { loadEditedItem(editingItemId) }
    }

    /** One-shot nav-back signal, emitted after a successful [onSave] (design.md Data Flow). */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    val state: StateFlow<AddPatrimonioUiState> = combine(
        form,
        fxRepository.observeRates(),
        deleteWarning,
        accountTypeRepository.observeAll(),
    ) { f, rates, warning, customTypes ->
        buildState(f, rates, customTypes).copy(deleteWarning = warning)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ADD_PATRIMONIO_STOP_TIMEOUT_MS),
        initialValue = buildState(form.value, FxRates(emptyMap()), emptyList()),
    )

    fun onModeChange(isLiability: Boolean) {
        if (editingItemId != null || form.value.isLiability == isLiability) return
        form.value = form.value.copy(isLiability = isLiability, selectedGroupId = null)
    }

    fun onGroupSelect(groupId: String) {
        form.value = form.value.copy(selectedGroupId = groupId)
    }

    /** Any text the keyboard gives, kept short: an emoji (some are several characters) or a symbol. */
    fun onEmojiChange(text: String) {
        form.value = form.value.copy(emoji = text.trim().take(MAX_EMOJI_LENGTH))
    }

    /** Creates a type of the form's kind and selects it. */
    fun onCreateType(name: String, emoji: String, color: TypeColor) {
        val kind = if (form.value.isLiability) AccountKind.LIABILITY else AccountKind.ASSET
        viewModelScope.launch {
            val id = accountTypeRepository.create(kind, name, emoji, color)
            if (form.value.isLiability == (kind == AccountKind.LIABILITY)) {
                form.value = form.value.copy(selectedGroupId = id)
            }
        }
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
        val amountMinor = parseAmountToMinor(f.amountText, f.currency, allowZero = true) ?: return
        val name = f.name.trim()
        if (name.isEmpty()) return

        val emoji = f.emoji.ifBlank { null }
        viewModelScope.launch {
            val amount = CurrencyAmount(Money(amountMinor), f.currency)
            val id = editingItemId ?: idProvider()
            if (f.isLiability) {
                val liability = Liability(
                    id = id,
                    group = groupId,
                    name = name,
                    subtitle = editingSubtitle,
                    amount = amount,
                    emoji = emoji,
                )
                if (editingItemId != null) {
                    liabilityRepository.update(liability)
                } else {
                    liabilityRepository.insert(liability)
                }
            } else {
                val asset = Asset(
                    id = id,
                    group = groupId,
                    name = name,
                    subtitle = editingSubtitle,
                    amount = amount,
                    emoji = emoji,
                )
                if (editingItemId != null) assetRepository.update(asset) else assetRepository.insert(asset)
            }
            navigateBackChannel.send(Unit)
        }
    }

    /** Deletes the edited item; an asset also leaves the goals and groups that held it. */
    fun onDelete() {
        val id = editingItemId ?: return
        viewModelScope.launch {
            if (form.value.isLiability) liabilityRepository.deleteById(id) else assetRepository.deleteById(id)
            navigateBackChannel.send(Unit)
        }
    }

    private suspend fun loadEditedItem(id: String) {
        val fields = if (form.value.isLiability) {
            liabilityRepository.list().find { it.id == id }?.let { liability ->
                editingSubtitle = liability.subtitle
                formFields(true, liability.group, liability.name, liability.amount, liability.emoji)
            }
        } else {
            assetRepository.list().find { it.id == id }?.let { asset ->
                editingSubtitle = asset.subtitle
                val usage = accountUsage(
                    goals = savingsGoalRepository.observeAll().first(),
                    groups = accountGroupRepository.observeAll().first(),
                )[id]
                deleteWarning.value = usage?.let(::deleteWarningFor)
                formFields(false, asset.group, asset.name, asset.amount, asset.emoji)
            }
        }
        // Gone meanwhile (deleted elsewhere): nothing to edit.
        if (fields == null) navigateBackChannel.send(Unit) else form.value = fields
    }

    private fun buildState(f: FormFields, rates: FxRates, customTypes: List<CustomAccountType>): AddPatrimonioUiState {
        val amountMinor = parseAmountToMinor(f.amountText, f.currency, allowZero = true)
        val kind = if (f.isLiability) AccountKind.LIABILITY else AccountKind.ASSET
        return AddPatrimonioUiState(
            isLiability = f.isLiability,
            groupOptions = typeOptions(kind, customTypes).map { GroupOptionUi(it.id, it.label, it) },
            emoji = f.emoji,
            selectedGroupId = f.selectedGroupId,
            name = f.name,
            amountText = f.amountText,
            currency = f.currency,
            eurHint = eurHintFor(f.currency, amountMinor, rates),
            canSave = f.selectedGroupId != null && f.name.isNotBlank() && amountMinor != null,
            isEditing = editingItemId != null,
        )
    }
}

private fun formFields(isLiability: Boolean, groupId: String, name: String, amount: CurrencyAmount, emoji: String?) =
    FormFields(
        isLiability = isLiability,
        selectedGroupId = groupId,
        name = name,
        amountText = amountInputText(amount.amount, amount.currency),
        currency = amount.currency,
        emoji = emoji.orEmpty(),
    )

@OptIn(ExperimentalUuidApi::class)
private fun newPatrimonioItemId(): String = Uuid.random().toString()

/** "Está en la meta «Colchón» y en el grupo «Ahorro»; se quitará de ellos." */
internal fun deleteWarningFor(usage: AccountUsage): String? {
    val parts = listOfNotNull(
        usage.goalNames.takeIf { it.isNotEmpty() }?.let { quoted(if (it.size == 1) "la meta" else "las metas", it) },
        usage.groupNames.takeIf { it.isNotEmpty() }?.let { quoted(if (it.size == 1) "el grupo" else "los grupos", it) },
    )
    if (parts.isEmpty()) return null
    val count = usage.goalNames.size + usage.groupNames.size
    return "Está ${parts.joinToString(" y ")}; se quitará ${if (count == 1) "de él" else "de ellos"}."
}

/** "en las metas «A», «B» y «C»" */
private fun quoted(noun: String, names: List<String>): String {
    val all = names.map { "«$it»" }
    val list = if (all.size == 1) all.single() else all.dropLast(1).joinToString(", ") + " y " + all.last()
    return "en $noun $list"
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
