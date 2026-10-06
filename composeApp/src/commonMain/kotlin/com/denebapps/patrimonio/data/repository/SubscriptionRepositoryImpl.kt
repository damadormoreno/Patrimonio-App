package com.denebapps.patrimonio.data.repository

import com.denebapps.patrimonio.data.db.SeedingGate
import com.denebapps.patrimonio.data.db.dao.SubscriptionDao
import com.denebapps.patrimonio.data.db.entity.SubscriptionEntity
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.repository.InvalidSubscriptionException
import com.denebapps.patrimonio.domain.repository.SubscriptionNotFoundException
import com.denebapps.patrimonio.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Subscriptions don't feed the net-worth snapshot, so writes are single statements with no
 *  surrounding transaction. A dangling [Subscription.paidFromAssetId] is rejected by the FK. */
class SubscriptionRepositoryImpl(
    private val subscriptionDao: SubscriptionDao,
    private val seedingGate: SeedingGate,
) : SubscriptionRepository {
    override fun observeAll(): Flow<List<Subscription>> = flow {
        seedingGate.await()
        emitAll(subscriptionDao.observeAll().map { rows -> rows.map(::toDomain) })
    }

    override suspend fun find(id: String): Subscription? {
        seedingGate.await()
        return subscriptionDao.find(id)?.let(::toDomain)
    }

    override suspend fun insert(subscription: Subscription) {
        validate(subscription)
        seedingGate.await()
        subscriptionDao.insert(toEntity(subscription))
    }

    override suspend fun update(subscription: Subscription) {
        validate(subscription)
        seedingGate.await()
        if (subscriptionDao.update(toEntity(subscription)) == 0) {
            throw SubscriptionNotFoundException(subscription.id)
        }
    }

    override suspend fun deleteById(id: String) {
        seedingGate.await()
        subscriptionDao.deleteById(id)
    }
}

private fun validate(subscription: Subscription) {
    if (subscription.name.isEmpty() || subscription.name != subscription.name.trim()) {
        throw InvalidSubscriptionException("Invalid subscription name: '${subscription.name}'")
    }
    if (subscription.amount.amount <= Money.ZERO) {
        throw InvalidSubscriptionException("Subscription amount must be positive: ${subscription.amount.amount}")
    }
}

private fun toDomain(entity: SubscriptionEntity): Subscription = Subscription(
    id = entity.id,
    name = entity.name,
    amount = CurrencyAmount(Money(entity.amountMinor), Currency.valueOf(entity.currency)),
    cycle = BillingCycle.valueOf(entity.cycle),
    firstChargeDate = LocalDate.fromEpochDays(entity.firstChargeEpochDay.toInt()),
    paidFromAssetId = entity.paidFromAssetId,
    active = entity.active,
)

private fun toEntity(subscription: Subscription): SubscriptionEntity = SubscriptionEntity(
    id = subscription.id,
    name = subscription.name,
    amountMinor = subscription.amount.amount.minorUnits,
    currency = subscription.amount.currency.code,
    cycle = subscription.cycle.name,
    firstChargeEpochDay = subscription.firstChargeDate.toEpochDays().toLong(),
    paidFromAssetId = subscription.paidFromAssetId,
    active = subscription.active,
)
