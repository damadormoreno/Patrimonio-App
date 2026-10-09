package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.accountUsage
import com.denebapps.patrimonio.domain.calc.groupMembers
import com.denebapps.patrimonio.domain.calc.groupTotal
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.repository.AccountGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.ui.components.TypeLook
import com.denebapps.patrimonio.ui.components.typeLook
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val GRUPOS_STOP_TIMEOUT_MS = 5_000L

/** One member row inside an [AccountGroupRowUi]'s expanded content (spec: Account Groups List).
 *  [group] drives the icon lookup ([PatrimonioScreen]/[AddPatrimonioSheet] precedent). */
data class GruposMemberUi(
    val id: String,
    val name: String,
    val amountEur: Money,
    val group: String,
    val type: TypeLook = typeLook(group),
    val emoji: String? = null,
)

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
    /** Reordering arrows; the builtin group is always first and never moves. */
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
)

/** One checklist row in the NuevoGrupo form (spec: Create Account Group Form). */
data class AssetChecklistItemUi(
    val id: String,
    val name: String,
    val subtitle: String?,
    val group: String,
    val selected: Boolean,
    val type: TypeLook = typeLook(group),
    val emoji: String? = null,
    /** Open goals that follow the account and the other groups that hold it. */
    val goalNames: List<String> = emptyList(),
    val groupNames: List<String> = emptyList(),
)

data class GruposUiState(
    val groups: List<AccountGroupRowUi>,
    val title: String,
    val showBalance: Boolean,
    val assetChecklist: List<AssetChecklistItemUi>,
    val selectedCount: Int,
    val selectedTotal: Money,
    val canSaveNewGroup: Boolean,
    val pendingDeletion: GroupDeletionConfirmationUi? = null,
    /** The form edits an existing group instead of creating one. */
    val isEditing: Boolean = false,
)

/** Combined repo data snapshot, mirrors [PatrimonioViewModel]'s private data-bundle convention. */
private data class GruposData(
    val assets: List<Asset>,
    val accountGroups: List<AccountGroup>,
    val rates: FxRates,
    val goals: List<SavingsGoal> = emptyList(),
    val customTypes: Map<String, CustomAccountType> = emptyMap(),
)

/** NuevoGrupo form fields, kept separate from repo-derived [GruposData] (design.md Decision 1:
 *  [GruposViewModel] holds the NuevoGrupo form). */
private data class NewGroupForm(
    val title: String = "",
    val showBalance: Boolean = true,
    val selectedAssetIds: Set<String> = emptySet(),
    /** False until an edited group's current values are in the form; saving waits for it. */
    val loaded: Boolean = true,
)

/**
 * Backs both the `Grupos` (account-groups list) and `NuevoGrupo` (create form) destinations
 * (design.md Decision 1). Ports `design-reference/grupos.jsx`'s `GruposSheet`/`NuevoGrupoSheet`.
 * Reuses [groupMembers]/[groupTotal] verbatim from [com.denebapps.patrimonio.domain.calc] — no
 * reimplementation of the overlapping-membership contract. Save creates a new, non-builtin
 * [AccountGroup] via [AccountGroupRepository.insertGroup] using [idProvider] for the new String id
 * (same caller-supplied-id convention as [AddPatrimonioSheetViewModel]), then emits one
 * [navigateBack] event. Deleting a group that still has linked savings goals (any lifecycle) is gated
 * behind a confirmation exposed as [GruposUiState.pendingDeletion]; the repository unlinks those goals.
 *
 * With [editingGroupId] the form edits that group instead: it starts from the group's current name,
 * balance visibility and members, and saving updates it in place (same id, position and linked
 * goals). If the group no longer exists the form just navigates back.
 */
