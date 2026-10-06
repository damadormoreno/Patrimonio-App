package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.groupMembers
import com.denebapps.patrimonio.domain.calc.groupTotal
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
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

private const val GRUPOS_STOP_TIMEOUT_MS = 5_000L

/** One member row inside an [AccountGroupRowUi]'s expanded content (spec: Account Groups List).
 *  [group] drives the icon lookup ([PatrimonioScreen]/[AddPatrimonioSheet] precedent). */
data class GruposMemberUi(val id: String, val name: String, val amountEur: Money, val group: Asset.AssetGroup)

/** One expandable group row (spec: Account Groups List, Group Edit and Delete). [total]/[members]
 *  are ALWAYS computed from the group's resolved membership regardless of [showBalance] — the sheet
 *  decides whether to render the masked "···" placeholder or [total] for the collapsed row. */
data class AccountGroupRowUi(
    val id: String,
    val name: String,
    val builtin: Boolean,
    val showBalance: Boolean,
    val total: Money,
    val members: List<GruposMemberUi>,
)

/** One checklist row in the NuevoGrupo form (spec: Create Account Group Form). */
data class AssetChecklistItemUi(
    val id: String,
    val name: String,
    val subtitle: String?,
    val group: Asset.AssetGroup,
    val selected: Boolean,
)

data class GruposUiState(
    val groups: List<AccountGroupRowUi>,
    val title: String,
    val showBalance: Boolean,
    val assetChecklist: List<AssetChecklistItemUi>,
    val selectedCount: Int,
    val selectedTotal: Money,
    val canSaveNewGroup: Boolean,
)

/** Combined repo data snapshot, mirrors [PatrimonioViewModel]'s private data-bundle convention. */
private data class GruposData(
    val assets: List<Asset>,
    val accountGroups: List<AccountGroup>,
    val rates: FxRates,
)

/** NuevoGrupo form fields, kept separate from repo-derived [GruposData] (design.md Decision 1:
 *  [GruposViewModel] holds the NuevoGrupo form). */
private data class NewGroupForm(
    val title: String = "",
    val showBalance: Boolean = true,
    val selectedAssetIds: Set<String> = emptySet(),
)

/**
 * Backs both the `Grupos` (account-groups list) and `NuevoGrupo` (create form) destinations
 * (design.md Decision 1). Ports `design-reference/grupos.jsx`'s `GruposSheet`/`NuevoGrupoSheet`.
 * Reuses [groupMembers]/[groupTotal] verbatim from [com.denebapps.patrimonio.domain.calc] — no
 * reimplementation of the overlapping-membership contract. Save creates a new, non-builtin
 * [AccountGroup] via [AccountGroupRepository.insertGroup] using [idProvider] for the new String id
 * (same caller-supplied-id convention as [AddPatrimonioSheetViewModel]), then emits one
 * [navigateBack] event.
 */
class GruposViewModel(
    private val assetRepository: AssetRepository,
    private val accountGroupRepository: AccountGroupRepository,
    fxRepository: FxRepository,
    private val idProvider: () -> String = ::newAccountGroupId,
) : ViewModel() {
    private val newGroupForm = MutableStateFlow(NewGroupForm())

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot nav-back signal, emitted after a successful [onSaveNewGroup] (`AddPatrimonioSheetViewModel`
     *  precedent). */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val dataFlow = combine(
        assetRepository.observeAll(),
        accountGroupRepository.observeAll(),
        fxRepository.observeRates(),
    ) { assets, accountGroups, rates -> GruposData(assets, accountGroups, rates) }

    val state: StateFlow<GruposUiState> = combine(
        dataFlow,
        newGroupForm,
    ) { data, form -> buildState(data, form) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(GRUPOS_STOP_TIMEOUT_MS),
        initialValue = buildState(GruposData(emptyList(), emptyList(), FxRates(emptyMap())), NewGroupForm()),
    )

    fun onDeleteGroup(id: String) {
        if (id == AccountGroup.ALL_ACCOUNTS_ID) return
        viewModelScope.launch { accountGroupRepository.deleteGroup(id) }
    }

    fun onTitleChange(text: String) {
        newGroupForm.value = newGroupForm.value.copy(title = text)
    }

    fun onShowBalanceToggle() {
        newGroupForm.value = newGroupForm.value.copy(showBalance = !newGroupForm.value.showBalance)
    }

    fun onAssetToggle(assetId: String) {
        val current = newGroupForm.value.selectedAssetIds
        val updated = if (assetId in current) current - assetId else current + assetId
        newGroupForm.value = newGroupForm.value.copy(selectedAssetIds = updated)
    }

    fun onSaveNewGroup() {
        val form = newGroupForm.value
        if (form.title.isBlank() || form.selectedAssetIds.isEmpty()) return

        viewModelScope.launch {
            accountGroupRepository.insertGroup(
                AccountGroup(
                    id = idProvider(),
                    name = form.title.trim(),
                    showBalance = form.showBalance,
                    sortOrder = 0,
                    memberAssetIds = form.selectedAssetIds,
                ),
            )
            navigateBackChannel.send(Unit)
        }
    }

    private fun buildState(data: GruposData, form: NewGroupForm): GruposUiState {
        val (assets, accountGroups, rates) = data

        val groups = accountGroups.map { group ->
            val members = groupMembers(group, assets)
            AccountGroupRowUi(
                id = group.id,
                name = group.name,
                builtin = group.id == AccountGroup.ALL_ACCOUNTS_ID,
                showBalance = group.showBalance,
                total = groupTotal(group, assets, rates),
                members = members.map { GruposMemberUi(it.id, it.name, it.amount.toEur(rates), it.group) },
            )
        }

        val checklist = assets.map { asset ->
            AssetChecklistItemUi(
                id = asset.id,
                name = asset.name,
                subtitle = asset.subtitle,
                group = asset.group,
                selected = asset.id in form.selectedAssetIds,
            )
        }
        val selectedTotal = assets
            .filter { it.id in form.selectedAssetIds }
            .fold(Money.ZERO) { acc, asset -> acc + asset.amount.toEur(rates) }

        return GruposUiState(
            groups = groups,
            title = form.title,
            showBalance = form.showBalance,
            assetChecklist = checklist,
            selectedCount = form.selectedAssetIds.size,
            selectedTotal = selectedTotal,
            canSaveNewGroup = form.title.isNotBlank() && form.selectedAssetIds.isNotEmpty(),
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newAccountGroupId(): String = Uuid.random().toString()
