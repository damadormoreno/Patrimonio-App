package com.denebapps.patrimonio.ui.screens.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.groupMembers
import com.denebapps.patrimonio.domain.calc.parseAmountToMinor
import com.denebapps.patrimonio.domain.calc.savingsGoalCoverage
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val linkedAssetId: String?,
    val linkedGroupId: String?,
)

/** One same-currency asset offered by the create form's optional link picker (spec: Create Goal
 *  Form — "an asset link MUST be optional and, when offered, restricted to assets sharing the
 *  entered currency"). */
data class LinkableAssetUi(val id: String, val name: String)

/** One persisted account group offered by the create form's optional link picker. Groups carry no
 *  currency restriction (their balance is converted at the current rate) and the builtin "all
 *  accounts" group is never offered: it is synthesized, not a row, so it cannot be linked. */
data class LinkableGroupUi(val id: String, val name: String)

data class SavingsGoalsUiState(
    val goals: List<SavingsGoalRowUi>,
    val isEmpty: Boolean,
    val coverageWarning: PatrimonioCoverageUi,
    val groupCoverageWarning: PatrimonioCoverageUi,
    val newGoalName: String,
    val newGoalTargetText: String,
    val newGoalCurrency: Currency,
    val newGoalTargetDate: LocalDate?,
    val newGoalLinkedAssetId: String?,
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
    val linkedAssetId: String? = null,
    val linkedGroupId: String? = null,
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
 * introduced here. Reuses [savingsGoalCoverage] plus [coverageWarning] to derive the ONE shared
 * undercoverage warning (savings-goals-core spec: "Shared native-currency coverage") across every
 * currently-linked goal — never a per-goal covered amount or priority (spec: Shared Undercoverage
 * Warning Only). [initialGoalId]/[initialWithdraw] seed [SavingsGoalsUiState.selectedGoal]/
 * [SavingsGoalsUiState.withdraw] for the `GoalAllocate` destination; both default to `null`/`false`
 * for the section and `NewGoal` instances, where they are unused.
 *
 * A new goal links to an asset XOR a persisted group XOR nothing ([onNewGoalLinkChange] and
 * [onNewGoalGroupLinkChange] replace each other). Group coverage is reported separately in
 * [SavingsGoalsUiState.groupCoverageWarning] (one shared warning, like the asset one).
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

    /** Switching currency invalidates an ASSET link picked for the previous currency — same-currency-only
     *  invariant (`AddPatrimonioSheetViewModel.onModeChange` clearing `selectedGroupId` precedent). A
     *  group link survives: groups have no currency restriction. */
    fun onNewGoalCurrencyChange(currency: Currency) {
        newGoalForm.value = newGoalForm.value.copy(currency = currency, linkedAssetId = null)
    }

    fun onNewGoalDateChange(date: LocalDate?) {
        newGoalForm.value = newGoalForm.value.copy(targetDate = date)
    }

    /** Picks an asset (or, with null, no link at all) — either way drops any picked group. */
    fun onNewGoalLinkChange(assetId: String?) {
        newGoalForm.value = newGoalForm.value.copy(linkedAssetId = assetId, linkedGroupId = null)
    }

    /** Picks a group, dropping any picked asset: a goal links to an asset XOR a group. */
    fun onNewGoalGroupLinkChange(groupId: String) {
        newGoalForm.value = newGoalForm.value.copy(linkedAssetId = null, linkedGroupId = groupId)
    }

    fun onSaveNewGoal() {
        val form = newGoalForm.value
        val targetMinor = parseAmountToMinor(form.targetText, form.currency) ?: return
        if (form.name.isBlank() || targetMinor <= 0) return

        viewModelScope.launch {
            try {
                savingsGoalRepository.create(
                    CreateSavingsGoal(
                        name = form.name.trim(),
                        target = CurrencyAmount(Money(targetMinor), form.currency),
                        targetDate = form.targetDate,
                        linkedAssetId = form.linkedAssetId,
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

        val rows = goals.map { it.toRowUi() }

        // ONE shared warning across every currently-linked goal (spec: Shared Undercoverage Warning
        // Only) — pick any one linked goal per distinct linked asset id (savingsGoalCoverage's
        // reserved sum is per-asset, identical for every goal sharing that asset — SavingsGoalCalcTest
        // "shared coverage counts each goal id once without per-goal priority").
        val coverageWarning = goals
            .filter { it.linkedAssetId != null }
            .distinctBy { it.linkedAssetId }
            .map { linkedGoal -> coverageWarning(savingsGoalCoverage(linkedGoal, assets, goals)) }
            .firstOrNull { it == PatrimonioCoverageUi.Warning } ?: PatrimonioCoverageUi.None

        // Group coverage is expressed in each goal's own currency, so (unlike the per-asset case) goals
        // sharing a group are evaluated one by one; the section still shows ONE shared group warning.
        val groupWarnings = goals
            .filter { it.linkedGroupId != null }
            .map { linkedGoal ->
                val converted = needsConversion(linkedGoal, assets, groups, goals)
                coverageWarning(savingsGoalCoverage(linkedGoal, assets, goals, groups, rates), converted)
            }
            .filterIsInstance<PatrimonioCoverageUi.GroupWarning>()
        val groupCoverageWarning = if (groupWarnings.isEmpty()) {
            PatrimonioCoverageUi.None
        } else {
            PatrimonioCoverageUi.GroupWarning(groupWarnings.any { it.convertedAtCurrentRate })
        }

        val linkableAssets = assets
            .filter { it.amount.currency == forms.newGoal.currency }
            .map { LinkableAssetUi(it.id, it.name) }

        val linkableGroups = groups
            .filter { it.id != AccountGroup.ALL_ACCOUNTS_ID }
            .map { LinkableGroupUi(it.id, it.name) }

        val newGoalTargetMinor = parseAmountToMinor(forms.newGoal.targetText, forms.newGoal.currency)

        val selectedGoal = rows.firstOrNull { it.id == forms.selectedGoalId }
        val allocateAmountMinor = selectedGoal?.let {
            parseAmountToMinor(forms.allocate.amountText, it.target.currency)
        }

        return SavingsGoalsUiState(
            goals = rows,
            isEmpty = rows.isEmpty(),
            coverageWarning = coverageWarning,
            groupCoverageWarning = groupCoverageWarning,
            newGoalName = forms.newGoal.name,
            newGoalTargetText = forms.newGoal.targetText,
            newGoalCurrency = forms.newGoal.currency,
            newGoalTargetDate = forms.newGoal.targetDate,
            newGoalLinkedAssetId = forms.newGoal.linkedAssetId,
            newGoalLinkedGroupId = forms.newGoal.linkedGroupId,
            linkableAssets = linkableAssets,
            linkableGroups = linkableGroups,
            canSaveNewGoal = forms.newGoal.name.isNotBlank() && newGoalTargetMinor != null && newGoalTargetMinor > 0,
            selectedGoal = selectedGoal,
            withdraw = forms.allocate.withdraw,
            allocateAmountText = forms.allocate.amountText,
            canSubmitAllocate = selectedGoal != null &&
                !selectedGoal.closed &&
                allocateAmountMinor != null &&
                allocateAmountMinor > 0,
            errorMessage = forms.errorMessage,
        )
    }
}

private fun SavingsGoal.toRowUi() = SavingsGoalRowUi(
    id = id,
    name = name,
    target = target,
    progress = progress,
    progressPct = progressPercentage(progress, target.amount),
    targetReached = targetReached,
    closed = lifecycle != SavingsGoalLifecycle.OPEN,
    linkedAssetId = linkedAssetId,
    linkedGroupId = linkedGroupId,
)

/** True when [goal]'s group coverage converts anything: a member asset, or another goal sharing the group,
 *  is in a currency other than [goal]'s. */
private fun needsConversion(
    goal: SavingsGoal,
    assets: List<Asset>,
    groups: List<AccountGroup>,
    allGoals: List<SavingsGoal>,
): Boolean {
    val group = groups.firstOrNull { it.id == goal.linkedGroupId } ?: return false
    val currency = goal.target.currency
    return groupMembers(group, assets).any { it.amount.currency != currency } ||
        allGoals.any { it.linkedGroupId == group.id && it.target.currency != currency }
}

private fun progressPercentage(progress: Money, target: Money): Int {
    if (target.minorUnits <= 0L) return 0
    return (progress.minorUnits.toDouble() * 100.0 / target.minorUnits.toDouble()).roundToInt()
}
