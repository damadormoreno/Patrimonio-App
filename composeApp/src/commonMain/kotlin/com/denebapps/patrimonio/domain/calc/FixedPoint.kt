package com.denebapps.patrimonio.domain.calc

private const val LOW_32_BITS = 0xFFFF_FFFFuL

/**
 * `a * b / divisor` rounded half-to-even, computed with an exact 128-bit intermediate product so that
 * `unitsScaled * priceScaled` (easily beyond [Long]) does not overflow. Only the final result must fit in a [Long].
 *
 * The magnitudes are multiplied as unsigned 64-bit halves, divided by [divisor] with a shift-and-subtract long
 * division (128 steps, plenty for a handful of holdings), and rounded on the magnitude, so negative values round
 * symmetrically like [roundHalfEven].
 *
 * @throws IllegalArgumentException when [divisor] is not positive.
 * @throws ArithmeticException when the rounded result does not fit in a [Long].
 */
fun mulDivHalfEven(a: Long, b: Long, divisor: Long): Long {
    require(divisor > 0) { "divisor must be positive, was $divisor" }
    if (a == 0L || b == 0L) return 0L
    val negative = (a < 0) != (b < 0)
    val (high, low) = multiplyFull(magnitude(a), magnitude(b))
    val (quotient, remainder) = divideFull(high, low, divisor.toULong())
    // remainder < divisor <= Long.MAX_VALUE, so doubling it cannot overflow an unsigned 64-bit value.
    val doubled = remainder * 2uL
    val roundUp = doubled > divisor.toULong() || (doubled == divisor.toULong() && quotient.isOdd())
    if (roundUp && quotient == ULong.MAX_VALUE) throw ArithmeticException("long overflow in fixed-point result")
    return toSigned(if (roundUp) quotient + 1uL else quotient, negative)
}

/** `a + b`, throwing [ArithmeticException] instead of wrapping around. */
fun checkedAdd(a: Long, b: Long): Long {
    val sum = a + b
    if (((a xor sum) and (b xor sum)) < 0) throw ArithmeticException("long overflow adding $a and $b")
    return sum
}

/** `|value|` as an unsigned 64-bit number (`Long.MIN_VALUE` is 2^63, which fits). */
private fun magnitude(value: Long): ULong = if (value < 0) (-value).toULong() else value.toULong()

/** The exact 128-bit product of two unsigned 64-bit numbers as (high, low). */
private fun multiplyFull(x: ULong, y: ULong): Pair<ULong, ULong> {
    val xLow = x and LOW_32_BITS
    val xHigh = x shr 32
    val yLow = y and LOW_32_BITS
    val yHigh = y shr 32
    val lowLow = xLow * yLow
    val lowHigh = xLow * yHigh
    val highLow = xHigh * yLow
    val highHigh = xHigh * yHigh
    val middle = (lowLow shr 32) + (lowHigh and LOW_32_BITS) + (highLow and LOW_32_BITS)
    val low = (lowLow and LOW_32_BITS) or (middle shl 32)
    val high = highHigh + (lowHigh shr 32) + (highLow shr 32) + (middle shr 32)
    return high to low
}

/**
 * Divides the unsigned 128-bit number (high, low) by [divisor] (below 2^63) and returns (quotient, remainder). The
 * quotient must fit in 64 bits; otherwise the result of the whole operation overflows.
 */
private fun divideFull(high: ULong, low: ULong, divisor: ULong): Pair<ULong, ULong> {
    var quotientHigh = 0uL
    var quotient = 0uL
    var remainder = 0uL
    for (bit in 127 downTo 0) {
        val next = if (bit >= 64) (high shr (bit - 64)) and 1uL else (low shr bit) and 1uL
        // remainder < divisor < 2^63 before the shift, so the shifted value stays below 2^64.
        remainder = (remainder shl 1) or next
        quotientHigh = (quotientHigh shl 1) or (quotient shr 63)
        quotient = quotient shl 1
        if (remainder >= divisor) {
            remainder -= divisor
            quotient = quotient or 1uL
        }
    }
    if (quotientHigh != 0uL) throw ArithmeticException("long overflow in fixed-point multiplication")
    return quotient to remainder
}

private fun ULong.isOdd(): Boolean = (this and 1uL) == 1uL

/** [magnitude] with the sign applied, accepting exactly 2^63 only when negative. */
private fun toSigned(magnitude: ULong, negative: Boolean): Long {
    val limit = Long.MAX_VALUE.toULong() + if (negative) 1uL else 0uL
    if (magnitude > limit) throw ArithmeticException("long overflow in fixed-point result")
    return if (negative) -magnitude.toLong() else magnitude.toLong()
}
