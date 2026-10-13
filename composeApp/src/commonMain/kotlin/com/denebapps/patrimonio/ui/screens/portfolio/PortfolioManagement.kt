package com.denebapps.patrimonio.ui.screens.portfolio

import com.denebapps.patrimonio.domain.model.InvalidPortfolioNameException
import com.denebapps.patrimonio.domain.model.MAX_PORTFOLIO_NAME_LENGTH
import com.denebapps.patrimonio.domain.model.Portfolio
import com.denebapps.patrimonio.domain.model.PortfolioHolding
import com.denebapps.patrimonio.domain.model.PortfolioNameProblem
import com.denebapps.patrimonio.domain.repository.DuplicatePortfolioNameException
import com.denebapps.patrimonio.domain.repository.LastPortfolioException
import com.denebapps.patrimonio.domain.repository.PortfolioCollectionRepository
import com.denebapps.patrimonio.domain.repository.PortfolioNotEmptyException
import com.denebapps.patrimonio.domain.repository.PortfolioRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

// Managing a portfolio (creating, renaming, deleting): what the dialogs show and say, shared by the Cartera tab (which
// creates) and the portfolio detail (which renames and deletes the portfolio it is about).

internal const val LAST_PORTFOLIO_TEXT = "Necesitas al menos una cartera."

/**
 * A dialog of portfolio management. [Name] creates or renames a portfolio ([name] is what the field holds); [Delete]
 * confirms deleting one and, when it has holdings, asks which of the other portfolios ([targets]) receives them
 * ([targetId] is the chosen one). [error] is the reason the last confirmation was refused, ready to show.
 */
sealed interface PortfolioDialogUi {
    val error: String?

    data class Name(
        val title: String,
        val confirmLabel: String,
        val name: String,
        override val error: String?,
    ) : PortfolioDialogUi

    data class Delete(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val targets: List<PortfolioOptionUi>,
        val targetId: String?,
        override val error: String?,
    ) : PortfolioDialogUi
}

/** The chosen target when it is still one of the [others]; the first of them otherwise. */
internal fun targetOf(others: List<Portfolio>, chosenId: String?): String? =
    others.firstOrNull { it.id == chosenId }?.id ?: others.firstOrNull()?.id

internal fun movedHoldingsText(count: Int): String =
    if (count == 1) "Su producto pasará a:" else "Sus $count productos pasarán a:"

/** Why a portfolio change was refused, in words for the user. */
internal fun portfolioErrorMessage(failure: Exception): String = when (failure) {
    is DuplicatePortfolioNameException -> "Ya tienes una cartera con ese nombre."
    is InvalidPortfolioNameException -> when (failure.problem) {
        PortfolioNameProblem.BLANK -> "Escribe un nombre para la cartera."
        PortfolioNameProblem.TOO_LONG -> "El nombre no puede tener más de $MAX_PORTFOLIO_NAME_LENGTH caracteres."
    }
    is LastPortfolioException -> LAST_PORTFOLIO_TEXT
    is PortfolioNotEmptyException -> "Elige a qué cartera pasan sus productos."
    else -> "No se pudo guardar el cambio."
}

/**
 * What the detail of a portfolio offers to do with it ("Gestionar cartera"): rename, and delete unless
 * [deleteBlockedReason] says why it cannot be (it is the last portfolio).
 */
data class PortfolioManagementUi(val deleteBlockedReason: String?)

/** The management part of the portfolio detail: the section, and the dialog while one is open. */
internal data class ManagementPanel(
    val management: PortfolioManagementUi? = null,
    val dialog: PortfolioDialogUi? = null,
)

/** The dialog as [PortfolioManagement] keeps it; the portfolio it is about is always the managed one. */
private sealed interface ManageDialog {
    val error: String?

    data class Rename(val name: String, override val error: String? = null) : ManageDialog

    data class Delete(val targetId: String? = null, override val error: String? = null) : ManageDialog
}

/**
 * Renaming and deleting the portfolio that the detail of [scopeId] is about: that portfolio, or the only one when
 * [scopeId] is `null` and there is just one; with several portfolios shown together there is nothing to manage. Keeps
 * the open dialog; deleting moves the holdings to the portfolio the user picked and, when Cartera was showing the
 * deleted portfolio, sends it back to all of them through [preferences].
 */
