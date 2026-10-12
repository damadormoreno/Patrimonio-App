package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalAdd
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalNegate
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalProgress
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalSubtract
import com.denebapps.patrimonio.domain.calc.savingsGoalCancellationDelta
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.DEFAULT_PORTFOLIO_NAME
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.Quote
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalAllocationEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.model.Units
import com.denebapps.patrimonio.domain.model.UnitsBalance
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.domain.model.normalizeBroker
import com.denebapps.patrimonio.domain.model.normalizePortfolioName
import com.denebapps.patrimonio.domain.model.toBalance
import com.denebapps.patrimonio.domain.repository.AccountGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.BackupRepository
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.DuplicatePortfolioNameException
import com.denebapps.patrimonio.domain.repository.FundamentalsSyncResult
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.HoldingHasMovementsException
import com.denebapps.patrimonio.domain.repository.HoldingHasTransfersException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalDeltaException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalNameException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalTargetException
import com.denebapps.patrimonio.domain.repository.LastPortfolioException
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioHoldingNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioNotEmptyException
import com.denebapps.patrimonio.domain.repository.PortfolioNotFoundException
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.PriceRefreshResult
import com.denebapps.patrimonio.domain.repository.QuoteSource
import com.denebapps.patrimonio.domain.repository.QuoteUnavailableException
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.TerminalSavingsGoalException
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.domain.repository.UpdateSavingsGoal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** `MutableStateFlow`-backed preferences fake for root and settings ViewModel tests. */
class FakePreferencesRepository(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    firstName: String = "",
    lastName: String = "",
    selectedPortfolioId: String? = null,
) : PreferencesRepository {
    private val themeModeBacking = MutableStateFlow(themeMode)
    private val firstNameBacking = MutableStateFlow(firstName)
    private val lastNameBacking = MutableStateFlow(lastName)
    private val selectedPortfolioBacking = MutableStateFlow(selectedPortfolioId)

    override fun observeThemeMode(): StateFlow<ThemeMode> = themeModeBacking

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeModeBacking.value = mode
    }

    override fun observeFirstName(): StateFlow<String> = firstNameBacking

    override suspend fun setFirstName(value: String) {
        firstNameBacking.value = value
    }

    override fun observeLastName(): StateFlow<String> = lastNameBacking

    override suspend fun setLastName(value: String) {
        lastNameBacking.value = value
    }

    override fun observeSelectedPortfolioId(): StateFlow<String?> = selectedPortfolioBacking

    override suspend fun setSelectedPortfolioId(id: String?) {
        selectedPortfolioBacking.value = id
    }
}

/** In-memory [BackupRepository] fake: [exportJson] returns [exported], [importJson] records the
 *  payload; [failure] makes both throw. [gate] holds an in-flight call open. */
class FakeBackupRepository(var exported: String = "{}") : BackupRepository {
    val imported = mutableListOf<String>()
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun exportJson(): String {
        gate?.await()
        failure?.let { throw it }
        return exported
    }

    override suspend fun importJson(json: String) {
        gate?.await()
        failure?.let { throw it }
        imported += json
    }
}

/**
 * Invocation-recording [DataMaintenanceRepository] fake. [gate] holds an in-flight clear open so
 * tests can observe the ViewModel's progress flag and re-entrancy guard mid-command.
 */
class FakeDataMaintenanceRepository : DataMaintenanceRepository {
    var clearCalls = 0
        private set
    var gate: CompletableDeferred<Unit>? = null
    var clearFailure: Throwable? = null

    override suspend fun clearAllFinancialData() {
        clearCalls++
        gate?.await()
        clearFailure?.let { throw it }
    }
}

/** `MutableStateFlow`-backed [FxRepository] fake; [refreshIfStale] is a no-op. */
class FakeFxRepository(initial: FxRates = FxRates(emptyMap())) : FxRepository {
    private val backing = MutableStateFlow(initial)

