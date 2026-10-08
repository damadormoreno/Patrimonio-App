package com.denebapps.patrimonio.data.cloud

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

/**
 * The last copy this device uploaded: to which account, with which [revision] and when. While the cloud copy
 * still has that revision nobody else has changed it, so this device can keep uploading without asking.
 * Signing out clears it: the data may change while signed out, so signing in again always asks first.
 */
data class CloudLink(val uid: String, val revision: String, val savedAtEpochMs: Long)

interface CloudLinkStore {
    suspend fun get(): CloudLink?

    suspend fun save(link: CloudLink)

    suspend fun clear()
}

private val UID_KEY = stringPreferencesKey("uid")
private val REVISION_KEY = stringPreferencesKey("revision")
private val SAVED_AT_KEY = longPreferencesKey("saved_at_epoch_ms")

/** Own DataStore file, excluded from Android's auto backup: a restored device must check the cloud again. */
class DataStoreCloudLinkStore(filePath: String) : CloudLinkStore {
    private val dataStore = PreferenceDataStoreFactory.createWithPath(produceFile = { filePath.toPath() })

    override suspend fun get(): CloudLink? {
        val prefs = dataStore.data.first()
        return CloudLink(
            uid = prefs[UID_KEY] ?: return null,
            revision = prefs[REVISION_KEY] ?: return null,
            savedAtEpochMs = prefs[SAVED_AT_KEY] ?: return null,
        )
    }

    override suspend fun save(link: CloudLink) {
        dataStore.edit { prefs ->
            prefs[UID_KEY] = link.uid
            prefs[REVISION_KEY] = link.revision
            prefs[SAVED_AT_KEY] = link.savedAtEpochMs
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
