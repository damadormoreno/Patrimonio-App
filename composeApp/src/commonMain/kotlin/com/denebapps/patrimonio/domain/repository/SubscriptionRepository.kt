package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.Subscription
import kotlinx.coroutines.flow.Flow

interface SubscriptionRepository {
    fun observeAll(): Flow<List<Subscription>>

    suspend fun find(id: String): Subscription?

    /** @throws InvalidSubscriptionException when the name is blank/untrimmed or the amount is not positive. */
    suspend fun insert(subscription: Subscription)

    /**
     * @throws InvalidSubscriptionException as [insert].
     * @throws SubscriptionNotFoundException if [Subscription.id] no longer exists.
     */
    suspend fun update(subscription: Subscription)

    suspend fun deleteById(id: String)
}

class InvalidSubscriptionException(message: String) : IllegalArgumentException(message)

class SubscriptionNotFoundException(id: String) : NoSuchElementException("Subscription '$id' not found")