internal class PortfolioManagement(
    private val scopeId: String?,
    private val portfolios: PortfolioCollectionRepository,
    private val holdings: PortfolioRepository,
    private val preferences: PreferencesRepository,
) {
    private val dialog = MutableStateFlow<ManageDialog?>(null)
    private var confirming = false

    val panel: Flow<ManagementPanel> = combine(portfolios.observePortfolios(), holdings.observeAll(), dialog, ::panelOf)

    private fun panelOf(all: List<Portfolio>, owned: List<PortfolioHolding>, shown: ManageDialog?): ManagementPanel {
        val managed = managedIn(all) ?: return ManagementPanel()
        return ManagementPanel(
            management = PortfolioManagementUi(LAST_PORTFOLIO_TEXT.takeIf { all.size < MIN_PORTFOLIOS_TO_CHOOSE }),
            dialog = dialogUi(shown, managed, all, owned),
        )
    }

    /** Opens the dialog that renames the portfolio; nothing happens when there is none to manage. */
    suspend fun openRename() {
        val managed = managedIn(portfolios.observePortfolios().first()) ?: return
        dialog.value = ManageDialog.Rename(managed.name)
    }

    /** Opens the confirmation to delete the portfolio; nothing happens for the last portfolio or when none applies. */
    suspend fun openDelete() {
        val all = portfolios.observePortfolios().first()
        if (all.size < MIN_PORTFOLIOS_TO_CHOOSE || managedIn(all) == null) return
        dialog.value = ManageDialog.Delete()
    }

    /** What the name field of the rename dialog holds; a refused name's error goes away. */
    fun changeName(text: String) {
        dialog.update { if (it is ManageDialog.Rename) it.copy(name = text, error = null) else it }
    }

    /** The portfolio that receives the holdings of the one being deleted. */
    fun changeTarget(portfolioId: String) {
        dialog.update { if (it is ManageDialog.Delete) it.copy(targetId = portfolioId) else it }
    }

    fun dismiss() {
        dialog.value = null
    }

    /**
     * Does what the open dialog asks and closes it; a refusal keeps it open with its reason. Returns `true` when the
     * portfolio was deleted (the screen is about a portfolio that is gone), `false` otherwise.
     */
    suspend fun confirm(): Boolean {
        val current = dialog.value ?: return false
        if (confirming) return false
        confirming = true
        return try {
            val deleted = apply(current)
            dialog.value = null
            deleted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = portfolioErrorMessage(failure)
            dialog.update { shown -> if (shown == current) current.withError(message) else shown }
            false
        } finally {
            confirming = false
        }
    }

    /** Renames or deletes the managed portfolio; `true` when it deleted it. */
    private suspend fun apply(current: ManageDialog): Boolean {
        val all = portfolios.observePortfolios().first()
        val managed = managedIn(all) ?: return false
        when (current) {
            is ManageDialog.Rename -> portfolios.renamePortfolio(managed.id, current.name)
            is ManageDialog.Delete -> {
                val others = all.filter { it.id != managed.id }
                val holdingCount = holdings.observeAll().first().count { it.portfolioId == managed.id }
                val target = if (holdingCount > 0) targetOf(others, current.targetId) else null
                portfolios.deletePortfolio(managed.id, target)
                // Cartera was showing the deleted portfolio: back to all of them.
                if (preferences.observeSelectedPortfolioId().first() == managed.id) {
                    preferences.setSelectedPortfolioId(null)
                }
            }
        }
        return current is ManageDialog.Delete
    }

    /** The portfolio of the scope, or the only one when the scope is all of them. */
    private fun managedIn(all: List<Portfolio>): Portfolio? =
        all.firstOrNull { it.id == scopeId } ?: all.singleOrNull().takeIf { scopeId == null }

    private fun dialogUi(
        shown: ManageDialog?,
        managed: Portfolio,
        all: List<Portfolio>,
        owned: List<PortfolioHolding>,
    ): PortfolioDialogUi? = when (shown) {
        null -> null
        is ManageDialog.Rename -> PortfolioDialogUi.Name(
            title = "Renombrar cartera",
            confirmLabel = "Guardar",
            name = shown.name,
            error = shown.error,
        )
        is ManageDialog.Delete -> {
            val others = all.filter { it.id != managed.id }
            val holdingCount = owned.count { it.portfolioId == managed.id }
            val withHoldings = holdingCount > 0
            PortfolioDialogUi.Delete(
                title = "Eliminar «${managed.name}»",
                message = if (withHoldings) movedHoldingsText(holdingCount) else "Se eliminará la cartera.",
                confirmLabel = if (withHoldings) "Mover y eliminar" else "Eliminar",
                targets = if (withHoldings) others.toOptions() else emptyList(),
                targetId = if (withHoldings) targetOf(others, shown.targetId) else null,
                error = shown.error,
            )
        }
    }
}

private fun ManageDialog.withError(message: String): ManageDialog = when (this) {
    is ManageDialog.Rename -> copy(error = message)
    is ManageDialog.Delete -> copy(error = message)
}
