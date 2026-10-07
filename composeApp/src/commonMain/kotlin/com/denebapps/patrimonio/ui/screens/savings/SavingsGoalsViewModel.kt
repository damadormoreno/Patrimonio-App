package com.denebapps.patrimonio.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.AccountUsage
import com.denebapps.patrimonio.domain.calc.accountUsage
import com.denebapps.patrimonio.domain.calc.goalNamesByLinkedGroup
import com.denebapps.patrimonio.domain.calc.goalsSharingLinkedBalance
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.trackedBalance
import com.denebapps.patrimonio.domain.calc.tracksLinkedBalance
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.UpdateSavingsGoal
import com.denebapps.patrimonio.ui.components.amountInputText
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
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

private const val SAVINGS_GOALS_STOP_TIMEOUT_MS = 5_000L

/** One row in the Metas de ahorro list/detail (spec: Goals List Shows Name, Target, and Progress).
 *  [progressPct] intentionally is NOT clamped to 100 — overfunding is permitted and MUST remain
 *  visible (spec: Allocate and Withdraw Funds). [closed] gates mutation actions (spec: Cancel and
 *  Closed-Goal Restrictions — a closed goal must not offer allocate/withdraw/link/relink/unlink/
 *  close/cancel while still showing its preserved [progress]). */
data class SavingsGoalRowUi(
    val id: String,
    val name: String,
    val target: CurrencyAmount,
    val progress: Money,
    val progressPct: Int,
    val targetReached: Boolean,
    val closed: Boolean,
    val linkedAssetIds: Set<String>,
    val linkedGroupId: String?,
    /** [progress] is the balance of the linked assets or group ([linkedTargetLabel]), not allocations:
     *  allocate and withdraw do not apply. */
    val tracksBalance: Boolean = false,
    /** What the goal follows, ready to read after "de": «Cuenta», «A» y «B», or "4 cuentas". */
    val linkedTargetLabel: String? = null,
    /** The tracked balance needs an exchange rate that is not available; [progress] shows zero. */
    val balanceUnavailable: Boolean = false,
)

/** Several open goals follow the balance of the same assets or group ([targetLabel], as in
 *  [SavingsGoalRowUi.linkedTargetLabel]); each one counts it in full. */
data class SharedBalanceNoticeUi(val targetLabel: String, val goalNames: List<String>)

/** One asset offered by the goal form's link picker, of any currency: the tracked balance converts it
 *  to the goal's currency. Several can be picked at once. [goalNames]/[groupNames]: the other open goals
 *  that already follow it and the groups that hold it. */
data class LinkableAssetUi(
    val id: String,
    val name: String,
    val currency: Currency,
    val goalNames: List<String> = emptyList(),
    val groupNames: List<String> = emptyList(),
)

/** One persisted account group offered by the create form's optional link picker. Groups carry no
 *  currency restriction (their balance is converted at the current rate) and the builtin "all
 *  accounts" group is never offered: it is synthesized, not a row, so it cannot be linked. */
data class LinkableGroupUi(val id: String, val name: String, val goalNames: List<String> = emptyList())

data class SavingsGoalsUiState(
    val goals: List<SavingsGoalRowUi>,
    val isEmpty: Boolean,
    val sharedBalanceNotices: List<SharedBalanceNoticeUi>,
    val newGoalName: String,
    val newGoalTargetText: String,
    val newGoalCurrency: Currency,
    val newGoalTargetDate: LocalDate?,
    val newGoalLinkedAssetIds: Set<String>,
    val newGoalLinkedGroupId: String?,
    val linkableAssets: List<LinkableAssetUi>,
    val linkableGroups: List<LinkableGroupUi>,
    val canSaveNewGoal: Boolean,
    val selectedGoal: SavingsGoalRowUi?,
    val withdraw: Boolean,
    val allocateAmountText: String,
    val canSubmitAllocate: Boolean,
    val errorMessage: String?,
)

