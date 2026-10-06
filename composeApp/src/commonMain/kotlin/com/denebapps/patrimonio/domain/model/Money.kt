package com.denebapps.patrimonio.domain.model

import kotlin.jvm.JvmInline

/**
 * Integer minor-unit money amount (e.g. cents). Currency-less by design: aggregated/summed
 * [Money] values (totals, net worth, budget spent, group totals) are, by convention, always EUR
 * unless explicitly noted otherwise — currency tagging happens via [CurrencyAmount] or the
 * surrounding calc contract. No `Double`/`Float` is ever involved.
 */
@JvmInline
value class Money(val minorUnits: Long) {
    operator fun plus(other: Money) = Money(minorUnits + other.minorUnits)

    operator fun minus(other: Money) = Money(minorUnits - other.minorUnits)

    operator fun times(n: Long) = Money(minorUnits * n)

    operator fun unaryMinus() = Money(-minorUnits)

    operator fun compareTo(other: Money) = minorUnits.compareTo(other.minorUnits)

    companion object {
        val ZERO = Money(0)
    }
}
