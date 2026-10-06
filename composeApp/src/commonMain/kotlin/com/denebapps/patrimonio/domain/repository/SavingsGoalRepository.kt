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

    fun observeAllocationHistory(goalId: Long): Flow<List<SavingsGoalAllocationEvent>>

    fun observeLinkHistory(goalId: Long): Flow<List<SavingsGoalLinkEvent>>

    suspend fun create(command: CreateSavingsGoal): Long

    suspend fun allocate(goalId: Long, amount: Money)

    suspend fun withdraw(goalId: Long, amount: Money)

    suspend fun link(goalId: Long, assetId: String)

    suspend fun relink(goalId: Long, assetId: String)

    suspend fun unlink(goalId: Long)

    suspend fun close(goalId: Long)

    suspend fun cancel(goalId: Long)
}

data class CreateSavingsGoal(
    val name: String,
    val target: CurrencyAmount,
    val targetDate: LocalDate? = null,
    val linkedAssetId: String? = null,
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

class SavingsGoalCurrencyMismatchException(goalCurrency: Currency, assetCurrency: Currency) :
    IllegalArgumentException("Savings goal currency $goalCurrency does not match asset currency $assetCurrency")

class SavingsGoalNotFoundException(goalId: Long) :
    NoSuchElementException("Savings goal $goalId was not found")

class SavingsGoalAssetNotFoundException(assetId: String) :
    NoSuchElementException("Savings goal asset '$assetId' was not found")

class TerminalSavingsGoalException(goalId: Long, lifecycle: SavingsGoalLifecycle) :
    IllegalStateException("Savings goal $goalId is terminal: $lifecycle")

class InvalidSavingsGoalTransitionException(goalId: Long, transition: String) :
    IllegalStateException("Savings goal $goalId cannot perform transition '$transition'")
