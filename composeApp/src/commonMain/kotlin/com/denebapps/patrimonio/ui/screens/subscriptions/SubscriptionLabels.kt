package com.denebapps.patrimonio.ui.screens.subscriptions

import com.denebapps.patrimonio.domain.model.BillingCycle

internal fun cycleLabel(cycle: BillingCycle): String = when (cycle) {
    BillingCycle.WEEKLY -> "Semanal"
    BillingCycle.MONTHLY -> "Mensual"
    BillingCycle.QUARTERLY -> "Trimestral"
    BillingCycle.SEMIANNUAL -> "Semestral"
    BillingCycle.YEARLY -> "Anual"
}

/** "Hoy", "Mañana", "En 3 días". */
internal fun nextChargeLabel(daysUntil: Int): String = when (daysUntil) {
    0 -> "Hoy"
    1 -> "Mañana"
    else -> "En $daysUntil días"
}

/** "1 activa", "3 activas". */
internal fun activeCountLabel(count: Int): String = if (count == 1) "1 activa" else "$count activas"
