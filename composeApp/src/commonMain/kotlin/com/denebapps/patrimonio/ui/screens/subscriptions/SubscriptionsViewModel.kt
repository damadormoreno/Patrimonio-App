package com.denebapps.patrimonio.ui.screens.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.calc.SubscriptionTotals
import com.denebapps.patrimonio.domain.calc.monthlyCost
import com.denebapps.patrimonio.domain.calc.nextChargeDate
import com.denebapps.patrimonio.domain.calc.subscriptionTotals
import com.denebapps.patrimonio.domain.calc.toEur
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.Subscription
import com.denebapps.patrimonio.domain.repository.AssetRepository
import com.denebapps.patrimonio.domain.repository.FxRepository
import com.denebapps.patrimonio.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.todayIn

private const val SUBSCRIPTIONS_STOP_TIMEOUT_MS = 5_000L

data class SubscriptionRowUi(
    val id: String,
    val name: String,
    val amount: Money,
    val currency: Currency,
    val cycle: BillingCycle,
    /** Monthly cost in EUR, for comparing subscriptions with different cycles or currencies. */
    val monthlyEur: Money,
    val nextCharge: LocalDate,
    val daysUntilNextCharge: Int,
    /** Name of the paying asset, if one is linked. */
    val paidFrom: String?,
)

data class SubscriptionsUiState(
    val totals: SubscriptionTotals,
    /** Active subscriptions, soonest charge first. */
    val upcoming: List<SubscriptionRowUi>,
    /** Paused subscriptions, by name. */
    val paused: List<SubscriptionRowUi>,
) {
    val isEmpty: Boolean get() = upcoming.isEmpty() && paused.isEmpty()
}

/**
 * Subscriptions tab. "Today" is read from [clock] on every emission rather than ticking at midnight:
 * any data change or re-subscription refreshes the next-charge dates, which is enough for a list.
 */
class SubscriptionsViewModel(
    subscriptionRepository: SubscriptionRepository,
    assetRepository: AssetRepository,
    fxRepository: FxRepository,
    private val clock: Clock,
    private val zoneProvider: () -> TimeZone,
) : ViewModel() {
    val state: StateFlow<SubscriptionsUiState> = combine(
        subscriptionRepository.observeAll(),
        assetRepository.observeAll(),
        fxRepository.observeRates(),
    ) { subscriptions, assets, rates ->
        val assetNames = assets.associate { it.id to it.name }
        buildSubscriptionsState(subscriptions, assetNames, rates, clock.todayIn(zoneProvider()))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTIONS_STOP_TIMEOUT_MS),
        initialValue = SubscriptionsUiState(SubscriptionTotals(Money.ZERO, Money.ZERO, 0), emptyList(), emptyList()),
    )
}

internal fun buildSubscriptionsState(
    subscriptions: List<Subscription>,
    assetNames: Map<String, String>,
    rates: FxRates,
    today: LocalDate,
): SubscriptionsUiState {
    val rows = subscriptions.map { subscription ->
        val next = nextChargeDate(subscription.firstChargeDate, subscription.cycle, today)
        subscription to SubscriptionRowUi(
            id = subscription.id,
            name = subscription.name,
            amount = subscription.amount.amount,
            currency = subscription.amount.currency,
            cycle = subscription.cycle,
            monthlyEur = monthlyCost(subscription.amount.toEur(rates), subscription.cycle),
            nextCharge = next,
            daysUntilNextCharge = today.daysUntil(next),
            paidFrom = subscription.paidFromAssetId?.let(assetNames::get),
        )
    }
    return SubscriptionsUiState(
        totals = subscriptionTotals(subscriptions, rates),
        upcoming = rows.filter { (sub, _) -> sub.active }.map { it.second }
            .sortedWith(compareBy({ it.nextCharge }, { it.name.lowercase() })),
        paused = rows.filterNot { (sub, _) -> sub.active }.map { it.second }.sortedBy { it.name.lowercase() },
    )
}