    override fun observeRates(): Flow<FxRates> = backing

    override suspend fun refreshIfStale() {
        // No-op: tests control rates directly via emit()/the constructor.
    }

    fun emit(rates: FxRates) {
        backing.value = rates
    }
}

/** `MutableStateFlow`-backed [AssetRepository] fake for ViewModel unit tests. */
class FakeAssetRepository(initial: List<Asset> = emptyList()) : AssetRepository {
    private val backing = MutableStateFlow(initial)

    override fun observeAll(): Flow<List<Asset>> = backing

    override suspend fun list(): List<Asset> = backing.value

    override suspend fun insert(asset: Asset) {
        backing.value = backing.value + asset
    }

    override suspend fun update(asset: Asset) {
        backing.value = backing.value.map { if (it.id == asset.id) asset else it }
    }

    override suspend fun deleteById(id: String) {
        backing.value = backing.value.filterNot { it.id == id }
    }

    fun emit(assets: List<Asset>) {
        backing.value = assets
    }
}

/** `MutableStateFlow`-backed [LiabilityRepository] fake for ViewModel unit tests. */
class FakeLiabilityRepository(initial: List<Liability> = emptyList()) : LiabilityRepository {
    private val backing = MutableStateFlow(initial)

    override fun observeAll(): Flow<List<Liability>> = backing

    override suspend fun list(): List<Liability> = backing.value

    override suspend fun insert(liability: Liability) {
        backing.value = backing.value + liability
    }

    override suspend fun update(liability: Liability) {
        backing.value = backing.value.map { if (it.id == liability.id) liability else it }
    }

    override suspend fun deleteById(id: String) {
        backing.value = backing.value.filterNot { it.id == id }
    }

    fun emit(liabilities: List<Liability>) {
        backing.value = liabilities
    }
}

/** `MutableStateFlow`-backed [NetWorthRepository] fake for ViewModel unit tests. */
class FakeNetWorthRepository(initial: List<NetWorthSnapshot> = emptyList()) : NetWorthRepository {
    private val backing = MutableStateFlow(initial)

    override fun observeSnapshots(): Flow<List<NetWorthSnapshot>> = backing

    override suspend fun findMostRecentBefore(yearMonth: YearMonth): NetWorthSnapshot? =
        backing.value.filter { it.yearMonth < yearMonth }.maxByOrNull { it.yearMonth }

    fun emit(snapshots: List<NetWorthSnapshot>) {
        backing.value = snapshots
    }
}

/** `MutableStateFlow`-backed [AccountGroupRepository] fake for ViewModel unit tests. */
class FakeAccountGroupRepository(initial: List<AccountGroup> = emptyList()) : AccountGroupRepository {
    private val backing = MutableStateFlow(initial)

    val current: List<AccountGroup> get() = backing.value

    override fun observeAll(): Flow<List<AccountGroup>> = backing

    override suspend fun insertGroup(group: AccountGroup) {
        backing.value = backing.value + group
    }

    override suspend fun setMembers(groupId: String, assetIds: Set<String>) {
        backing.value = backing.value.map { if (it.id == groupId) it.copy(memberAssetIds = assetIds) else it }
    }

    override suspend fun updateGroup(group: AccountGroup) {
        if (backing.value.none { it.id == group.id }) throw AccountGroupNotFoundException(group.id)
        backing.value = backing.value.map {
            if (it.id == group.id) {
                it.copy(name = group.name, showBalance = group.showBalance, memberAssetIds = group.memberAssetIds)
            } else {
                it
            }
        }
    }

    override suspend fun reorderGroups(groupIds: List<String>) {
        val reordered = backing.value.map { group ->
            val index = groupIds.indexOf(group.id)
            if (index >= 0) group.copy(sortOrder = index) else group
        }
        backing.value = reordered.sortedBy { it.sortOrder }
    }

    override suspend fun deleteGroup(id: String) {
        backing.value = backing.value.filterNot { it.id == id }
    }

