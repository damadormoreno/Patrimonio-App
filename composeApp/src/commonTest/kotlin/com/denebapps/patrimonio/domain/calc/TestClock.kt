package com.denebapps.patrimonio.domain.calc

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** A [Clock] fixed at [isoInstant] (e.g. "2026-05-21T12:00:00Z"), for deterministic date tests. */
internal fun fixedClock(isoInstant: String): Clock {
    val instant = Instant.parse(isoInstant)
    return object : Clock {
        override fun now(): Instant = instant
    }
}

internal class MutableTestClock(isoInstant: String) : Clock {
    var instant: Instant = Instant.parse(isoInstant)

    override fun now(): Instant = instant
}
