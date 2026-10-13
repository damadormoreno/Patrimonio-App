package com.denebapps.patrimonio.domain.repository

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

    suspend fun close(goalId: String)

    suspend fun cancel(goalId: String)

    /** Edits an open goal: name, target amount (its currency stays), target date and links (accounts, products,
     *  groups or none). Every account, product or group added leaves a LINK event and every one removed an UNLINK.
     *  Nothing is written when any of them does not exist. */
    suspend fun update(goalId: String, command: UpdateSavingsGoal)

    /** Removes the goal and its whole history, whatever its lifecycle. Unlike [cancel] nothing is
     *  kept. @throws SavingsGoalNotFoundException when it does not exist. */
    suspend fun delete(goalId: String)
}

data class CreateSavingsGoal(
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate? = null,
    val linkedAssetIds: Set<String> = emptySet(),
    val linkedHoldingIds: Set<String> = emptySet(),
    val linkedGroupIds: Set<String> = emptySet(),
)

data class UpdateSavingsGoal(
    val name: String,
    val targetAmount: Money,
    val targetDate: LocalDate?,
    val linkedAssetIds: Set<String>,
    val linkedHoldingIds: Set<String>,
    val linkedGroupIds: Set<String>,
)

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

class SavingsGoalNotFoundException(goalId: String) :
    NoSuchElementException("Savings goal $goalId was not found")

class SavingsGoalAssetNotFoundException(assetId: String) :
    NoSuchElementException("Savings goal asset '$assetId' was not found")

class SavingsGoalHoldingNotFoundException(holdingId: String) :
    NoSuchElementException("Savings goal portfolio holding '$holdingId' was not found")

class SavingsGoalGroupNotFoundException(groupId: String) :
    NoSuchElementException("Savings goal group '$groupId' was not found")

class SavingsGoalBuiltinGroupException :
    IllegalArgumentException("The builtin all-accounts group cannot be linked to a savings goal")

class TerminalSavingsGoalException(goalId: String, lifecycle: SavingsGoalLifecycle) :
    IllegalStateException("Savings goal $goalId is terminal: $lifecycle")
