package com.denebapps.patrimonio.data.quotes.dto

import kotlinx.serialization.Serializable

/** Transport DTO of the Yahoo `v1/finance/search` answer; only the fields the app reads. */
@Serializable
data class YahooSearchResponse(val quotes: List<YahooSearchQuote> = emptyList())

@Serializable
data class YahooSearchQuote(
    val symbol: String? = null,
    val quoteType: String? = null,
    val shortname: String? = null,
    val longname: String? = null,
    val exchange: String? = null,
    val exchDisp: String? = null,
)

/** Transport DTO of the Yahoo `v8/finance/chart` answer. `close` entries are `null` for bars without a price yet. */
@Serializable
data class YahooChartResponse(val chart: YahooChart? = null)

@Serializable
data class YahooChart(val result: List<YahooChartResult>? = null)

@Serializable
data class YahooChartResult(
    val meta: YahooChartMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: YahooChartIndicators? = null,
)

@Serializable
data class YahooChartMeta(val currency: String? = null)

@Serializable
data class YahooChartIndicators(val quote: List<YahooChartQuote>? = null)

@Serializable
data class YahooChartQuote(val close: List<Double?>? = null)
