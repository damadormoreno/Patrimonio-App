package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.accountUsage
import com.denebapps.patrimonio.domain.calc.groupHoldings
import com.denebapps.patrimonio.domain.calc.groupMembers
import com.denebapps.patrimonio.domain.calc.groupTotal
import com.denebapps.patrimonio.domain.calc.holdingUsage
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.calc.value
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.repository.AccountGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AccountTypeRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.ui.components.PORTFOLIO_LOOK
import com.denebapps.patrimonio.ui.components.PORTFOLIO_TYPE_ID
import com.denebapps.patrimonio.ui.components.TypeLook
import com.denebapps.patrimonio.ui.components.typeLook
import com.denebapps.patrimonio.ui.screens.portfolio.MIN_PORTFOLIOS_TO_CHOOSE
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
    /** A portfolio product rather than an account: drawn with the Cartera look and opened in its own detail.
     *  [priced] is false for one with no price yet, whose [amountEur] is zero. */
    val isHolding: Boolean = false,
    val priced: Boolean = true,
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

/**
 * One checklist row of a portfolio product in the NuevoGrupo form. [valueEur] is `null` for a product with no price
 * yet (it adds nothing to the group until it has one); [portfolioName] is set only while there are several portfolios.
 * [goalNames]/[groupNames] are the goals that follow it and the other groups that hold it.
 */
data class HoldingChecklistItemUi(
    val id: String,
    val name: String,
    val portfolioName: String?,
    val valueEur: Money?,
    val selected: Boolean,
    val goalNames: List<String> = emptyList(),
    val groupNames: List<String> = emptyList(),
) {
    /** "Mi cartera · 1.234,56 €", or "sin precio" in place of the value. */
    val subtitle: String
        get() = listOfNotNull(portfolioName, valueEur?.let(::formatMoneyEsGrupos) ?: NO_PRICE_LABEL).joinToString(" · ")
}

private const val NO_PRICE_LABEL = "sin precio"

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
    /** The portfolio's products, empty (and the form's Cartera section hidden) when it has none. */
    val holdingChecklist: List<HoldingChecklistItemUi> = emptyList(),
    /** "2 cuentas y 1 producto seleccionados". */
    val selectionLabel: String = "0 cuentas seleccionadas",
)

/** "0 cuentas seleccionadas", "1 producto seleccionado", "2 cuentas y 3 productos seleccionados". */
fun selectionLabel(accounts: Int, products: Int): String {
    val cuentas = if (accounts == 1) "1 cuenta" else "$accounts cuentas"
    val productos = if (products == 1) "1 producto" else "$products productos"
    return when {
        products == 0 -> "$cuentas ${if (accounts == 1) "seleccionada" else "seleccionadas"}"
        accounts == 0 -> "$productos ${if (products == 1) "seleccionado" else "seleccionados"}"
        else -> "$cuentas y $productos seleccionados"
    }
}

/** Combined repo data snapshot, mirrors [PatrimonioViewModel]'s private data-bundle convention. */
private data class GruposData(
    val assets: List<Asset>,
    val accountGroups: List<AccountGroup>,
    val rates: FxRates,
    val goals: List<SavingsGoal> = emptyList(),
    val customTypes: Map<String, CustomAccountType> = emptyMap(),
    val holdings: List<PortfolioHolding> = emptyList(),
    val portfolios: List<Portfolio> = emptyList(),
)

/** NuevoGrupo form fields, kept separate from repo-derived [GruposData] (design.md Decision 1:
 *  [GruposViewModel] holds the NuevoGrupo form). */
private data class NewGroupForm(
    val title: String = "",
    val showBalance: Boolean = true,
    val selectedAssetIds: Set<String> = emptySet(),
    val selectedHoldingIds: Set<String> = emptySet(),
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
 *
 * A group also holds portfolio products: [groupTotal] and the form's selected total count their value (a product
 * with no price yet counts zero), and [onHoldingToggle] picks them from [GruposUiState.holdingChecklist]; editing a
 * group keeps the ones it has. A group's member rows list its accounts first and then its products.
 */
