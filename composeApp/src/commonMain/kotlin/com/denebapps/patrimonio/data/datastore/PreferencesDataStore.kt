package com.denebapps.patrimonio.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope

/**
 * The preferences [DataStore] kept at [path]: a file on the phone, an entry of the browser's local storage on the web.
 * Never create two for the same [path]. [scope] runs its IO; null uses DataStore's own.
 */
expect fun preferencesDataStore(path: String, scope: CoroutineScope? = null): DataStore<Preferences>
