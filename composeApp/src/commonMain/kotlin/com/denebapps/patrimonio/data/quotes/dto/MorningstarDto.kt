package com.denebapps.patrimonio.data.quotes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport DTO of the Morningstar `security/screener` answer; only the fields the app reads. */
@Serializable
data class MorningstarScreenerResponse(val rows: List<MorningstarScreenerRow> = emptyList())

@Serializable
data class MorningstarScreenerRow(
    @SerialName("SecId") val secId: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("isin") val isin: String? = null,
    @SerialName("Universe") val universe: String? = null,
    @SerialName("PriceCurrency") val priceCurrency: String? = null,
    @SerialName("Ticker") val ticker: String? = null,
)