private data class SavingsGoalsData(
    val goals: List<SavingsGoal>,
    val assets: List<Asset>,
    val groups: List<AccountGroup>,
    val rates: FxRates,
)

/** NewGoal form fields, kept separate from repo-derived [SavingsGoalsData] (design.md Decision 1:
 *  [SavingsGoalsViewModel] holds the NewGoal form, `GruposViewModel`/`NuevoGrupo` precedent). */
private data class NewGoalForm(
    val name: String = "",
    val targetText: String = "",
    val currency: Currency = Currency.EUR,
    val targetDate: LocalDate? = null,
    val linkedAssetIds: Set<String> = emptySet(),
    val linkedGroupId: String? = null,
    /** Set once [SavingsGoalsViewModel.onStartEditing] has loaded an existing goal: saving updates it. */
    val editingGoalId: String? = null,
)

/** GoalAllocate form field; [withdraw] toggles between the allocate/withdraw atomic command
 *  (`AddPatrimonioSheetViewModel`'s `ModeSegmented`-backing-field precedent) and is seeded from
 *  [SavingsGoalsViewModel]'s [initialWithdraw] constructor arg but stays user-switchable. */
private data class AllocateForm(val withdraw: Boolean, val amountText: String = "")

private data class Forms(
    val selectedGoalId: String?,
    val newGoal: NewGoalForm,
    val allocate: AllocateForm,
    val errorMessage: String?,
)

/**
 * Backs the Metas de ahorro section embedded in [com.denebapps.patrimonio.ui.screens.patrimonio.PatrimonioScreen],
 * the `NewGoal` create-form destination, and the `GoalAllocate` allocate/withdraw destination
 * (design.md Decision 1 & 8) — one new instance per pushed destination (`GruposViewModel`/`Grupos`+
 * `NuevoGrupo` precedent), all sharing this single class. Every command maps 1:1 to a
 * [SavingsGoalRepository] atomic command (design.md Interfaces) — no new domain behavior is
 * introduced here. An open goal linked to an asset or a group shows that balance as its progress
 * ([trackedBalance]) and takes no allocations; goals sharing a balance get one notice per shared
 * target. [initialGoalId]/[initialWithdraw] seed [SavingsGoalsUiState.selectedGoal]/
 * [SavingsGoalsUiState.withdraw] for the `GoalAllocate` destination; both default to `null`/`false`
 * for the section and `NewGoal` instances, where they are unused.
 *
 * A goal follows any number of assets XOR one persisted group XOR nothing: [onNewGoalAssetToggle]
 * drops a picked group and [onNewGoalGroupLinkChange] drops the picked assets.
 */
