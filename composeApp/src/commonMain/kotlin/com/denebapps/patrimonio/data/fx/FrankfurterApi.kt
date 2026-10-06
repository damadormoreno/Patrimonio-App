package com.denebapps.patrimonio.data.fx

import com.denebapps.patrimonio.data.fx.dto.FrankfurterResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

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

    companion object {
        const val ENDPOINT = "https://api.frankfurter.dev/v1/latest"
    }
}
