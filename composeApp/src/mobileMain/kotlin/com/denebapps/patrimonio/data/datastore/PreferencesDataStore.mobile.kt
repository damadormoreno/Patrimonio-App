package com.denebapps.patrimonio.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import okio.Path.Companion.toPath

actual fun preferencesDataStore(path: String, scope: CoroutineScope?): DataStore<Preferences> = if (scope == null) {
    PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
} else {
    PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path.toPath() })
}