class SavingsGoalsViewModel(
    private val savingsGoalRepository: SavingsGoalRepository,
    private val assetRepository: AssetRepository,
    accountGroupRepository: AccountGroupRepository,
    fxRepository: FxRepository,
    initialGoalId: String? = null,
    initialWithdraw: Boolean = false,
) : ViewModel() {
    private val selectedGoalId = MutableStateFlow(initialGoalId)
    private val newGoalForm = MutableStateFlow(NewGoalForm())
    private val allocateForm = MutableStateFlow(AllocateForm(withdraw = initialWithdraw))
    private val errorMessage = MutableStateFlow<String?>(null)

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot nav-back signal, emitted after a successful [onSaveNewGoal] or [onDeleteGoal]
     *  (`AddPatrimonioSheetViewModel` precedent). Allocate/withdraw/cancel do NOT navigate back — the
     *  sheet stays open so the updated progress is visible in place (spec: Reactive Goals State — "the
     *  affected goal's progress updates in place without reload"). */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val dataFlow = combine(
        savingsGoalRepository.observeAll(),
        assetRepository.observeAll(),
        accountGroupRepository.observeAll(),
        fxRepository.observeRates(),
    ) { goals, assets, groups, rates -> SavingsGoalsData(goals, assets, groups, rates) }

    private val formsFlow = combine(
        selectedGoalId,
        newGoalForm,
        allocateForm,
        errorMessage,
    ) { goalId, newGoal, allocate, error -> Forms(goalId, newGoal, allocate, error) }

    val state: StateFlow<SavingsGoalsUiState> = combine(
        dataFlow,
        formsFlow,
    ) { data, forms -> buildState(data, forms) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SAVINGS_GOALS_STOP_TIMEOUT_MS),
        initialValue = buildState(
            SavingsGoalsData(emptyList(), emptyList(), emptyList(), FxRates(emptyMap())),
            Forms(initialGoalId, NewGoalForm(), AllocateForm(withdraw = initialWithdraw), null),
        ),
    )

    fun onNewGoalNameChange(text: String) {
        newGoalForm.value = newGoalForm.value.copy(name = text)
    }

    fun onNewGoalTargetChange(text: String) {
        newGoalForm.value = newGoalForm.value.copy(targetText = text)
    }

    /** Links survive a currency change: linked balances are converted to the goal's currency. */
    fun onNewGoalCurrencyChange(currency: Currency) {
        newGoalForm.value = newGoalForm.value.copy(currency = currency)
    }

    fun onNewGoalDateChange(date: LocalDate?) {
        newGoalForm.value = newGoalForm.value.copy(targetDate = date)
    }

    /** Adds or removes one asset from the picked ones, dropping any picked group. */
    fun onNewGoalAssetToggle(assetId: String) {
        val form = newGoalForm.value
        val picked = assetId in form.linkedAssetIds
        val assetIds = if (picked) form.linkedAssetIds - assetId else form.linkedAssetIds + assetId
        newGoalForm.value = form.copy(linkedAssetIds = assetIds, linkedGroupId = null)
    }

    /** Picks a group, dropping any picked asset: a goal links to assets XOR a group. */
    fun onNewGoalGroupLinkChange(groupId: String) {
        newGoalForm.value = newGoalForm.value.copy(linkedAssetIds = emptySet(), linkedGroupId = groupId)
    }

    /** Leaves the goal unlinked: it takes allocations instead of following a balance. */
    fun onNewGoalUnlink() {
        newGoalForm.value = newGoalForm.value.copy(linkedAssetIds = emptySet(), linkedGroupId = null)
    }

    /** Fills the form with an existing open goal so saving edits it. Calling it again for the same goal
     *  (e.g. after a configuration change) keeps what the user has typed. */
    fun onStartEditing(goalId: String) {
        if (newGoalForm.value.editingGoalId == goalId) return
        viewModelScope.launch {
            val goal = savingsGoalRepository.observeAll().first().firstOrNull { it.id == goalId } ?: return@launch
            newGoalForm.value = NewGoalForm(
                name = goal.name,
                targetText = amountInputText(goal.target.amount, goal.target.currency),
                currency = goal.target.currency,
                targetDate = goal.targetDate,
                linkedAssetIds = goal.linkedAssetIds,
                linkedGroupId = goal.linkedGroupId,
                editingGoalId = goal.id,
            )
        }
    }

    /** Creates the goal, or updates the one loaded by [onStartEditing]. */
    fun onSaveNewGoal() {
        val form = newGoalForm.value
        val targetMinor = parseAmountToMinor(form.targetText, form.currency) ?: return
        if (form.name.isBlank() || targetMinor <= 0) return
        form.editingGoalId?.let { goalId ->
            saveEdit(goalId, form, Money(targetMinor))
            return
        }

        viewModelScope.launch {
            try {
                savingsGoalRepository.create(
                    CreateSavingsGoal(
                        name = form.name.trim(),
                        target = CurrencyAmount(Money(targetMinor), form.currency),
                        targetDate = form.targetDate,
                        linkedAssetIds = form.linkedAssetIds,
                        linkedGroupId = form.linkedGroupId,
                    ),
                )
                errorMessage.value = null
                navigateBackChannel.send(Unit)
            } catch (e: RuntimeException) {
                errorMessage.value = "No se pudo crear la meta. Revisa el importe y la divisa."
            }
        }
    }

    private fun saveEdit(goalId: String, form: NewGoalForm, target: Money) {
        viewModelScope.launch {
            try {
                savingsGoalRepository.update(
                    goalId,
                    UpdateSavingsGoal(
                        name = form.name.trim(),
                        targetAmount = target,
                        targetDate = form.targetDate,
                        linkedAssetIds = form.linkedAssetIds,
                        linkedGroupId = form.linkedGroupId,
                    ),
                )
                errorMessage.value = null
                navigateBackChannel.send(Unit)
            } catch (e: RuntimeException) {
                errorMessage.value = "No se pudo guardar la meta."
            }
        }
    }

    fun onWithdrawModeChange(withdraw: Boolean) {
        allocateForm.value = allocateForm.value.copy(withdraw = withdraw)
    }

    fun onAllocateAmountChange(text: String) {
        allocateForm.value = allocateForm.value.copy(amountText = text)
    }

    fun onSubmitAllocate() {
        val goalId = selectedGoalId.value ?: return
        val selected = state.value.selectedGoal ?: return
        val form = allocateForm.value
        val amountMinor = parseAmountToMinor(form.amountText, selected.target.currency) ?: return
        if (amountMinor <= 0) return

        viewModelScope.launch {
            try {
                val amount = Money(amountMinor)
                if (form.withdraw) {
                    savingsGoalRepository.withdraw(goalId, amount)
                } else {
                    savingsGoalRepository.allocate(goalId, amount)
                }
                errorMessage.value = null
                allocateForm.value = form.copy(amountText = "")
            } catch (e: RuntimeException) {
                errorMessage.value = if (form.withdraw) {
                    "No se pudo retirar ese importe."
                } else {
                    "No se pudo asignar ese importe."
                }
            }
        }
    }

    fun onCancelGoal() {
        val goalId = selectedGoalId.value ?: return
        viewModelScope.launch {
            try {
                savingsGoalRepository.cancel(goalId)
                errorMessage.value = null
            } catch (e: RuntimeException) {
                errorMessage.value = "No se pudo cancelar la meta."
            }
        }
    }

    /** Deletes the selected goal and its history, then navigates back. Callers confirm first. */
    fun onDeleteGoal() {
        val goalId = selectedGoalId.value ?: return
        viewModelScope.launch {
            try {
                savingsGoalRepository.delete(goalId)
                errorMessage.value = null
                navigateBackChannel.send(Unit)
            } catch (e: RuntimeException) {
                errorMessage.value = "No se pudo eliminar la meta."
            }
        }
    }

    private fun buildState(data: SavingsGoalsData, forms: Forms): SavingsGoalsUiState {
        val (goals, assets, groups, rates) = data

        val targetNames = assets.associate { it.id to it.name } + groups.associate { it.id to it.name }
        val rows = goals.map { goal -> goal.toRowUi(assets, groups, rates, targetNames) }
        val sharedBalanceNotices = goalsSharingLinkedBalance(goals).map { sharing ->
            SharedBalanceNoticeUi(
                targetLabel = linkedTargetLabel(sharing.targetIds.mapNotNull(targetNames::get).sorted()).orEmpty(),
                goalNames = sharing.goals.map { it.name },
            )
        }

        val editingGoalId = forms.newGoal.editingGoalId
        val usage = accountUsage(goals, groups, excludeGoalId = editingGoalId)
        val linkableAssets = assets.map { asset ->
            val assetUsage = usage[asset.id] ?: AccountUsage()
            LinkableAssetUi(asset.id, asset.name, asset.amount.currency, assetUsage.goalNames, assetUsage.groupNames)
        }

        val goalsByGroup = goalNamesByLinkedGroup(goals, excludeGoalId = editingGoalId)
        val linkableGroups = groups
            .filter { it.id != AccountGroup.ALL_ACCOUNTS_ID }
            .map { LinkableGroupUi(it.id, it.name, goalsByGroup[it.id].orEmpty()) }

        val newGoalTargetMinor = parseAmountToMinor(forms.newGoal.targetText, forms.newGoal.currency)

        val selectedGoal = rows.firstOrNull { it.id == forms.selectedGoalId }
        val allocateAmountMinor = selectedGoal?.let {
            parseAmountToMinor(forms.allocate.amountText, it.target.currency)
        }

        return SavingsGoalsUiState(
            goals = rows,
            isEmpty = rows.isEmpty(),
            sharedBalanceNotices = sharedBalanceNotices,
            newGoalName = forms.newGoal.name,
            newGoalTargetText = forms.newGoal.targetText,
            newGoalCurrency = forms.newGoal.currency,
            newGoalTargetDate = forms.newGoal.targetDate,
            newGoalLinkedAssetIds = forms.newGoal.linkedAssetIds,
            newGoalLinkedGroupId = forms.newGoal.linkedGroupId,
            linkableAssets = linkableAssets,
            linkableGroups = linkableGroups,
            canSaveNewGoal = forms.newGoal.name.isNotBlank() && newGoalTargetMinor != null && newGoalTargetMinor > 0,
            selectedGoal = selectedGoal,
            withdraw = forms.allocate.withdraw,
            allocateAmountText = forms.allocate.amountText,
            canSubmitAllocate = selectedGoal != null &&
                !selectedGoal.closed &&
                !selectedGoal.tracksBalance &&
                allocateAmountMinor != null &&
                allocateAmountMinor > 0,
            errorMessage = forms.errorMessage,
        )
    }
}