    fun emit(groups: List<AccountGroup>) {
        backing.value = groups
    }
}

/**
 * `MutableStateFlow`-backed [SavingsGoalRepository] fake for ViewModel unit tests. Mirrors
 * [com.denebapps.patrimonio.data.repository.SavingsGoalRepositoryImpl]'s validation/atomicity by
 * reusing the SAME `domain/calc` checked-ledger functions ([checkedSavingsGoalAdd],
 * [checkedSavingsGoalSubtract], [checkedSavingsGoalProgress], [savingsGoalCancellationDelta]) rather
 * than reimplementing them — a rejected command never mutates [backing] (single atomic
 * `MutableStateFlow.value` assignment happens only after every check passes). [knownAssetIds]
 * stands in for the real repo's `AssetDao` lookup when linking accounts; [persistedGroupIds] for its
 * `AccountGroupDao` lookup (the builtin "all accounts" group is never persisted, hence never linkable).
 */
class FakeSavingsGoalRepository(
    initial: List<SavingsGoal> = emptyList(),
    private val knownAssetIds: Set<String> = emptySet(),
    private val persistedGroupIds: Set<String> = emptySet(),
) : SavingsGoalRepository {
    private val backing = MutableStateFlow(initial)

    // Seeds one synthetic allocation event per non-zero-progress initial goal so the checked-ledger
    // functions (which derive progress SOLELY from events) stay consistent with the goal's declared
    // [SavingsGoal.progress] — without this, the first allocate/withdraw/cancel on a fixture goal
    // would silently ignore its pre-existing progress.
    private val eventsBacking = MutableStateFlow(
        initial.filter { it.progress != Money.ZERO }.withIndex().associate { (index, goal) ->
            goal.id to listOf(
                SavingsGoalAllocationEvent(
                    id = -(index + 1L),
                    goalId = goal.id,
                    delta = goal.progress,
                    timestampEpochMs = 0L,
                ),
            )
        },
    )
    private val linkEventsBacking = MutableStateFlow<Map<String, List<SavingsGoalLinkEvent>>>(emptyMap())
    private var createdGoals = 0
    private var nextEventId = 1L

    override fun observeAll(): Flow<List<SavingsGoal>> = backing

    override fun observeAllocationHistory(goalId: String): Flow<List<SavingsGoalAllocationEvent>> =
        eventsBacking.map { it[goalId].orEmpty() }

    override fun observeLinkHistory(goalId: String): Flow<List<SavingsGoalLinkEvent>> =
        linkEventsBacking.map { it[goalId].orEmpty() }

    /** New ids are `new-goal-1`, `new-goal-2`… so tests can predict them. */
    override suspend fun create(command: CreateSavingsGoal): String {
        if (command.name.isEmpty() || command.name != command.name.trim()) {
            throw InvalidSavingsGoalNameException(command.name)
        }
        if (command.target.amount <= Money.ZERO) {
            throw InvalidSavingsGoalTargetException(command.target.amount.minorUnits)
        }
        command.linkedAssetIds.forEach(::requireAsset)
        command.linkedGroupId?.let { requireLinkableGroup(it) }

        val goalId = "new-goal-${++createdGoals}"
        backing.value = backing.value + SavingsGoal(
            id = goalId,
            name = command.name,
            target = command.target,
            targetDate = command.targetDate,
            linkedAssetIds = command.linkedAssetIds,
            lifecycle = SavingsGoalLifecycle.OPEN,
            progress = Money.ZERO,
            linkedGroupId = command.linkedGroupId,
        )
        appendLinkEvents(goalId, emptySet(), null, command.linkedAssetIds, command.linkedGroupId)
        return goalId
    }

    override suspend fun allocate(goalId: String, amount: Money) {
        requirePositiveDelta(amount)
        requireOpenGoal(goalId)
        checkedSavingsGoalAdd(progressOf(goalId), amount)
        appendAllocationEvent(goalId, amount)
    }

    override suspend fun withdraw(goalId: String, amount: Money) {
        requirePositiveDelta(amount)
        requireOpenGoal(goalId)
        val updated = checkedSavingsGoalSubtract(progressOf(goalId), amount)
        if (updated < Money.ZERO) throw NegativeSavingsGoalProgressException(updated.minorUnits)
        appendAllocationEvent(goalId, checkedSavingsGoalNegate(amount))
    }

    override suspend fun close(goalId: String) {
        requireOpenGoal(goalId)
        setLifecycle(goalId, SavingsGoalLifecycle.CLOSED)
    }

    override suspend fun update(goalId: String, command: UpdateSavingsGoal) {
        if (command.name.isEmpty() || command.name != command.name.trim()) {
            throw InvalidSavingsGoalNameException(command.name)
        }
        if (command.targetAmount <= Money.ZERO) throw InvalidSavingsGoalTargetException(command.targetAmount.minorUnits)
        val goal = requireOpenGoal(goalId)
        (command.linkedAssetIds - goal.linkedAssetIds).forEach(::requireAsset)
        command.linkedGroupId?.takeIf { it != goal.linkedGroupId }?.let(::requireLinkableGroup)

        backing.value = backing.value.map {
            if (it.id != goalId) {
                it
            } else {
                it.copy(
                    name = command.name,
                    target = it.target.copy(amount = command.targetAmount),
                    targetDate = command.targetDate,
                    linkedAssetIds = command.linkedAssetIds,
                    linkedGroupId = command.linkedGroupId,
                )
            }
        }
        appendLinkEvents(goalId, goal.linkedAssetIds, goal.linkedGroupId, command.linkedAssetIds, command.linkedGroupId)
    }

    override suspend fun delete(goalId: String) {
        if (backing.value.none { it.id == goalId }) throw SavingsGoalNotFoundException(goalId)
        backing.value = backing.value.filterNot { it.id == goalId }
        eventsBacking.value = eventsBacking.value - goalId
        linkEventsBacking.value = linkEventsBacking.value - goalId
    }

    override suspend fun cancel(goalId: String) {
        requireOpenGoal(goalId)
        savingsGoalCancellationDelta(progressOf(goalId))?.let { delta -> appendAllocationEvent(goalId, delta) }
        setLifecycle(goalId, SavingsGoalLifecycle.CANCELLED)
    }

    /** Test-only helper: simulates a live repository update (another writer) without validation. */
    fun emit(goals: List<SavingsGoal>) {
        backing.value = goals
    }

    private fun progressOf(goalId: String): Money = checkedSavingsGoalProgress(eventsBacking.value[goalId].orEmpty())

    private fun requireOpenGoal(goalId: String): SavingsGoal {
        val goal = backing.value.firstOrNull { it.id == goalId } ?: throw SavingsGoalNotFoundException(goalId)
        if (goal.lifecycle != SavingsGoalLifecycle.OPEN) throw TerminalSavingsGoalException(goalId, goal.lifecycle)
        return goal
    }

    private fun requireAsset(assetId: String) {
        if (assetId !in knownAssetIds) throw SavingsGoalAssetNotFoundException(assetId)
    }

    private fun requireLinkableGroup(groupId: String) {
        if (groupId == AccountGroup.ALL_ACCOUNTS_ID) throw SavingsGoalBuiltinGroupException()
        if (groupId !in persistedGroupIds) throw SavingsGoalGroupNotFoundException(groupId)
    }

    private fun requirePositiveDelta(amount: Money) {
        if (amount <= Money.ZERO) throw InvalidSavingsGoalDeltaException(amount.minorUnits)
    }

    private fun appendAllocationEvent(goalId: String, delta: Money) {
        val event = SavingsGoalAllocationEvent(
            id = nextEventId++,
            goalId = goalId,
            delta = delta,
            timestampEpochMs = 0L,
        )
        eventsBacking.value = eventsBacking.value + (goalId to (eventsBacking.value[goalId].orEmpty() + event))
        val progress = checkedSavingsGoalProgress(eventsBacking.value[goalId].orEmpty())
        backing.value = backing.value.map { if (it.id == goalId) it.copy(progress = progress) else it }
    }

    private fun appendLinkEvent(
        goalId: String,
        kind: SavingsGoalLinkEventKind,
        fromAssetId: String? = null,
        toAssetId: String? = null,
        fromGroupId: String? = null,
        toGroupId: String? = null,
    ) {
        val event = SavingsGoalLinkEvent(
            id = nextEventId++,
            goalId = goalId,
            fromAssetId = fromAssetId,
            toAssetId = toAssetId,
            kind = kind,
            timestampEpochMs = 0L,
            fromGroupId = fromGroupId,
            toGroupId = toGroupId,
        )
        val goalEvents = linkEventsBacking.value[goalId].orEmpty() + event
        linkEventsBacking.value = linkEventsBacking.value + (goalId to goalEvents)
    }

    /** Same events, in the same order, as the real repository: UNLINK per removed account, the group
     *  change, LINK per added account. */
    private fun appendLinkEvents(
        goalId: String,
        fromAssetIds: Set<String>,
        fromGroupId: String?,
        toAssetIds: Set<String>,
        toGroupId: String?,
    ) {
        (fromAssetIds - toAssetIds).sorted().forEach {
            appendLinkEvent(goalId, SavingsGoalLinkEventKind.UNLINK, fromAssetId = it)
        }
        if (fromGroupId != toGroupId) {
            val kind = when {
                fromGroupId == null -> SavingsGoalLinkEventKind.LINK
                toGroupId == null -> SavingsGoalLinkEventKind.UNLINK
                else -> SavingsGoalLinkEventKind.RELINK
            }
            appendLinkEvent(goalId, kind, fromGroupId = fromGroupId, toGroupId = toGroupId)
        }
        (toAssetIds - fromAssetIds).sorted().forEach {
            appendLinkEvent(goalId, SavingsGoalLinkEventKind.LINK, toAssetId = it)
        }
    }

    private fun setLifecycle(goalId: String, lifecycle: SavingsGoalLifecycle) {
        backing.value = backing.value.map { if (it.id == goalId) it.copy(lifecycle = lifecycle) else it }
    }
}

