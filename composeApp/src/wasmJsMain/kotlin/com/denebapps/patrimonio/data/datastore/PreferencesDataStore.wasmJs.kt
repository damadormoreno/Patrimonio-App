package com.denebapps.patrimonio.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * DataStore's own web storage needs DataStore 1.3, which needs Kotlin 2.4; until then the preferences live in the
 * browser's local storage, one JSON entry per [path], with each value tagged with its type so the typed keys come
 * back. One tab writes at a time is assumed (as DataStore assumes one process per file).
 */
actual fun preferencesDataStore(path: String, scope: CoroutineScope?): DataStore<Preferences> =
    LocalStorageDataStore("patrimonio:$path")

private class LocalStorageDataStore(private val storageKey: String) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(read())

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        val updated = transform(state.value).toPreferences()
        if (updated != state.value) {
            localStorage.setItem(storageKey, encode(updated))
            state.value = updated
        }
        updated
    }

    private fun read(): Preferences = localStorage.getItem(storageKey)?.let(::decode) ?: emptyPreferences()
}

private fun encode(preferences: Preferences): String = JsonObject(
    preferences.asMap().entries.associate { (key, value) ->
        key.name to when (value) {
            is String -> tagged("s", JsonPrimitive(value))
            is Int -> tagged("i", JsonPrimitive(value))
            is Long -> tagged("l", JsonPrimitive(value))
            is Boolean -> tagged("b", JsonPrimitive(value))
            is Double -> tagged("d", JsonPrimitive(value))
            is Float -> tagged("f", JsonPrimitive(value))
            is Set<*> -> tagged("ss", JsonArray(value.map { JsonPrimitive(it as String) }))
            else -> error("Unsupported preference type for ${key.name}: ${value::class}")
        }
    },
).toString()

private fun tagged(type: String, value: kotlinx.serialization.json.JsonElement) =
    JsonObject(mapOf("t" to JsonPrimitive(type), "v" to value))

private fun decode(json: String): Preferences {
    val preferences = emptyPreferences().toMutablePreferences()
    Json.parseToJsonElement(json).jsonObject.forEach { (name, element) ->
        val entry = element.jsonObject
        val value = entry.getValue("v")
        when (entry.getValue("t").jsonPrimitive.content) {
            "s" -> preferences[stringPreferencesKey(name)] = value.jsonPrimitive.content
            "i" -> preferences[intPreferencesKey(name)] = value.jsonPrimitive.int
            "l" -> preferences[longPreferencesKey(name)] = value.jsonPrimitive.long
            "b" -> preferences[booleanPreferencesKey(name)] = value.jsonPrimitive.boolean
            "d" -> preferences[doublePreferencesKey(name)] = value.jsonPrimitive.double
            "f" -> preferences[floatPreferencesKey(name)] = value.jsonPrimitive.float
            "ss" -> preferences[stringSetPreferencesKey(name)] =
                value.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
    }
    return preferences.toPreferences()
}