/** A goal that tracks a linked balance shows that balance as its progress (zero when it cannot be
 *  computed); any other goal shows its own allocations. */
private fun SavingsGoal.toRowUi(
    assets: List<Asset>,
    groups: List<AccountGroup>,
    rates: FxRates,
    targetNames: Map<String, String>,
): SavingsGoalRowUi {
    val balance = trackedBalance(this, assets, groups, rates)
    val linkedTargetIds = linkedAssetIds + listOfNotNull(linkedGroupId)
    val shown = if (tracksLinkedBalance) balance ?: Money.ZERO else progress
    return SavingsGoalRowUi(
        id = id,
        name = name,
        target = target,
        progress = shown,
        progressPct = progressPercentage(shown, target.amount),
        targetReached = shown >= target.amount,
        closed = lifecycle != SavingsGoalLifecycle.OPEN,
        linkedAssetIds = linkedAssetIds,
        linkedGroupId = linkedGroupId,
        tracksBalance = tracksLinkedBalance,
        linkedTargetLabel = linkedTargetLabel(linkedTargetIds.mapNotNull(targetNames::get).sorted()),
        balanceUnavailable = tracksLinkedBalance && balance == null,
    )
}

private fun progressPercentage(progress: Money, target: Money): Int {
    if (target.minorUnits <= 0L) return 0
    return (progress.minorUnits.toDouble() * 100.0 / target.minorUnits.toDouble()).roundToInt()
}

/** «A»; «A» y «B»; «A», «B» y «C»; from [MAX_NAMED_TARGETS] + 1 names on, "N cuentas". Null when empty. */
internal fun linkedTargetLabel(names: List<String>): String? {
    if (names.isEmpty()) return null
    if (names.size > MAX_NAMED_TARGETS) return "${names.size} cuentas"
    val quoted = names.map { "«$it»" }
    return if (quoted.size == 1) quoted.single() else quoted.dropLast(1).joinToString(", ") + " y " + quoted.last()
}

private const val MAX_NAMED_TARGETS = 3

/** Where a linked goal's progress comes from, or null for goals that take allocations. */
fun trackedBalanceCaption(goal: SavingsGoalRowUi): String? = when {
    !goal.tracksBalance -> null
    goal.balanceUnavailable -> "Sin tipo de cambio para calcular el saldo de ${goal.linkedTargetLabel.orEmpty()}"
    else -> "Sigue el saldo de ${goal.linkedTargetLabel.orEmpty()}"
}
