package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalAllocationEvent
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface SavingsGoalRepository {
    fun observeAll(): Flow<List<SavingsGoal>>

    fun observeAllocationHistory(goalId: String): Flow<List<SavingsGoalAllocationEvent>>

    fun observeLinkHistory(goalId: String): Flow<List<SavingsGoalLinkEvent>>

    /** @return the new goal's id. */
    suspend fun create(command: CreateSavingsGoal): String

    suspend fun allocate(goalId: String, amount: Money)

    suspend fun withdraw(goalId: String, amount: Money)

    /** Links an unlinked goal to an asset of the goal's currency. */
    suspend fun link(goalId: String, assetId: String)

    /** Links an unlinked goal to a persisted account group (any currency mix). The builtin
     *  "all accounts" group is not linkable. */
    suspend fun linkToGroup(goalId: String, groupId: String)

    /** Moves an already-linked goal (to an asset or a group) to another asset of its currency. */
    suspend fun relink(goalId: String, assetId: String)

    /** Moves an already-linked goal (to an asset or a group) to another persisted account group. */
    suspend fun relinkToGroup(goalId: String, groupId: String)

    /** Removes the current link, whether it targets an asset or a group. */
    suspend fun unlink(goalId: String)

    suspend fun close(goalId: String)

    suspend fun cancel(goalId: String)

    /** Edits an open goal: name, target amount (its currency stays), target date and link (asset, group
     *  or none). A link change leaves the same LINK/RELINK/UNLINK event as [link]/[relink]/[unlink]. */
    suspend fun update(goalId: String, command: UpdateSavingsGoal)

    /** Removes the goal and its whole history, whatever its lifecycle. Unlike [cancel] nothing is
     *  kept. @throws SavingsGoalNotFoundException when it does not exist. */
    suspend fun delete(goalId: String)
}

data class CreateSavingsGoal(
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate? = null,
    val linkedAssetId: String? = null,
    val linkedGroupId: String? = null,
) {
    init {
        require(linkedAssetId == null || linkedGroupId == null) {
            "A savings goal cannot be linked to an asset and a group at the same time"
        }
    }
}

data class UpdateSavingsGoal(
    val name: String,
    val targetAmount: Money,
    val targetDate: LocalDate?,
    val linkedAssetId: String?,
    val linkedGroupId: String?,
) {
    init {
        require(linkedAssetId == null || linkedGroupId == null) {
            "A savings goal cannot be linked to an asset and a group at the same time"
        }
    }
}

class InvalidSavingsGoalNameException(name: String) :
    IllegalArgumentException("Savings goal name must be trimmed and non-empty, was '$name'")

class InvalidSavingsGoalTargetException(targetMinor: Long) :
    IllegalArgumentException("Savings goal target minor units must be > 0, was $targetMinor")

class InvalidSavingsGoalDeltaException(deltaMinor: Long) :
    IllegalArgumentException("Savings goal allocation delta must be non-zero, was $deltaMinor")

class NegativeSavingsGoalProgressException(progressMinor: Long) :
    IllegalArgumentException("Savings goal progress must not be negative, was $progressMinor")

class SavingsGoalArithmeticOverflowException(leftMinor: Long, operation: String, rightMinor: Long) :
    ArithmeticException("Savings goal arithmetic overflow: $leftMinor $operation $rightMinor")

class SavingsGoalCurrencyMismatchException(goalCurrency: Currency, assetCurrency: Currency) :
    IllegalArgumentException("Savings goal currency $goalCurrency does not match asset currency $assetCurrency")

class SavingsGoalNotFoundException(goalId: String) :
    NoSuchElementException("Savings goal $goalId was not found")

class SavingsGoalAssetNotFoundException(assetId: String) :
    NoSuchElementException("Savings goal asset '$assetId' was not found")

class SavingsGoalGroupNotFoundException(groupId: String) :
    NoSuchElementException("Savings goal group '$groupId' was not found")

class SavingsGoalBuiltinGroupException :
    IllegalArgumentException("The builtin all-accounts group cannot be linked to a savings goal")

class TerminalSavingsGoalException(goalId: String, lifecycle: SavingsGoalLifecycle) :
    IllegalStateException("Savings goal $goalId is terminal: $lifecycle")

class InvalidSavingsGoalTransitionException(goalId: String, transition: String) :
    IllegalStateException("Savings goal $goalId cannot perform transition '$transition'")
