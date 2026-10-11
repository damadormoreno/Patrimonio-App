package com.denebapps.patrimonio.data.quotes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Transport DTO of the Morningstar `security/screener` answer; only the fields the app reads. The four fundamentals
 * datapoints stay raw [JsonElement]s: the endpoint is unofficial and a number may arrive quoted, or a text as a
 * number, so each is interpreted (and ignored when unusable) by `toFundamentals` instead of failing the whole answer.
 */
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
    @SerialName("OngoingCharge") val ongoingCharge: JsonElement? = null,
    @SerialName("CollectedSRRI") val collectedSrri: JsonElement? = null,
    @SerialName("CategoryName") val categoryName: JsonElement? = null,
    @SerialName("BrandingCompanyName") val brandingCompanyName: JsonElement? = null,
)