/**
 * `MutableStateFlow`-backed [PortfolioRepository] fake for ViewModel unit tests. [refreshPrices] records its `force`
 * argument in [refreshCalls] and answers [refreshResult], or throws [refreshFailure]; it never changes a price.
 * Manual values are stamped with [valuedAtEpochMs].
 */
class FakePortfolioRepository(
    initial: List<PortfolioHolding> = emptyList(),
    private val valuedAtEpochMs: Long = 0L,
) : PortfolioRepository {
    private val backing = MutableStateFlow(initial)

    val current: List<PortfolioHolding> get() = backing.value
    val refreshCalls = mutableListOf<Boolean>()

    /** The `(holdingId, assetClass)` of every [updateAssetClass] call. */
    val assetClassCalls = mutableListOf<Pair<String, AssetClass?>>()
    var refreshResult = PriceRefreshResult(refreshed = 0, upToDate = 0, failures = emptyList())
    var refreshFailure: Throwable? = null

    /** The `(holdingId, force)` of every [syncFundamentals] call, answered with [fundamentalsResult]. */
    val fundamentalsCalls = mutableListOf<Pair<String?, Boolean>>()
    var fundamentalsResult = FundamentalsSyncResult(updated = 0, upToDate = 0, failures = emptyList())

    /** The holdings that have movements, kept up to date by [FakeMovementRepository]: their units are not editable. */
    internal var holdingsWithMovements: Set<String> = emptySet()

    /** The holdings with a transfer to or from another holding, kept up to date by [FakeMovementRepository]. */
    internal var holdingsWithTransfers: Set<String> = emptySet()

    /** Told when a holding is deleted, so [FakeMovementRepository] can drop its movements as the database cascades. */
    internal var onHoldingDeleted: (String) -> Unit = {}

    /** When set, [refreshPrices] suspends until it is completed, to observe a refresh in flight. */
    var refreshGate: CompletableDeferred<Unit>? = null

    override fun observeAll(): Flow<List<PortfolioHolding>> = backing

    override suspend fun add(holding: PortfolioHolding) {
        check(backing.value.none { it.id == holding.id }) { "Holding '${holding.id}' already exists" }
        backing.value = backing.value + holding
    }

    override suspend fun updateUnits(id: String, units: Units) {
        val holding = find(id)
        val valuation = holding.valuation as? HoldingValuation.Quoted
            ?: error("Holding '$id' is not a quoted holding")
        if (id in holdingsWithMovements) throw HoldingHasMovementsException(id)
        replace(holding.copy(valuation = valuation.copy(units = units.toBalance())))
    }

    override suspend fun updateManualValue(id: String, value: CurrencyAmount) {
        val holding = find(id)
        check(holding.valuation is HoldingValuation.Manual) { "Holding '$id' is not a manual holding" }
        replace(holding.copy(valuation = HoldingValuation.Manual(value, valuedAtEpochMs)))
    }

    override suspend fun updateBroker(id: String, broker: String?) {
        replace(find(id).copy(broker = normalizeBroker(broker)))
    }

    override suspend fun updateAssetClass(id: String, assetClass: AssetClass?) {
        assetClassCalls += id to assetClass
        replace(find(id).copy(assetClass = assetClass))
    }

    override suspend fun delete(id: String) {
        find(id)
        if (id in holdingsWithTransfers) throw HoldingHasTransfersException(id)
        backing.value = backing.value.filterNot { it.id == id }
        onHoldingDeleted(id)
    }

    override suspend fun refreshPrices(force: Boolean): PriceRefreshResult {
        refreshCalls += force
        refreshGate?.await()
        refreshFailure?.let { throw it }
        return refreshResult
    }

    override suspend fun syncFundamentals(holdingId: String?, force: Boolean): FundamentalsSyncResult {
        fundamentalsCalls += holdingId to force
        return fundamentalsResult
    }

    fun emit(holdings: List<PortfolioHolding>) {
        backing.value = holdings
    }

    /** Sets the units a quoted holding has, as the movements decide; used by [FakeMovementRepository]. */
    internal fun materializeUnits(id: String, units: UnitsBalance) {
        val holding = find(id)
        val valuation = holding.valuation as? HoldingValuation.Quoted ?: error("Holding '$id' is not quoted")
        replace(holding.copy(valuation = valuation.copy(units = units)))
    }

    /** How many holdings are in [portfolioId]; used by [FakePortfolioCollectionRepository]. */
    internal fun countIn(portfolioId: String): Int = backing.value.count { it.portfolioId == portfolioId }

    /** Puts holding [id] in [portfolioId]; used by [FakePortfolioCollectionRepository]. */
    internal fun moveTo(id: String, portfolioId: String) {
        replace(find(id).copy(portfolioId = portfolioId))
    }

    /** Puts every holding of [from] in [to]; used by [FakePortfolioCollectionRepository]. */
    internal fun moveAll(from: String, to: String) {
        backing.value = backing.value.map { if (it.portfolioId == from) it.copy(portfolioId = to) else it }
    }

    private fun find(id: String): PortfolioHolding =
        backing.value.firstOrNull { it.id == id } ?: throw PortfolioHoldingNotFoundException(id)

    private fun replace(holding: PortfolioHolding) {
        backing.value = backing.value.map { if (it.id == holding.id) holding else it }
    }
}

