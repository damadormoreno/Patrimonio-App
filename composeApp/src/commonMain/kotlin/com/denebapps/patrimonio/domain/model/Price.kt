package com.denebapps.patrimonio.domain.model

import kotlin.math.abs
import kotlin.math.round

/** Scale factor for [Price]: `PRICE_SCALE` == one major unit of the currency (6 decimals). */
const val PRICE_SCALE: Long = 1_000_000L

/**
 * Exact fixed-point price of one unit of an instrument, expressed in the MAJOR unit of [currency]
 * (e.g. 12.345678 EUR is `scaled = 12_345_678`) — not in minor units like [Money], because fund NAVs
 * and share prices need more than 2 decimals. Never holds a `Double`: JSON doubles are converted at the
 * network boundary with [fromDouble].
 */
data class Price(val scaled: Long, val currency: Currency) {
    init {
        require(scaled >= 0) { "price must not be negative, was $scaled" }
    }

    companion object {
        /** Largest magnitude whose scaled value is still exactly representable as a [Long]. */
        private const val MAX_DOUBLE_SCALED = 9.0E18

        /**
         * Converts a quoted [value] (major units of [currency]) to a [Price], rounding half-to-even at the
         * sixth decimal. This is the only `Double` site of the pricing pipeline (like `invertAndScale` for FX).
         *
         * @throws IllegalArgumentException when [value] is not finite, is negative, or does not fit once scaled.
         */
        fun fromDouble(value: Double, currency: Currency): Price {
            require(value.isFinite() && value >= 0.0) { "price must be finite and non-negative, was $value" }
            val scaled = value * PRICE_SCALE.toDouble()
            require(abs(scaled) < MAX_DOUBLE_SCALED) { "price $value overflows the fixed-point range" }
            return Price(round(scaled).toLong(), currency)
        }
    }
}
