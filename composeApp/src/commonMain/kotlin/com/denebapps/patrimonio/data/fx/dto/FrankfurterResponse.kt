package com.denebapps.patrimonio.data.fx.dto

import kotlinx.serialization.SerialName
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

/**
 * Transport DTO for the Frankfurter time-series endpoint (`/v1/{start}..{end}`): [rates] maps each working day
 * (`YYYY-MM-DD`) to the amount of every requested currency per 1 [base], like [FrankfurterResponse.rates]. Weekends
 * and bank holidays are absent. `start_date` may be earlier than requested (the API snaps it to a working day).
 */
@Serializable
data class FrankfurterSeriesResponse(
    val base: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val rates: Map<String, Map<String, Double>>,
)
