package com.denebapps.patrimonio.domain.model

import kotlin.jvm.JvmInline

/** Scale factor for [Units]: `UNITS_SCALE` == one whole unit/share (6 decimals), like [RATE_SCALE] for rates. */
const val UNITS_SCALE: Long = 1_000_000L

/**
 * Exact fixed-point quantity of an instrument held (fund units, shares), scaled by [UNITS_SCALE]: 8.100001 units is
 * `scaled = 8_100_001`. Always positive — a position that is sold off is deleted, not kept at zero. Never a `Double`.
 */
@JvmInline
value class Units(val scaled: Long) {
    init {
        require(scaled > 0) { "units must be positive, was $scaled" }
    }
}
