package com.denebapps.patrimonio.domain.repository

import com.denebapps.patrimonio.domain.model.FxRates
import kotlinx.coroutines.flow.Flow

interface FxRepository {
    fun observeRates(): Flow<FxRates>

    /** Fetches fresh rates when the cache is stale (rolling 24h window); a no-op when fresh.
     *  Failures are non-fatal — the cache/fallback is kept and the failure is logged. */
    suspend fun refreshIfStale()
}
