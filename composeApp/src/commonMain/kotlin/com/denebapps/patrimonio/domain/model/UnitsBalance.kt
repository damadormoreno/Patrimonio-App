package com.denebapps.patrimonio.domain.model

import kotlin.jvm.JvmInline

/**
 * Exact fixed-point quantity that may be zero, scaled by [UNITS_SCALE]: the units a position holds at some date,
 * derived from its movements. [Units] stays strictly positive (it describes a quantity that is traded or valued);
 * a position that sold everything it bought holds `UnitsBalance.ZERO`. Never a `Double`.
 */
@JvmInline
value class UnitsBalance(val scaled: Long) : Comparable<UnitsBalance> {
    init {
        require(scaled >= 0) { "units balance must not be negative, was $scaled" }
    }

    val isZero: Boolean get() = scaled == 0L

    operator fun plus(other: UnitsBalance): UnitsBalance {
        val sum = scaled + other.scaled
        if (sum < 0) throw ArithmeticException("long overflow adding $scaled and ${other.scaled}")
        return UnitsBalance(sum)
    }

    operator fun minus(other: UnitsBalance) = UnitsBalance(scaled - other.scaled)

    override operator fun compareTo(other: UnitsBalance): Int = scaled.compareTo(other.scaled)

    /** These units as [Units], or `null` when the balance is zero. */
    fun toUnits(): Units? = if (scaled > 0) Units(scaled) else null

    companion object {
        val ZERO = UnitsBalance(0)
    }
}

/** This quantity as a [UnitsBalance] (always non-negative). */
fun Units.toBalance() = UnitsBalance(scaled)
