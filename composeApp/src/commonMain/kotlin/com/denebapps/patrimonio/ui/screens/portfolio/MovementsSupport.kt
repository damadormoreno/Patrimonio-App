package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.calc.formatUnits
import com.denebapps.patrimonio.domain.model.HoldingValuation
import com.denebapps.patrimonio.domain.model.Movement
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import kotlinx.coroutines.flow.MutableStateFlow

// What the screens that list the movements of a position (the detail and the full list) have in common: who is on the
// other side of a transfer, where a movement opens, and the question asked when the first one is added.

/** The text of a position without movements: its units are edited by hand. */
internal const val EMPTY_MOVEMENTS_TEXT = "Sin movimientos: las participaciones se editan a mano."

/**
 * Names the fund on the other side of a transfer leg: the one that holds the other leg of the same transfer among
 * [movements], called as in [holdings]. `null` for any other movement or when that fund is not known.
 */
internal fun counterpartNames(holdings: List<PortfolioHolding>, movements: List<Movement>): (Movement) -> String? {
    val namesByHolding = holdings.associate { it.id to it.name }
    val transfers = movements.filter { it.transferId != null }.groupBy { it.transferId }
    return { leg -> transfers[leg.transferId]?.firstOrNull { it.id != leg.id }?.let { namesByHolding[it.holdingId] } }
}

/** Where opening this movement leads: the sheet that edits it, or its transfer when it is a leg of one. */
internal fun Movement.editTarget(): PositionNavigation {
    val transfer = transferId
    return if (transfer != null) {
        PositionNavigation.Transfer(transfer)
    } else {
        PositionNavigation.MovementForm(id, openingBalance = type == MovementType.OPENING_BALANCE)
    }
}

/** The question about a position that already has units: both ways to start, and what the second one does. */
internal fun openingChoiceUi(holding: PortfolioHolding, step: OpeningChoiceStep): OpeningChoiceUi? {
    val units = (holding.valuation as? HoldingValuation.Quoted)?.units ?: return null
    val unitsText = "${formatUnits(units)} part."
    return OpeningChoiceUi(
        step = step,
        openingLabel = "Saldo inicial con tus $unitsText",
        oneByOneLabel = "Meter mis compras una a una",
        warning = if (step == OpeningChoiceStep.WARN_ONE_BY_ONE) {
            "Las participaciones pasarán a salir de tus movimientos: los $unitsText que tienes ahora se " +
                "sustituirán por lo que registres."
        } else {
            null
        },
    )
}

/**
 * The state of "Añadir movimiento" for a screen: a position that has units but no movements first asks whether to
 * record them as an opening balance or to enter the purchases one by one ([step]); any other goes straight to the
 * movement sheet. Where to go is told to [navigate].
 */
internal class AddMovementPrompt(private val navigate: (PositionNavigation) -> Unit) {
    /** The question being asked, `null` when none. */
    val step = MutableStateFlow<OpeningChoiceStep?>(null)

    /** "Añadir movimiento": asks first when [needsChoice], otherwise opens the movement sheet. */
    fun onAdd(needsChoice: Boolean) {
        if (needsChoice) {
            step.value = OpeningChoiceStep.CHOOSE
        } else {
            navigate(PositionNavigation.MovementForm(null, openingBalance = false))
        }
    }

    /** The user keeps the units the position has as its opening balance. */
    fun onChooseOpeningBalance() {
        step.value = null
        navigate(PositionNavigation.MovementForm(null, openingBalance = true))
    }

    /** The user will enter the purchases one by one; shows what happens to the units they have now. */
    fun onChooseOneByOne() {
        step.value = OpeningChoiceStep.WARN_ONE_BY_ONE
    }

    /** The user accepts that the units will come from the movements and goes on to the first one. */
    fun onConfirmOneByOne() {
        step.value = null
        navigate(PositionNavigation.MovementForm(null, openingBalance = false))
    }

    fun onDismiss() {
        step.value = null
    }
}
