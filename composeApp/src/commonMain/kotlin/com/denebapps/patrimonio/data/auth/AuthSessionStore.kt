package com.denebapps.patrimonio.data.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.denebapps.patrimonio.domain.repository.AccountProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/** A signed-in Firebase session: who, how they signed in, plus the tokens that keep it alive. */
data class AuthSession(
    val uid: String,
    val email: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val provider: AccountProvider = AccountProvider.PASSWORD,
)

interface AuthSessionStore {
    fun observe(): Flow<AuthSession?>

    suspend fun save(session: AuthSession)

    suspend fun clear()
}

private val UID_KEY = stringPreferencesKey("uid")
private val EMAIL_KEY = stringPreferencesKey("email")
private val ID_TOKEN_KEY = stringPreferencesKey("id_token")
private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
private val EXPIRES_AT_KEY = longPreferencesKey("expires_at_epoch_ms")
private val PROVIDER_KEY = stringPreferencesKey("provider")

/** Keeps the session in its own DataStore file, which Android's auto backup excludes. */
class DataStoreAuthSessionStore(filePath: String) : AuthSessionStore {
    private val dataStore = PreferenceDataStoreFactory.createWithPath(produceFile = { filePath.toPath() })

    override fun observe(): Flow<AuthSession?> = dataStore.data.map { prefs ->
        val uid = prefs[UID_KEY] ?: return@map null
        AuthSession(
            uid = uid,
            email = prefs[EMAIL_KEY].orEmpty(),
            idToken = prefs[ID_TOKEN_KEY].orEmpty(),
            refreshToken = prefs[REFRESH_TOKEN_KEY] ?: return@map null,
            expiresAtEpochMs = prefs[EXPIRES_AT_KEY] ?: 0L,
            // Sessions from before Google sign-in are email and password ones.
            provider = AccountProvider.entries.find { it.name == prefs[PROVIDER_KEY] } ?: AccountProvider.PASSWORD,
        )
    }

    override suspend fun save(session: AuthSession) {
        dataStore.edit { prefs ->
            prefs[UID_KEY] = session.uid
            prefs[EMAIL_KEY] = session.email
            prefs[ID_TOKEN_KEY] = session.idToken
            prefs[REFRESH_TOKEN_KEY] = session.refreshToken
            prefs[EXPIRES_AT_KEY] = session.expiresAtEpochMs
            prefs[PROVIDER_KEY] = session.provider.name
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
