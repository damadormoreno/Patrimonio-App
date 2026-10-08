package com.denebapps.patrimonio.data.lock

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

/** The PIN as a PBKDF2 hash, never the PIN itself. */
data class PinHash(val hash: String, val salt: String, val iterations: Int)

/** Everything the lock keeps. [retryAtEpochMs]: wrong PINs are not checked before then. */
data class StoredLock(
    val pin: PinHash? = null,
    val biometrics: Boolean = false,
    val autoLockDelay: AutoLockDelay = AutoLockDelay.ONE_MINUTE,
    val failedAttempts: Int = 0,
    val retryAtEpochMs: Long? = null,
)

interface LockStore {
    suspend fun get(): StoredLock

    suspend fun update(transform: (StoredLock) -> StoredLock): StoredLock
}

private val HASH_KEY = stringPreferencesKey("pin_hash")
private val SALT_KEY = stringPreferencesKey("pin_salt")
private val ITERATIONS_KEY = intPreferencesKey("pin_iterations")
private val BIOMETRICS_KEY = booleanPreferencesKey("biometrics")
private val DELAY_KEY = stringPreferencesKey("auto_lock_delay")
private val FAILED_KEY = intPreferencesKey("failed_attempts")
private val RETRY_AT_KEY = longPreferencesKey("retry_at_epoch_ms")

/**
 * Own DataStore file. It goes into Android's auto backup with the data it protects: a restored phone opens
 * with the same PIN.
 */
class DataStoreLockStore(filePath: String) : LockStore {
    private val dataStore = PreferenceDataStoreFactory.createWithPath(produceFile = { filePath.toPath() })

    override suspend fun get(): StoredLock = dataStore.data.first().toStoredLock()

    override suspend fun update(transform: (StoredLock) -> StoredLock): StoredLock {
        var result = StoredLock()
        dataStore.edit { prefs ->
            result = transform(prefs.toStoredLock())
            prefs.clear()
            result.pin?.let {
                prefs[HASH_KEY] = it.hash
                prefs[SALT_KEY] = it.salt
                prefs[ITERATIONS_KEY] = it.iterations
            }
            prefs[BIOMETRICS_KEY] = result.biometrics
            prefs[DELAY_KEY] = result.autoLockDelay.name
            prefs[FAILED_KEY] = result.failedAttempts
            result.retryAtEpochMs?.let { prefs[RETRY_AT_KEY] = it }
        }
        return result
    }

    private fun Preferences.toStoredLock(): StoredLock {
        val hash = this[HASH_KEY]
        val salt = this[SALT_KEY]
        val iterations = this[ITERATIONS_KEY]
        return StoredLock(
            pin = if (hash != null && salt != null && iterations != null) PinHash(hash, salt, iterations) else null,
            biometrics = this[BIOMETRICS_KEY] ?: false,
            autoLockDelay = AutoLockDelay.entries.firstOrNull { it.name == this[DELAY_KEY] }
                ?: AutoLockDelay.ONE_MINUTE,
            failedAttempts = this[FAILED_KEY] ?: 0,
            retryAtEpochMs = this[RETRY_AT_KEY],
        )
    }
}
