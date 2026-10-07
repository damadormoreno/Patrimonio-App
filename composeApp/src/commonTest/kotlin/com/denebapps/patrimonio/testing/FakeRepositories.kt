package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalAdd
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalNegate
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalProgress
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalSubtract
import com.denebapps.patrimonio.domain.calc.savingsGoalCancellationDelta
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.NetWorthSnapshot
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalAllocationEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.model.YearMonth
import com.denebapps.patrimonio.domain.repository.AccountGroupRepository
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.BackupRepository
import com.denebapps.patrimonio.domain.repository.CreateSavingsGoal
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalDeltaException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalNameException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalTargetException
import com.denebapps.patrimonio.domain.repository.InvalidSavingsGoalTransitionException
import com.denebapps.patrimonio.domain.repository.LiabilityRepository
import com.denebapps.patrimonio.domain.repository.NegativeSavingsGoalProgressException
import com.denebapps.patrimonio.domain.repository.NetWorthRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.RenewalReminderSettings
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalCurrencyMismatchException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalRepository
import com.denebapps.patrimonio.domain.repository.SubscriptionNotFoundException
import com.denebapps.patrimonio.domain.repository.SubscriptionRepository
import com.denebapps.patrimonio.domain.repository.TerminalSavingsGoalException
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.notifications.LocalNotification
import com.denebapps.patrimonio.notifications.ReminderScheduler
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
) : PreferencesRepository {
    private val themeModeBacking = MutableStateFlow(themeMode)
    private val firstNameBacking = MutableStateFlow(firstName)
    private val lastNameBacking = MutableStateFlow(lastName)

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

    private val remindersBacking = MutableStateFlow(RenewalReminderSettings())

    override fun observeRenewalReminders(): StateFlow<RenewalReminderSettings> = remindersBacking

    override suspend fun setRenewalReminders(settings: RenewalReminderSettings) {
        remindersBacking.value = settings
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

/** Records every [replaceAll] call; [failure] makes the next calls throw. */
class FakeReminderScheduler : ReminderScheduler {
    val calls = mutableListOf<List<LocalNotification>>()
    var failure: Throwable? = null

    val scheduled: List<LocalNotification> get() = calls.lastOrNull().orEmpty()

    override suspend fun replaceAll(notifications: List<LocalNotification>) {
        failure?.let { throw it }
        calls += notifications
    }
}

/** `MutableStateFlow`-backed [SubscriptionRepository] fake; [failure] makes every write throw. */
class FakeSubscriptionRepository(initial: List<Subscription> = emptyList()) : SubscriptionRepository {
    private val backing = MutableStateFlow(initial)
    var failure: Throwable? = null

    val current: List<Subscription> get() = backing.value

    override fun observeAll(): Flow<List<Subscription>> = backing

    override suspend fun find(id: String): Subscription? = backing.value.find { it.id == id }

    override suspend fun insert(subscription: Subscription) {
        failure?.let { throw it }
        backing.value = backing.value + subscription
    }

    override suspend fun update(subscription: Subscription) {
        failure?.let { throw it }
        if (backing.value.none { it.id == subscription.id }) throw SubscriptionNotFoundException(subscription.id)
        backing.value = backing.value.map { if (it.id == subscription.id) subscription else it }
    }

    override suspend fun deleteById(id: String) {
        failure?.let { throw it }
        backing.value = backing.value.filterNot { it.id == id }
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

    override fun observeAll(): Flow<List<AccountGroup>> = backing

    override suspend fun insertGroup(group: AccountGroup) {
        backing.value = backing.value + group
    }

    override suspend fun setMembers(groupId: String, assetIds: Set<String>) {
        backing.value = backing.value.map { if (it.id == groupId) it.copy(memberAssetIds = assetIds) else it }
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
 * `MutableStateFlow.value` assignment happens only after every check passes). [assetCurrencyById]
 * stands in for the real repo's `AssetDao` lookup used by the currency-compatibility check on
 * link/create; [persistedGroupIds] stands in for its `AccountGroupDao` lookup (the builtin
 * "all accounts" group is never persisted, hence never linkable).
 */
class FakeSavingsGoalRepository(
    initial: List<SavingsGoal> = emptyList(),
    private val assetCurrencyById: Map<String, Currency> = emptyMap(),
    private val persistedGroupIds: Set<String> = emptySet(),
) : SavingsGoalRepository {
    private val backing = MutableStateFlow(initial)

    // Seeds one synthetic allocation event per non-zero-progress initial goal so the checked-ledger
    // functions (which derive progress SOLELY from events) stay consistent with the goal's declared
    // [SavingsGoal.progress] — without this, the first allocate/withdraw/cancel on a fixture goal
    // would silently ignore its pre-existing progress.
    private val eventsBacking = MutableStateFlow(
        initial.filter { it.progress != Money.ZERO }.associate { goal ->
            goal.id to listOf(
                SavingsGoalAllocationEvent(
                    id = -goal.id,
                    goalId = goal.id,
                    delta = goal.progress,
                    timestampEpochMs = 0L,
                ),
            )
        },
    )
    private val linkEventsBacking = MutableStateFlow<Map<Long, List<SavingsGoalLinkEvent>>>(emptyMap())
    private var nextGoalId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    private var nextEventId = 1L

    override fun observeAll(): Flow<List<SavingsGoal>> = backing

    override fun observeAllocationHistory(goalId: Long): Flow<List<SavingsGoalAllocationEvent>> =
        eventsBacking.map { it[goalId].orEmpty() }

    override fun observeLinkHistory(goalId: Long): Flow<List<SavingsGoalLinkEvent>> =
        linkEventsBacking.map { it[goalId].orEmpty() }

    override suspend fun create(command: CreateSavingsGoal): Long {
        if (command.name.isEmpty() || command.name != command.name.trim()) {
            throw InvalidSavingsGoalNameException(command.name)
        }
        if (command.target.amount <= Money.ZERO) {
            throw InvalidSavingsGoalTargetException(command.target.amount.minorUnits)
        }
        command.linkedAssetId?.let { requireCompatibleAsset(command.target.currency, it) }
        command.linkedGroupId?.let { requireLinkableGroup(it) }

        val goalId = nextGoalId++
        backing.value = backing.value + SavingsGoal(
            id = goalId,
            name = command.name,
            target = command.target,
            targetDate = command.targetDate,
            linkedAssetId = command.linkedAssetId,
            lifecycle = SavingsGoalLifecycle.OPEN,
            progress = Money.ZERO,
            linkedGroupId = command.linkedGroupId,
        )
        command.linkedAssetId?.let { assetId ->
            appendLinkEvent(goalId, fromAssetId = null, toAssetId = assetId, kind = SavingsGoalLinkEventKind.LINK)
        }
        command.linkedGroupId?.let { groupId ->
            appendLinkEvent(goalId, toGroupId = groupId, kind = SavingsGoalLinkEventKind.LINK)
        }
        return goalId
    }

    override suspend fun allocate(goalId: Long, amount: Money) {
        requirePositiveDelta(amount)
        requireOpenGoal(goalId)
        checkedSavingsGoalAdd(progressOf(goalId), amount)
        appendAllocationEvent(goalId, amount)
    }

    override suspend fun withdraw(goalId: Long, amount: Money) {
        requirePositiveDelta(amount)
        requireOpenGoal(goalId)
        val updated = checkedSavingsGoalSubtract(progressOf(goalId), amount)
        if (updated < Money.ZERO) throw NegativeSavingsGoalProgressException(updated.minorUnits)
        appendAllocationEvent(goalId, checkedSavingsGoalNegate(amount))
    }

    override suspend fun link(goalId: Long, assetId: String) {
        val goal = requireOpenGoal(goalId)
        if (goal.linkedAssetId != null || goal.linkedGroupId != null) {
            throw InvalidSavingsGoalTransitionException(goalId, "link")
        }
        requireCompatibleAsset(goal.target.currency, assetId)
        setLink(goalId, assetId = assetId)
        appendLinkEvent(goalId, toAssetId = assetId, kind = SavingsGoalLinkEventKind.LINK)
    }

    override suspend fun linkToGroup(goalId: Long, groupId: String) {
        val goal = requireOpenGoal(goalId)
        if (goal.linkedAssetId != null || goal.linkedGroupId != null) {
            throw InvalidSavingsGoalTransitionException(goalId, "link")
        }
        requireLinkableGroup(groupId)
        setLink(goalId, groupId = groupId)
        appendLinkEvent(goalId, toGroupId = groupId, kind = SavingsGoalLinkEventKind.LINK)
    }

    override suspend fun relink(goalId: Long, assetId: String) {
        val goal = requireOpenGoal(goalId)
        if (goal.linkedAssetId == null && goal.linkedGroupId == null) {
            throw InvalidSavingsGoalTransitionException(goalId, "relink")
        }
        if (goal.linkedAssetId == assetId) throw InvalidSavingsGoalTransitionException(goalId, "relink")
        requireCompatibleAsset(goal.target.currency, assetId)
        setLink(goalId, assetId = assetId)
        appendLinkEvent(
            goalId,
            fromAssetId = goal.linkedAssetId,
            toAssetId = assetId,
            fromGroupId = goal.linkedGroupId,
            kind = SavingsGoalLinkEventKind.RELINK,
        )
    }

    override suspend fun relinkToGroup(goalId: Long, groupId: String) {
        val goal = requireOpenGoal(goalId)
        if (goal.linkedAssetId == null && goal.linkedGroupId == null) {
            throw InvalidSavingsGoalTransitionException(goalId, "relink")
        }
        if (goal.linkedGroupId == groupId) throw InvalidSavingsGoalTransitionException(goalId, "relink")
        requireLinkableGroup(groupId)
        setLink(goalId, groupId = groupId)
        appendLinkEvent(
            goalId,
            fromAssetId = goal.linkedAssetId,
            fromGroupId = goal.linkedGroupId,
            toGroupId = groupId,
            kind = SavingsGoalLinkEventKind.RELINK,
        )
    }

    override suspend fun unlink(goalId: Long) {
        val goal = requireOpenGoal(goalId)
        if (goal.linkedAssetId == null && goal.linkedGroupId == null) {
            throw InvalidSavingsGoalTransitionException(goalId, "unlink")
        }
        setLink(goalId)
        appendLinkEvent(
            goalId,
            fromAssetId = goal.linkedAssetId,
            fromGroupId = goal.linkedGroupId,
            kind = SavingsGoalLinkEventKind.UNLINK,
        )
    }

    override suspend fun close(goalId: Long) {
        requireOpenGoal(goalId)
        setLifecycle(goalId, SavingsGoalLifecycle.CLOSED)
    }

    override suspend fun cancel(goalId: Long) {
        requireOpenGoal(goalId)
        savingsGoalCancellationDelta(progressOf(goalId))?.let { delta -> appendAllocationEvent(goalId, delta) }
        setLifecycle(goalId, SavingsGoalLifecycle.CANCELLED)
    }

    /** Test-only helper: simulates a live repository update (another writer) without validation. */
    fun emit(goals: List<SavingsGoal>) {
        backing.value = goals
    }

    private fun progressOf(goalId: Long): Money = checkedSavingsGoalProgress(eventsBacking.value[goalId].orEmpty())

    private fun requireOpenGoal(goalId: Long): SavingsGoal {
        val goal = backing.value.firstOrNull { it.id == goalId } ?: throw SavingsGoalNotFoundException(goalId)
        if (goal.lifecycle != SavingsGoalLifecycle.OPEN) throw TerminalSavingsGoalException(goalId, goal.lifecycle)
        return goal
    }

    private fun requireCompatibleAsset(goalCurrency: Currency, assetId: String) {
        val assetCurrency = assetCurrencyById[assetId] ?: throw SavingsGoalAssetNotFoundException(assetId)
        if (assetCurrency != goalCurrency) throw SavingsGoalCurrencyMismatchException(goalCurrency, assetCurrency)
    }

    private fun requireLinkableGroup(groupId: String) {
        if (groupId == AccountGroup.ALL_ACCOUNTS_ID) throw SavingsGoalBuiltinGroupException()
        if (groupId !in persistedGroupIds) throw SavingsGoalGroupNotFoundException(groupId)
    }

    private fun requirePositiveDelta(amount: Money) {
        if (amount <= Money.ZERO) throw InvalidSavingsGoalDeltaException(amount.minorUnits)
    }

    private fun appendAllocationEvent(goalId: Long, delta: Money) {
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
        goalId: Long,
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

    private fun setLink(goalId: Long, assetId: String? = null, groupId: String? = null) {
        backing.value = backing.value.map {
            if (it.id == goalId) it.copy(linkedAssetId = assetId, linkedGroupId = groupId) else it
        }
    }

    private fun setLifecycle(goalId: Long, lifecycle: SavingsGoalLifecycle) {
        backing.value = backing.value.map { if (it.id == goalId) it.copy(lifecycle = lifecycle) else it }
    }
}
