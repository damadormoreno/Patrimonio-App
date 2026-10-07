package com.denebapps.patrimonio.ui.navigation

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

@Serializable
object Subscriptions

// ── Main zone · pushed (non-tab) destinations ───────────────────────────────

@Serializable
object Settings

@Serializable
object Profile

/** Month-by-month net worth ("Evolución mensual"), opened from the Patrimonio header. */
@Serializable
object NetWorthHistory

/** Add-only Patrimonio item form (spec: Add Patrimonio Item Form). [isLiability] is the mode the
 *  route is opened with — the FAB uses the active Activos/Pasivos toggle, a per-group add affordance
 *  uses that card's mode — but the user MAY still switch mode inside the form. [groupId] prefills the
 *  group selection when opened from a per-group add affordance; `null` from the FAB / "Nuevo
 *  activo/pasivo" CTA. */
@Serializable
data class AddPatrimonio(val isLiability: Boolean = false, val groupId: String? = null)

/** Account-groups list (spec: Account Groups List, Group Edit and Delete). */
@Serializable
object Grupos

/** Account-group form: creates a group, or edits the one with [groupId]. */
@Serializable
data class NuevoGrupo(val groupId: String? = null)

/** Create-goal form (spec: `savings-goals-ui` — Create Goal Form). */
@Serializable
object NewGoal

/** Allocate/withdraw destination for one savings goal (spec: `savings-goals-ui` — Allocate and
 *  Withdraw Funds, Cancel and Closed-Goal Restrictions). [withdraw] seeds the sheet's
 *  allocate/withdraw toggle — `false` from a plain goal-row tap, `true` from a dedicated withdraw
 *  affordance — but stays user-switchable inside the sheet. */
@Serializable
data class GoalAllocate(val goalId: String, val withdraw: Boolean = false)

/** Create ([subscriptionId] null, from the FAB or the list CTA) or edit one subscription. */
@Serializable
data class EditSubscription(val subscriptionId: String? = null)
