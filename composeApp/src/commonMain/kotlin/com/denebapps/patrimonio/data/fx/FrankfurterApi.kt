package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.fx.dto.FrankfurterResponse
import com.denebapps.patrimonio.data.fx.dto.FrankfurterSeriesResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.datetime.LocalDate

/**
 * Wraps the confirmed live Frankfurter endpoint (verified at apply time via `curl` — see
 * design.md FX Pipeline / Open Questions): `api.frankfurter.dev` responds `200 OK` with the
 * `latest` payload; the legacy `api.frankfurter.app` host `301`-redirects and is intentionally not
 * used, per design's "no fallback-host mechanism".
 */
class FrankfurterApi(private val httpClient: HttpClient) {
    suspend fun latest(base: String, symbols: List<String>): FrankfurterResponse = httpClient.get(ENDPOINT) {
        parameter("base", base)
        parameter("symbols", symbols.joinToString(","))
    }.body()

    /**
     * The daily rates of [symbols] per 1 [base] from [from] to [to] (verified live: `/v1/2024-12-20..2025-01-10` answers
     * one entry per working day, daily whatever the length of the range).
     */
    suspend fun timeSeries(
        base: String,
        symbols: List<String>,
        from: LocalDate,
        to: LocalDate,
    ): FrankfurterSeriesResponse = httpClient.get("$SERIES_ENDPOINT/$from..$to") {
        parameter("base", base)
        parameter("symbols", symbols.joinToString(","))
    }.body()

    companion object {
        const val ENDPOINT = "https://api.frankfurter.dev/v1/latest"
        const val SERIES_ENDPOINT = "https://api.frankfurter.dev/v1"
    }
}
