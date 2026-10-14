package com.denebapps.patrimonio.data.datasources

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * What the user chose about optional market-data sources. [apiKey] is the user's own EODHD key (`null` when none):
 * secret, so [toString] never prints it. [eodhdPrimary] puts EODHD before the holding's own provider (default: only as
 * a fallback). [invalidKey] is set when EODHD refused the key and cleared when the key changes. [quotaExhaustedOn] is
 * the UTC day EODHD said its daily quota was used up: EODHD is skipped on that day.
 */
data class DataSourceSettings(
    val apiKey: String? = null,
    val eodhdPrimary: Boolean = false,
    val invalidKey: Boolean = false,
    val quotaExhaustedOn: LocalDate? = null,
) {
    override fun toString(): String =
        "DataSourceSettings(hasKey=${apiKey != null}, eodhdPrimary=$eodhdPrimary, invalidKey=$invalidKey, " +
            "quotaExhaustedOn=$quotaExhaustedOn)"
}

/**
 * The EODHD listing a holding resolved to, found on [checkedOn] (UTC day). [symbol] is `null` for "the instrument is not
 * on EODHD": remembered so it is not searched again on every refresh, and retried once the answer is old enough.
 * [currency] is EODHD's code of the listing (`EUR`, `GBX`).
 */
@Serializable
data class CachedSymbol(val symbol: String?, val currency: String?, val checkedOn: LocalDate)

/**
 * Local, device-only data-source settings and the EODHD symbol cache. Lives in its own DataStore file that Android's
 * backup and device transfer exclude, and is not part of the app's export or cloud backup: the key never leaves the
 * device.
 */
interface DataSourceSettingsStore {
    fun observe(): Flow<DataSourceSettings>

    /** The settings now. */
    suspend fun current(): DataSourceSettings = observe().first()

    /**
     * Stores [key] (trimmed) or, for `null` or a blank one, removes it. Either way the invalid flag, the exhausted
     * quota and the symbol cache are cleared: they described the previous key. Removing the key also switches
     * [DataSourceSettings.eodhdPrimary] off, so a key typed later starts as a fallback again.
     */
    suspend fun setApiKey(key: String?)

    suspend fun setEodhdPrimary(primary: Boolean)

    /** Flags the key as refused, but only while [key] is still the stored one (a call in flight may be stale). */
    suspend fun markInvalidKey(key: String)

    /** Records that the quota is used up on [day], but only while [key] is still the stored one. */
    suspend fun markQuotaExhausted(key: String, day: LocalDate)

    /** What was remembered for [identity] (a holding's `ISIN:…` or `TICKER:…`), or `null`. */
    suspend fun cachedSymbol(identity: String): CachedSymbol?

    suspend fun cacheSymbol(identity: String, entry: CachedSymbol)
}

private val API_KEY = stringPreferencesKey("eodhd_api_key")
private val PRIMARY_KEY = booleanPreferencesKey("eodhd_primary")
private val INVALID_KEY = booleanPreferencesKey("eodhd_invalid_key")
private val QUOTA_DAY_KEY = intPreferencesKey("eodhd_quota_exhausted_epoch_day")
private val SYMBOL_CACHE_KEY = stringPreferencesKey("eodhd_symbol_cache")

private val CacheJson = Json { ignoreUnknownKeys = true }

/** The symbol cache stored as [json]; anything unreadable (or absent) is an empty cache, never a failure. */
internal fun decodeSymbolCache(json: String?): Map<String, CachedSymbol> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        CacheJson.decodeFromString<Map<String, CachedSymbol>>(json)
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }
}

private fun encodeSymbolCache(cache: Map<String, CachedSymbol>): String = CacheJson.encodeToString(cache)

/**
 * Keeps the settings in their own DataStore file, which Android's auto backup and device transfer exclude. [scope] is the
 * DataStore's own scope (the default is the library's); a test cancels its own to open the same file again.
 */
class DataStoreDataSourceSettingsStore(filePath: String, scope: CoroutineScope? = null) : DataSourceSettingsStore {
    private val dataStore = if (scope == null) {
        PreferenceDataStoreFactory.createWithPath(produceFile = { filePath.toPath() })
    } else {
        PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { filePath.toPath() })
    }

    override fun observe(): Flow<DataSourceSettings> = dataStore.data.map { prefs ->
        DataSourceSettings(
            apiKey = prefs[API_KEY],
            eodhdPrimary = prefs[PRIMARY_KEY] ?: false,
            invalidKey = prefs[INVALID_KEY] ?: false,
            quotaExhaustedOn = prefs[QUOTA_DAY_KEY]?.let(LocalDate::fromEpochDays),
        )
    }

    override suspend fun setApiKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        dataStore.edit { prefs ->
            prefs.remove(INVALID_KEY)
            prefs.remove(QUOTA_DAY_KEY)
            prefs.remove(SYMBOL_CACHE_KEY)
            if (clean == null) {
                prefs.remove(API_KEY)
                prefs.remove(PRIMARY_KEY)
            } else {
                prefs[API_KEY] = clean
            }
        }
    }

    override suspend fun setEodhdPrimary(primary: Boolean) {
        dataStore.edit { it[PRIMARY_KEY] = primary }
    }

    override suspend fun markInvalidKey(key: String) {
        dataStore.edit { prefs -> if (prefs[API_KEY] == key) prefs[INVALID_KEY] = true }
    }

    override suspend fun markQuotaExhausted(key: String, day: LocalDate) {
        dataStore.edit { prefs -> if (prefs[API_KEY] == key) prefs[QUOTA_DAY_KEY] = day.toEpochDays() }
    }

    override suspend fun cachedSymbol(identity: String): CachedSymbol? =
        decodeSymbolCache(dataStore.data.first()[SYMBOL_CACHE_KEY])[identity]

    override suspend fun cacheSymbol(identity: String, entry: CachedSymbol) {
        dataStore.edit { prefs ->
            val cache = decodeSymbolCache(prefs[SYMBOL_CACHE_KEY])
            prefs[SYMBOL_CACHE_KEY] = encodeSymbolCache(cache + (identity to entry))
        }
    }
}
