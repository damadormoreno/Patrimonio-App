package com.denebapps.patrimonio.data.quotes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport DTO of the CoinGecko `search` answer; only the coins, the other sections are ignored. */
@Serializable
data class CoinGeckoSearchResponse(val coins: List<CoinGeckoCoin> = emptyList())

/** `marketCapRank` is `null` for coins CoinGecko does not rank. */
@Serializable
data class CoinGeckoCoin(
    val id: String? = null,
    val symbol: String? = null,
    val name: String? = null,
    @SerialName("market_cap_rank") val marketCapRank: Int? = null,
)

/** One entry of the CoinGecko `simple/price` answer, keyed by coin id; `eur` can arrive as an integer. */
@Serializable
data class CoinGeckoSimplePrice(
    val eur: Double? = null,
    @SerialName("last_updated_at") val lastUpdatedAt: Long? = null,
)

/**
 * Transport DTO of the CoinGecko `market_chart` answer: `prices` holds `[epochMillis, price]` pairs. The key is absent
 * when the API answers with an error body instead (e.g. a range the public API refuses).
 */
@Serializable
data class CoinGeckoChartResponse(val prices: List<List<Double?>>? = null)
