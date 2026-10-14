package com.denebapps.patrimonio.data.quotes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** One row of the EODHD `search` answer; only the fields the app reads. */
@Serializable
data class EodhdSearchRow(
    @SerialName("Code") val code: String? = null,
    @SerialName("Exchange") val exchange: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Currency") val currency: String? = null,
    @SerialName("ISIN") val isin: String? = null,
    val isPrimary: Boolean? = null,
)

/** One daily bar of the EODHD `eod` answer. The app reads the `close` (the NAV of a fund), never the adjusted one. */
@Serializable
data class EodhdEodRow(val date: String? = null, val close: Double? = null)

/**
 * The EODHD `user` answer reduced to the quota counters. The account's name and e-mail are deliberately not declared:
 * they are never read, kept or logged. The counters are primitives so a number sent as a string still parses.
 */
@Serializable
data class EodhdUserResponse(
    val subscriptionType: String? = null,
    val apiRequests: JsonPrimitive? = null,
    val dailyRateLimit: JsonPrimitive? = null,
)
