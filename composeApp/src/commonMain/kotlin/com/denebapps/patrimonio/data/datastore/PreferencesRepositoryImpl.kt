package com.denebapps.patrimonio.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.denebapps.patrimonio.domain.repository.CarteraFilterSelection
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val FIRST_NAME_KEY = stringPreferencesKey("first_name")
private val LAST_NAME_KEY = stringPreferencesKey("last_name")
private val SELECTED_PORTFOLIO_KEY = stringPreferencesKey("selected_portfolio_id")
private val CARTERA_FILTER_CLASSES_KEY = stringSetPreferencesKey("cartera_filter_asset_classes")
private val CARTERA_FILTER_BROKERS_KEY = stringSetPreferencesKey("cartera_filter_brokers")
private val CARTERA_FILTER_TYPES_KEY = stringSetPreferencesKey("cartera_filter_types")

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

    override fun observeSelectedPortfolioId(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[SELECTED_PORTFOLIO_KEY]
    }

    override suspend fun setSelectedPortfolioId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(SELECTED_PORTFOLIO_KEY) else prefs[SELECTED_PORTFOLIO_KEY] = id
        }
    }

    override fun observeCarteraFilter(): Flow<CarteraFilterSelection> = dataStore.data.map { prefs ->
        CarteraFilterSelection(
            assetClasses = prefs[CARTERA_FILTER_CLASSES_KEY].orEmpty(),
            brokers = prefs[CARTERA_FILTER_BROKERS_KEY].orEmpty(),
            types = prefs[CARTERA_FILTER_TYPES_KEY].orEmpty(),
        )
    }

    override suspend fun setCarteraFilter(filter: CarteraFilterSelection) {
        dataStore.edit { prefs ->
            prefs.putOrRemove(CARTERA_FILTER_CLASSES_KEY, filter.assetClasses)
            prefs.putOrRemove(CARTERA_FILTER_BROKERS_KEY, filter.brokers)
            prefs.putOrRemove(CARTERA_FILTER_TYPES_KEY, filter.types)
        }
    }

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<Set<String>>, values: Set<String>) {
        if (values.isEmpty()) remove(key) else this[key] = values
    }
}
