package com.denebapps.patrimonio.ui.navigation

import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.QuoteProvider
import kotlinx.serialization.Serializable

/**
 * Root-level navigation zones. The root `NavHost` (see [RootNavHost]) starts at
 * [MainZone]; [AuthZone] and [LockZone] are structurally defined for a future
 * gating change (login, PIN/biometric lock) but are not yet reachable — no
 * navigation action in this change transitions to either.
 */
@Serializable
object AuthZone

@Serializable
object LockZone

@Serializable
object MainZone

// ── Main zone · tab destinations (inner NavHost, see MainScaffold) ─────────

@Serializable
object Patrimonio

/** The investment portfolio ("Cartera") tab. */
@Serializable
object Cartera

// ── Main zone · pushed (non-tab) destinations ───────────────────────────────

@Serializable
object Settings

@Serializable
object Profile

/** Optional cloud account (sign in, create, sign out, delete), opened from Ajustes. */
@Serializable
object Account

/** Month-by-month net worth ("Evolución mensual"), opened from the Patrimonio header. */
@Serializable
object NetWorthHistory

/** Patrimonio item form (spec: Add Patrimonio Item Form); with [itemId] it edits that asset or liability
 *  ([isLiability] says which) instead of creating one. [isLiability] is the mode the
 *  route is opened with — the FAB uses the active Activos/Pasivos toggle, a per-group add affordance
 *  uses that card's mode — but the user MAY still switch mode inside the form. [groupId] prefills the
 *  group selection when opened from a per-group add affordance; `null` from the FAB / "Nuevo
 *  activo/pasivo" CTA. */
@Serializable
data class AddPatrimonio(val isLiability: Boolean = false, val groupId: String? = null, val itemId: String? = null)

/** Account-groups list (spec: Account Groups List, Group Edit and Delete). */
@Serializable
object Grupos

/** Account-group form: creates a group, or edits the one with [groupId]. */
@Serializable
data class NuevoGrupo(val groupId: String? = null)

/** Goal form (spec: `savings-goals-ui` — Create Goal Form): creates a goal, or edits the one with
 *  [goalId]. */
@Serializable
data class NewGoal(val goalId: String? = null)

/** Allocate/withdraw destination for one savings goal (spec: `savings-goals-ui` — Allocate and
 *  Withdraw Funds, Cancel and Closed-Goal Restrictions). [withdraw] seeds the sheet's
 *  allocate/withdraw toggle — `false` from a plain goal-row tap, `true` from a dedicated withdraw
 *  affordance — but stays user-switchable inside the sheet. */
@Serializable
data class GoalAllocate(val goalId: String, val withdraw: Boolean = false)

/** Form that adds a holding to the portfolio: search a product, pick it and enter what is held, or enter it by hand. */
@Serializable
object AddHolding

/**
 * Preview of a search result before it is added, pushed over [AddHolding] (which keeps its search and its state while
 * it is open). The result is identified by its [provider] (the name of its `QuoteProvider`) and its [code] there; the
 * candidate itself lives in the add form, which is where the search happened.
 */
@Serializable
data class InstrumentPreview(val provider: String, val code: String) {
    /** The instrument this route previews. */
    val ref: InstrumentRef get() = InstrumentRef(QuoteProvider.valueOf(provider), code)

    companion object {
        fun of(ref: InstrumentRef) = InstrumentPreview(ref.provider.name, ref.code)
    }
}

/** Position detail of one portfolio holding: its price history, returns and product data (a pushed screen). */
@Serializable
data class PositionDetail(val holdingId: String)

/**
 * Portfolio detail: value over time, correlation between products, split, costs and concentration (pushed). It is the
 * detail of the portfolio [portfolioId], or of all of them when it is `null`.
 */
@Serializable
data class PortfolioDetail(val portfolioId: String? = null)

/** Edit sheet of one portfolio holding: its value, units to edit, and delete; opened from [PositionDetail]. */
@Serializable
data class HoldingDetail(val holdingId: String)