class GruposViewModel(
    private val assetRepository: AssetRepository,
    private val accountGroupRepository: AccountGroupRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    portfolioRepository: PortfolioRepository,
    portfolios: PortfolioCollectionRepository,
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
    }.combine(portfolioRepository.observeAll()) { data, holdings ->
        data.copy(holdings = holdings)
    }.combine(portfolios.observePortfolios()) { data, allPortfolios ->
        data.copy(portfolios = allPortfolios)
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
                        selectedHoldingIds = group.memberHoldingIds,
                    )
                }
            }
        }
    }

    fun onDeleteGroup(id: String) {
        if (id == AccountGroup.ALL_ACCOUNTS_ID) return
        viewModelScope.launch {
            val linkedGoalNames = savingsGoalRepository.observeAll().first()
                .filter { id in it.linkedGroupIds }
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

    fun onHoldingToggle(holdingId: String) {
        val current = newGroupForm.value.selectedHoldingIds
        val updated = if (holdingId in current) current - holdingId else current + holdingId
        newGroupForm.value = newGroupForm.value.copy(selectedHoldingIds = updated)
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
                memberHoldingIds = form.selectedHoldingIds,
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
        val holdings = data.holdings
        val customTypes = data.customTypes

        val movableIds = accountGroups.map { it.id }.filter { it != AccountGroup.ALL_ACCOUNTS_ID }
        val groups = accountGroups.map { group ->
            val members = groupMembers(group, assets)
            val productMembers = groupHoldings(group, holdings.closedLast())
            val position = movableIds.indexOf(group.id)
            AccountGroupRowUi(
                id = group.id,
                name = group.name,
                builtin = group.id == AccountGroup.ALL_ACCOUNTS_ID,
                showBalance = group.showBalance,
                total = groupTotal(group, assets, rates, holdings),
                members = members.map {
                    val type = typeLook(it.group, customTypes)
                    GruposMemberUi(it.id, it.name, it.amount.toEur(rates), it.group, type, it.emoji)
                } + productMembers.map { it.toMemberUi(rates) },
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
        val holdingUse = holdingUsage(goals, accountGroups, excludeGroupId = editingGroupId)
        val portfolioNames = data.portfolios.associate { it.id to it.name }
            .takeIf { data.portfolios.size >= MIN_PORTFOLIOS_TO_CHOOSE }
        val holdingChecklist = holdings.closedLast().map { holding ->
            HoldingChecklistItemUi(
                id = holding.id,
                name = holding.name,
                portfolioName = portfolioNames?.get(holding.portfolioId),
                valueEur = holding.value()?.toEur(rates) ?: Money.ZERO.takeIf { holding.isClosedPosition },
                selected = holding.id in form.selectedHoldingIds,
                goalNames = holdingUse[holding.id]?.goalNames.orEmpty(),
                groupNames = holdingUse[holding.id]?.groupNames.orEmpty(),
            )
        }
        val selectedTotal = assets
            .filter { it.id in form.selectedAssetIds }
            .fold(Money.ZERO) { acc, asset -> acc + asset.amount.toEur(rates) }
        val selectedHoldingsTotal = holdings
            .filter { it.id in form.selectedHoldingIds }
            .fold(Money.ZERO) { acc, holding -> acc + (holding.value()?.toEur(rates) ?: Money.ZERO) }

        return GruposUiState(
            groups = groups,
            title = form.title,
            showBalance = form.showBalance,
            assetChecklist = checklist,
            selectedCount = form.selectedAssetIds.size + form.selectedHoldingIds.size,
            selectedTotal = selectedTotal + selectedHoldingsTotal,
            canSaveNewGroup = form.canSave(),
            pendingDeletion = pendingDeletion,
            isEditing = editingGroupId != null,
            holdingChecklist = holdingChecklist,
            selectionLabel = selectionLabel(form.selectedAssetIds.size, form.selectedHoldingIds.size),
        )
    }
}

private fun PortfolioHolding.toMemberUi(rates: FxRates): GruposMemberUi {
    val value = value()
    return GruposMemberUi(
        id = id,
        name = name,
        amountEur = value?.toEur(rates) ?: Money.ZERO,
        group = PORTFOLIO_TYPE_ID,
        type = PORTFOLIO_LOOK,
        isHolding = true,
        priced = value != null || isClosedPosition,
    )
}

private fun NewGroupForm.canSave(): Boolean =
    loaded && title.isNotBlank() && (selectedAssetIds.isNotEmpty() || selectedHoldingIds.isNotEmpty())

@OptIn(ExperimentalUuidApi::class)
private fun newAccountGroupId(): String = Uuid.random().toString()
