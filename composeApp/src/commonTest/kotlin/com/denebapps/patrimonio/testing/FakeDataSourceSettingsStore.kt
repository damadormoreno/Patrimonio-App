package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.data.datasources.CachedSymbol
import com.denebapps.patrimonio.data.datasources.DataSourceSettings
import com.denebapps.patrimonio.data.datasources.DataSourceSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate

/** In-memory [DataSourceSettingsStore] that follows the same rules as the DataStore one. */
class FakeDataSourceSettingsStore(initial: DataSourceSettings = DataSourceSettings()) : DataSourceSettingsStore {
    private val state = MutableStateFlow(initial)
    private val cache = mutableMapOf<String, CachedSymbol>()

    /** Every identity passed to [cacheSymbol], in order. */
    val cacheWrites = mutableListOf<String>()

    override fun observe(): Flow<DataSourceSettings> = state

    override suspend fun setApiKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        cache.clear()
        state.value = if (clean == null) {
            DataSourceSettings()
        } else {
            DataSourceSettings(apiKey = clean, eodhdPrimary = state.value.eodhdPrimary)
        }
    }

    override suspend fun setEodhdPrimary(primary: Boolean) {
        state.value = state.value.copy(eodhdPrimary = primary)
    }

    override suspend fun markInvalidKey(key: String) {
        if (state.value.apiKey == key) state.value = state.value.copy(invalidKey = true)
    }

    override suspend fun markQuotaExhausted(key: String, day: LocalDate) {
        if (state.value.apiKey == key) state.value = state.value.copy(quotaExhaustedOn = day)
    }

    override suspend fun cachedSymbol(identity: String): CachedSymbol? = cache[identity]

    override suspend fun cacheSymbol(identity: String, entry: CachedSymbol) {
        cacheWrites += identity
        cache[identity] = entry
    }
}
