package com.denebapps.patrimonio.data.fx.dto

import kotlinx.serialization.Serializable

/**
 * Transport DTO for the Frankfurter `latest` endpoint (design.md FX Pipeline). `rates[code]` is
 * the amount of `code` per 1 [base] (e.g. `rates["USD"] = 1.0870` means 1 EUR = 1.0870 USD) — the
 * ONLY [Double] site in the codebase; inverted and scaled to a `Long` at the [base] boundary by
 * `FxMapping.invertAndScale`. The live response also includes an `amount` field which is ignored
 * (`ignoreUnknownKeys = true` on the shared [kotlinx.serialization.json.Json] instance).
 */
@Serializable
data class FrankfurterResponse(
    val base: String,
    val date: String,
    val rates: Map<String, Double>,
)