class GruposViewModel(
    private val assetRepository: AssetRepository,
    private val accountGroupRepository: AccountGroupRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    fxRepository: FxRepository,
    accountTypeRepository: AccountTypeRepository,
    private val editingGroupId: String? = null,
    private val idProvider: () -> String = ::newAccountGroupId,
) : ViewModel() {
    private val newGroupForm = MutableStateFlow(NewGroupForm(loaded = editingGroupId == null))
    private val pendingDeletion = MutableStateFlow<GroupDeletionConfirmationUi?>(null)
    private val reorderMutex = Mutex()

    private val navigateBackChannel = Channel<Unit>(Channel.BUFFERED)

    /** One-shot nav-back signal, emitted after a successful [onSaveNewGroup] (`AddPatrimonioSheetViewModel`
     *  precedent). */
    val navigateBack: Flow<Unit> = navigateBackChannel.receiveAsFlow()

    private val dataFlow = combine(
        assetRepository.observeAll(),
        accountGroupRepository.observeAll(),
        fxRepository.observeRates(),
        savingsGoalRepository.observeAll(),
        accountTypeRepository.observeAll(),
    ) { assets, accountGroups, rates, goals, types ->
        GruposData(assets, accountGroups, rates, goals, types.associateBy { it.id })
    }

    val state: StateFlow<GruposUiState> = combine(
        dataFlow,
        newGroupForm,
        pendingDeletion,
    ) { data, form, pending -> buildState(data, form, pending) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(GRUPOS_STOP_TIMEOUT_MS),
        initialValue = buildState(
            GruposData(emptyList(), emptyList(), FxRates(emptyMap())),
            newGroupForm.value,
            null,
        ),
    )

    init {
        if (editingGroupId != null) {
            viewModelScope.launch {
                val group = accountGroupRepository.observeAll().first()
                    .firstOrNull { it.id == editingGroupId && it.id != AccountGroup.ALL_ACCOUNTS_ID }
                if (group == null) {
                    navigateBackChannel.send(Unit)
                } else {
                    newGroupForm.value = NewGroupForm(
                        title = group.name,
                        showBalance = group.showBalance,
                        selectedAssetIds = group.memberAssetIds.orEmpty(),
                    )
                }
            }
        }
    }

    fun onDeleteGroup(id: String) {
        if (id == AccountGroup.ALL_ACCOUNTS_ID) return
        viewModelScope.launch {
            val linkedGoalNames = savingsGoalRepository.observeAll().first()
                .filter { it.linkedGroupId == id }
                .map { it.name }
            val groupName = accountGroupRepository.observeAll().first().firstOrNull { it.id == id }?.name
            if (linkedGoalNames.isEmpty() || groupName == null) {
                accountGroupRepository.deleteGroup(id)
            } else {
                pendingDeletion.value = GroupDeletionConfirmationUi(id, groupName, linkedGoalNames)
            }
        }
    }

    /** Moves a persisted group one place up or down and saves the whole order. Taps are serialized so
     *  each move starts from the order the previous one saved. */
    fun onMoveGroup(id: String, up: Boolean) {
        viewModelScope.launch {
            reorderMutex.withLock {
                val ids = accountGroupRepository.observeAll().first()
                    .map { it.id }
                    .filter { it != AccountGroup.ALL_ACCOUNTS_ID }
                val from = ids.indexOf(id)
                val to = if (up) from - 1 else from + 1
                if (from < 0 || to !in ids.indices) return@withLock
                accountGroupRepository.reorderGroups(ids.toMutableList().apply { add(to, removeAt(from)) })
            }
        }
    }

    fun onConfirmDeleteGroup() {
        val pending = pendingDeletion.value ?: return
        pendingDeletion.value = null
        viewModelScope.launch { accountGroupRepository.deleteGroup(pending.groupId) }
    }

    fun onDismissDeleteGroup() {
        pendingDeletion.value = null
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

    /** Creates the group, or updates the edited one (see [editingGroupId]). */
    fun onSaveNewGroup() {
        val form = newGroupForm.value
        if (!form.canSave()) return

        viewModelScope.launch {
            // New groups go last; an edit keeps its position (updateGroup ignores sortOrder).
            val nextSortOrder = accountGroupRepository.observeAll().first()
                .filter { it.id != AccountGroup.ALL_ACCOUNTS_ID }
                .maxOfOrNull { it.sortOrder + 1 } ?: 0
            val group = AccountGroup(
                id = editingGroupId ?: idProvider(),
                name = form.title.trim(),
                showBalance = form.showBalance,
                sortOrder = nextSortOrder,
                memberAssetIds = form.selectedAssetIds,
            )
            if (editingGroupId == null) {
                accountGroupRepository.insertGroup(group)
            } else {
                try {
                    accountGroupRepository.updateGroup(group)
                } catch (_: AccountGroupNotFoundException) {
                    // Deleted meanwhile (e.g. from another screen): nothing left to edit.
                }
            }
            navigateBackChannel.send(Unit)
        }
    }

    private fun buildState(
        data: GruposData,
        form: NewGroupForm,
        pendingDeletion: GroupDeletionConfirmationUi?,
    ): GruposUiState {
        val (assets, accountGroups, rates, goals) = data
        val customTypes = data.customTypes

        val movableIds = accountGroups.map { it.id }.filter { it != AccountGroup.ALL_ACCOUNTS_ID }
        val groups = accountGroups.map { group ->
            val members = groupMembers(group, assets)
            val position = movableIds.indexOf(group.id)
            AccountGroupRowUi(
                id = group.id,
                name = group.name,
                builtin = group.id == AccountGroup.ALL_ACCOUNTS_ID,
                showBalance = group.showBalance,
                total = groupTotal(group, assets, rates),
                members = members.map {
                    val type = typeLook(it.group, customTypes)
                    GruposMemberUi(it.id, it.name, it.amount.toEur(rates), it.group, type, it.emoji)
                },
                canMoveUp = position > 0,
                canMoveDown = position in 0 until movableIds.lastIndex,
            )
        }

        val usage = accountUsage(goals, accountGroups, excludeGroupId = editingGroupId)
        val checklist = assets.map { asset ->
            AssetChecklistItemUi(
                id = asset.id,
                name = asset.name,
                subtitle = asset.subtitle,
                group = asset.group,
                selected = asset.id in form.selectedAssetIds,
                type = typeLook(asset.group, customTypes),
                emoji = asset.emoji,
                goalNames = usage[asset.id]?.goalNames.orEmpty(),
                groupNames = usage[asset.id]?.groupNames.orEmpty(),
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
            canSaveNewGroup = form.canSave(),
            pendingDeletion = pendingDeletion,
            isEditing = editingGroupId != null,
        )
    }
}

private fun NewGroupForm.canSave(): Boolean = loaded && title.isNotBlank() && selectedAssetIds.isNotEmpty()

@OptIn(ExperimentalUuidApi::class)
private fun newAccountGroupId(): String = Uuid.random().toString()
