package com.denebapps.patrimonio.ui.screens.savings

import com.denebapps.patrimonio.domain.calc.groupHoldings
import com.denebapps.patrimonio.domain.calc.groupMembers
import com.denebapps.patrimonio.domain.calc.trackedBalance
import com.denebapps.patrimonio.domain.model.AccountGroup
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.CurrencyAmount
import com.denebapps.patrimonio.domain.model.FxRates
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.SavingsGoal
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.repository.SavingsGoalAssetNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalBuiltinGroupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalGroupNotFoundException
import com.denebapps.patrimonio.domain.repository.SavingsGoalHoldingNotFoundException

/**
 * The goal form's live footer: how many accounts, products and groups are picked and the [total] they add up to in
 * the goal's [currency], counting what several of them reach (an account picked and also inside a picked group) once.
 * [total] is null when a needed exchange rate is missing. [overlap] is true when some account or product is reached
 * more than once, to say so.
 */
data class GoalLinkSummaryUi(
    val accounts: Int,
    val products: Int,
    val groups: Int,
    val total: Money?,
    val currency: Currency,
    val overlap: Boolean,
) {
    /** «Sigue 2 cuentas y 1 grupo · total 130,00 € (sin contar dos veces lo que se repite)». */
    val text: String
        get() {
            val totalPart = total?.let { "total ${formatSavingsAmount(it, currency)}" }
                ?: "sin tipo de cambio para calcular el total"
            val note = if (overlap && total != null) " (sin contar dos veces lo que se repite)" else ""
            return "Sigue ${joinSpanish(kindCounts(accounts, products, groups))} · $totalPart$note"
        }
}

/** The footer for the picked links, or null when nothing is picked (the goal is then filled in by hand). */
internal fun goalLinkSummary(
    assetIds: Set<String>,
    holdingIds: Set<String>,
    groupIds: Set<String>,
    currency: Currency,
    assets: List<Asset>,
    groups: List<AccountGroup>,
    holdings: List<PortfolioHolding>,
    rates: FxRates,
): GoalLinkSummaryUi? {
    if (assetIds.isEmpty() && holdingIds.isEmpty() && groupIds.isEmpty()) return null
    // The same deduplicated union the saved goal will show, so the footer matches the list afterwards.
    val probe = SavingsGoal(
        id = "",
        name = "",
        target = CurrencyAmount(Money(1), currency),
        targetDate = null,
        linkedAssetIds = assetIds,
        lifecycle = SavingsGoalLifecycle.OPEN,
        progress = Money.ZERO,
        linkedHoldingIds = holdingIds,
        linkedGroupIds = groupIds,
    )
    val picked = groups.filter { it.id in groupIds && it.id != AccountGroup.ALL_ACCOUNTS_ID }
    val accountRefs = assets.filter { it.id in assetIds }.map { it.id } +
        picked.flatMap { group -> groupMembers(group, assets).map { it.id } }
    val productRefs = holdings.filter { it.id in holdingIds }.map { it.id } +
        picked.flatMap { group -> groupHoldings(group, holdings).map { it.id } }
    return GoalLinkSummaryUi(
        accounts = assetIds.size,
        products = holdingIds.size,
        groups = groupIds.size,
        total = trackedBalance(probe, assets, groups, rates, holdings),
        currency = currency,
        overlap = accountRefs.size != accountRefs.toSet().size || productRefs.size != productRefs.toSet().size,
    )
}

/**
 * What a goal follows, ready to read after "de": «Casa»; «Cuenta» y «Casa»; «A», «B» y «C» (names in alphabetical
 * order, whatever they are) up to [MAX_NAMED_TARGETS] targets, and from one more on the count of each kind:
 * "2 cuentas, 1 producto y 3 grupos". Null when it follows nothing.
 */
internal fun linkedTargetLabel(
    accounts: List<String>,
    products: List<String> = emptyList(),
    groups: List<String> = emptyList(),
): String? {
    val total = accounts.size + products.size + groups.size
    return when {
        total == 0 -> null
        total > MAX_NAMED_TARGETS -> joinSpanish(kindCounts(accounts.size, products.size, groups.size))
        else -> joinSpanish((accounts + products + groups).sorted().map { "«$it»" })
    }
}

private const val MAX_NAMED_TARGETS = 3

/** "2 cuentas", "1 producto", "3 grupos", leaving out the kinds with none. */
private fun kindCounts(accounts: Int, products: Int, groups: Int): List<String> = listOfNotNull(
    countLabel(accounts, "cuenta", "cuentas"),
    countLabel(products, "producto", "productos"),
    countLabel(groups, "grupo", "grupos"),
)

private fun countLabel(count: Int, singular: String, plural: String): String? = when (count) {
    0 -> null
    1 -> "1 $singular"
    else -> "$count $plural"
}

/** «A»; «A» y «B»; «A», «B» y «C» (the items are already quoted or counted). */
private fun joinSpanish(items: List<String>): String =
    if (items.size == 1) items.single() else items.dropLast(1).joinToString(", ") + " y " + items.last()

/** The names of everything a goal can follow, to label its links and the goals sharing them. */
internal class LinkTargetNames(
    private val assets: Map<String, String>,
    private val holdings: Map<String, String>,
    private val groups: Map<String, String>,
) {
    /** The label for the given ids of each kind; ids that are not known (anymore) are left out. */
    fun label(assetIds: Collection<String>, holdingIds: Collection<String>, groupIds: Collection<String>): String? =
        linkedTargetLabel(
            accounts = assetIds.mapNotNull(assets::get),
            products = holdingIds.mapNotNull(holdings::get),
            groups = groupIds.mapNotNull(groups::get),
        )

    /** The label for ids that may be of any kind (an id belongs to one kind only). */
    fun label(ids: Collection<String>): String? = label(ids, ids, ids)
}

/** The form message for a failed create or update: the missing or unlinkable target when that is the cause. */
internal fun goalSaveErrorMessage(error: RuntimeException, editing: Boolean): String = when (error) {
    is SavingsGoalHoldingNotFoundException -> "Uno de los productos elegidos ya no existe. Quítalo de la selección."
    is SavingsGoalAssetNotFoundException -> "Una de las cuentas elegidas ya no existe. Quítala de la selección."
    is SavingsGoalGroupNotFoundException -> "Uno de los grupos elegidos ya no existe. Quítalo de la selección."
    is SavingsGoalBuiltinGroupException ->
        "«Todas las cuentas» no se puede seguir. Elige cuentas, productos u otros grupos."
    else -> if (editing) "No se pudo guardar la meta." else "No se pudo crear la meta. Revisa el importe y la divisa."
}