/**
 * `MutableStateFlow`-backed [PortfolioCollectionRepository] with the rules of the real one (names, uniqueness, the last
 * portfolio, moving holdings on delete). It starts with the single portfolio [TEST_PORTFOLIO_ID] unless [initial] says
 * otherwise. Holdings live in [holdings], so moving and deleting reach the same state the screens observe; without it
 * every portfolio counts as empty.
 */
class FakePortfolioCollectionRepository(
    initial: List<Portfolio> = listOf(Portfolio(TEST_PORTFOLIO_ID, DEFAULT_PORTFOLIO_NAME, 0)),
    private val holdings: FakePortfolioRepository? = null,
) : PortfolioCollectionRepository {
    private val backing = MutableStateFlow(initial)
    private var generated = 0

    val current: List<Portfolio> get() = ordered(backing.value)

    override fun observePortfolios(): Flow<List<Portfolio>> = backing.map(::ordered)

    override suspend fun createPortfolio(name: String): Portfolio {
        val normalized = checkedName(name, ignoring = null)
        val portfolio = Portfolio(
            id = "portfolio-new-${++generated}",
            name = normalized,
            sortOrder = (backing.value.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        backing.value = backing.value + portfolio
        return portfolio
    }

    override suspend fun renamePortfolio(id: String, name: String) {
        val portfolio = find(id)
        val normalized = checkedName(name, ignoring = id)
        backing.value = backing.value.map { if (it.id == portfolio.id) it.copy(name = normalized) else it }
    }

    override suspend fun deletePortfolio(id: String, moveHoldingsTo: String?) {
        find(id)
        if (backing.value.size == 1) throw LastPortfolioException()
        val holdingCount = holdings?.countIn(id) ?: 0
        if (holdingCount > 0) {
            val target = moveHoldingsTo ?: throw PortfolioNotEmptyException(id, holdingCount)
            require(target != id) { "Holdings cannot be moved to the portfolio being deleted" }
            find(target)
            holdings?.moveAll(id, target)
        }
        backing.value = backing.value.filterNot { it.id == id }
    }

    override suspend fun moveHolding(holdingId: String, portfolioId: String) {
        find(portfolioId)
        (holdings ?: throw PortfolioHoldingNotFoundException(holdingId)).moveTo(holdingId, portfolioId)
    }

    private fun ordered(portfolios: List<Portfolio>) =
        portfolios.sortedWith(compareBy<Portfolio> { it.sortOrder }.thenBy { it.name.lowercase() }.thenBy { it.id })

    private fun find(id: String): Portfolio =
        backing.value.firstOrNull { it.id == id } ?: throw PortfolioNotFoundException(id)

    private fun checkedName(raw: String, ignoring: String?): String {
        val name = normalizePortfolioName(raw)
        if (backing.value.any { it.id != ignoring && it.name.lowercase() == name.lowercase() }) {
            throw DuplicatePortfolioNameException(name)
        }
        return name
    }
}

/**
 * Scriptable [QuoteSource]: [searchResults] answers every search, [quotes] holds the price of each ref, and
 * [searchFailure]/[quoteFailure] make the matching call throw. Every call is recorded.
 */
class FakeQuoteSource : QuoteSource {
    var searchResults: List<InstrumentCandidate> = emptyList()
    var searchFailure: Throwable? = null
    val quotes = mutableMapOf<InstrumentRef, Quote>()
    var quoteFailure: Throwable? = null
    val searches = mutableListOf<String>()
    val quoteRequests = mutableListOf<InstrumentRef>()

    override suspend fun search(query: String): List<InstrumentCandidate> {
        searches += query
        searchFailure?.let { throw it }
        return searchResults
    }

    override suspend fun latestQuote(ref: InstrumentRef): Quote {
        quoteRequests += ref
        quoteFailure?.let { throw it }
        return quotes[ref] ?: throw QuoteUnavailableException("No quote scripted for $ref")
    }
}
