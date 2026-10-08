package com.denebapps.patrimonio.data.cloud

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/**
 * What this device keeps about the cloud copy: the [CloudLink] and the account's unwrapped data key, so the
 * passphrase is asked only when signing in. [clear] (on sign-out) forgets both.
 */
interface CloudLinkStore {
    suspend fun get(): CloudLink?

    suspend fun save(link: CloudLink)

    /** The data key of [uid], if this device unlocked or created it. */
    suspend fun key(uid: String): CloudKey?

    suspend fun saveKey(uid: String, key: CloudKey)

    suspend fun clear()
}

private val UID_KEY = stringPreferencesKey("uid")
private val REVISION_KEY = stringPreferencesKey("revision")
private val SAVED_AT_KEY = longPreferencesKey("saved_at_epoch_ms")
private val KEY_UID_KEY = stringPreferencesKey("key_uid")
private val KEY_SECRET_KEY = stringPreferencesKey("key_secret")
private val KEY_ID_KEY = stringPreferencesKey("key_id")
private val KEY_SALT_KEY = stringPreferencesKey("key_salt")
private val KEY_ITERATIONS_KEY = intPreferencesKey("key_iterations")
private val KEY_WRAPPED_KEY = stringPreferencesKey("key_wrapped")

/**
 * Own DataStore file, excluded from Android's auto backup: a restored device must check the cloud again and
 * ask for the passphrase. The data key is as protected as the app's database, which holds the same data.
 */
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

    override suspend fun key(uid: String): CloudKey? {
        val prefs = dataStore.data.first()
        if (prefs[KEY_UID_KEY] != uid) return null
        return CloudKey(
            secret = prefs[KEY_SECRET_KEY] ?: return null,
            wrapped = WrappedDataKey(
                keyId = prefs[KEY_ID_KEY] ?: return null,
                salt = prefs[KEY_SALT_KEY] ?: return null,
                iterations = prefs[KEY_ITERATIONS_KEY] ?: return null,
                wrapped = prefs[KEY_WRAPPED_KEY] ?: return null,
            ),
        )
    }

    override suspend fun saveKey(uid: String, key: CloudKey) {
        dataStore.edit { prefs ->
            prefs[KEY_UID_KEY] = uid
            prefs[KEY_SECRET_KEY] = key.secret
            prefs[KEY_ID_KEY] = key.wrapped.keyId
            prefs[KEY_SALT_KEY] = key.wrapped.salt
            prefs[KEY_ITERATIONS_KEY] = key.wrapped.iterations
            prefs[KEY_WRAPPED_KEY] = key.wrapped.wrapped
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
