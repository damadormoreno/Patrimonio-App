package com.denebapps.patrimonio.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val FIRST_NAME_KEY = stringPreferencesKey("first_name")
private val LAST_NAME_KEY = stringPreferencesKey("last_name")

/** Default theme mode when no preference has been written yet (spec: `datastore-preferences`). */
private val DEFAULT_THEME_MODE = ThemeMode.SYSTEM

class PreferencesRepositoryImpl(filePath: String) : PreferencesRepository {
    private val dataStore = PreferenceDataStoreFactory.createWithPath(
        produceFile = { filePath.toPath() },
    )

    override fun observeThemeMode(): Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { raw -> runCatching { ThemeMode.valueOf(raw) }.getOrNull() }
            ?: DEFAULT_THEME_MODE
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    override fun observeFirstName(): Flow<String> = dataStore.data.map { prefs ->
        prefs[FIRST_NAME_KEY].orEmpty()
    }

    override suspend fun setFirstName(value: String) {
        dataStore.edit { it[FIRST_NAME_KEY] = value }
    }

    override fun observeLastName(): Flow<String> = dataStore.data.map { prefs ->
        prefs[LAST_NAME_KEY].orEmpty()
    }

    override suspend fun setLastName(value: String) {
        dataStore.edit { it[LAST_NAME_KEY] = value }
    }
}
