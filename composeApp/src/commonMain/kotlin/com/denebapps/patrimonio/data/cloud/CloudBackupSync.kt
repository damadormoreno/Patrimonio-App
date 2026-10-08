package com.denebapps.patrimonio.data.cloud

import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.BackupRepository
import com.denebapps.patrimonio.domain.repository.CloudBackup
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [CloudBackup] for the signed-in account. For each session:
 *
 * 1. **Check**: read the cloud copy's revision. No copy: upload this device's data. Same revision this device
 *    last wrote ([CloudLinkStore], cleared on sign-out): nothing changed elsewhere. Anything else:
 *    [CloudBackupState.Conflict] until the user picks a side.
 * 2. **Back up**: every change (debounced) uploads the whole backup JSON under a new revision. Each upload
 *    first checks that the cloud still has this device's revision; if another device wrote meanwhile, it goes
 *    back to step 1 instead of overwriting that copy. Data emptied on this device (Borrar todos los datos) is
 *    never uploaded over a copy that has data without asking either.
 *
 * Everything uploaded is encrypted ([BackupCrypto]). The first time, the user creates a passphrase; a device
 * without the data key asks for it to open the cloud copy, or lets the user start over.
 *
 * Failures are retried with a growing delay, or straight away with [retry] / [backUpNow].
 */
@OptIn(FlowPreview::class, ExperimentalUuidApi::class)
class CloudBackupSync(
    private val authRepository: AuthRepository,
    private val backupRepository: BackupRepository,
    private val remote: CloudBackupRemote,
    private val linkStore: CloudLinkStore,
    private val localChanges: LocalDataChanges,
    private val clock: Clock,
    private val crypto: BackupCrypto = BackupCrypto(),
    private val newRevision: () -> String = { Uuid.random().toString() },
) : CloudBackup {
    private val _state = MutableStateFlow<CloudBackupState>(CloudBackupState.SignedOut)
    override val state: StateFlow<CloudBackupState> = _state.asStateFlow()

    private val choices = Channel<Choice>(Channel.CONFLATED)
    private val passphrases = Channel<PassphraseAction>(Channel.CONFLATED)
    private val uploadRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One upload at a time, and none while the account is being deleted. */
    private val uploadMutex = Mutex()

    override suspend fun run() {
        authRepository.observeUser().collectLatest { user ->
            if (user == null) {
                _state.value = CloudBackupState.SignedOut
                forgetLastUpload()
                return@collectLatest
            }
            var askFirst = false
            while (true) {
                val inSync = check(user.uid, askFirst)
                askFirst = backUpChanges(user.uid, skipFirst = inSync)
            }
        }
    }

    override fun retry() {
        if (_state.value is CloudBackupState.CheckFailed) choices.trySend(Choice.RETRY)
    }

    override fun useCloudCopy() {
        if (_state.value is CloudBackupState.Conflict) choices.trySend(Choice.USE_CLOUD)
    }

    override fun keepLocalData() {
        if (_state.value is CloudBackupState.Conflict) choices.trySend(Choice.KEEP_LOCAL)
    }

    override fun backUpNow() {
        if (_state.value is CloudBackupState.Active) uploadRequests.tryEmit(Unit)
    }

    override fun submitPassphrase(passphrase: String) {
        val current = _state.value
        if (current is CloudBackupState.NeedsPassphrase && !current.working) {
            passphrases.trySend(PassphraseAction.Submit(passphrase))
        }
    }

    override fun startOver(passphrase: String) {
        val current = _state.value
        if (current is CloudBackupState.NeedsPassphrase && current.unlock && !current.working) {
            passphrases.trySend(PassphraseAction.StartOver(passphrase))
        }
    }

    override suspend fun changePassphrase(passphrase: String) {
        if (_state.value !is CloudBackupState.Active) return
        val uid = authRepository.observeUser().first()?.uid ?: return
        uploadMutex.withLock {
            val key = linkStore.key(uid) ?: return
            linkStore.saveKey(uid, crypto.rewrap(key, passphrase))
        }
        // The next copy carries the key under the new passphrase.
        uploadRequests.tryEmit(Unit)
    }

    override suspend fun deleteAccount(password: String) {
        uploadMutex.withLock {
            authRepository.deleteAccount(password) {
                val uid = authRepository.observeUser().first()?.uid ?: throw AuthException(AuthError.NOT_SIGNED_IN)
                remote.delete(uid, authRepository.idToken())
            }
        }
    }

    /**
     * Step 1. Returns true when the cloud copy now matches this device's data, so no upload is due. With
     * [askFirst] an existing cloud copy is always a conflict.
     */
    private suspend fun check(uid: String, askFirst: Boolean): Boolean {
        var retryDelay = FIRST_RETRY_DELAY
        while (true) {
            _state.value = CloudBackupState.Checking
            try {
                val cloud = remote.fetch(uid, authRepository.idToken(), withData = false)
                val cloudKey = cloud?.key
                if (cloudKey != null && linkStore.key(uid)?.keyId != cloudKey.keyId) {
                    if (unlock(uid, cloudKey)) return true
                } else if (linkStore.key(uid) == null) {
                    createKey(uid)
                }
                val link = linkStore.get()
                return when {
                    cloud == null -> {
                        upload(uid, checkCloud = false)
                        true
                    }
                    !askFirst && link?.uid == uid && link.revision == cloud.revision -> {
                        _state.value = CloudBackupState.Active(Instant.fromEpochMilliseconds(link.savedAtEpochMs))
                        false
                    }
                    else -> resolveConflict(uid, cloud.savedAt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Cloud backup check failed", e)
                _state.value = CloudBackupState.CheckFailed(cloudBackupErrorFor(e))
                withTimeoutOrNull(retryDelay) { awaitChoice(Choice.RETRY) }
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    private suspend fun resolveConflict(uid: String, cloudSavedAt: Instant): Boolean {
        // A choice left over from an earlier conflict must not answer this one.
        while (choices.tryReceive().isSuccess) Unit
        _state.value = CloudBackupState.Conflict(cloudSavedAt)
        val choice = awaitChoice(Choice.USE_CLOUD, Choice.KEEP_LOCAL)
        _state.value = CloudBackupState.Resolving
        if (choice == Choice.KEEP_LOCAL) {
            upload(uid, checkCloud = false)
            return true
        }
        val cloud = remote.fetch(uid, authRepository.idToken(), withData = true)
        if (cloud?.data == null) {
            // The copy was deleted meanwhile: this device's data becomes the copy.
            upload(uid, checkCloud = false)
            return true
        }
        backupRepository.importJson(readable(uid, cloud, cloud.data))
        linkStore.save(CloudLink(uid, cloud.revision, cloud.savedAt.toEpochMilliseconds()))
        _state.value = CloudBackupState.Active(cloud.savedAt)
        return true
    }

    /**
     * Step 2. Returns when the cloud copy must not be overwritten without asking, so [check] runs again: true
     * when this device's data was emptied, false when another device changed the cloud copy.
     */
    private suspend fun backUpChanges(uid: String, skipFirst: Boolean): Boolean {
        val changes = localChanges.observe().let { if (skipFirst) it.drop(1) else it }
        try {
            merge(changes.debounce(UPLOAD_DEBOUNCE), uploadRequests).collectLatest { uploadWithRetry(uid) }
        } catch (e: StopBackingUp) {
            return e.localDataEmptied
        }
        error("The data changes never complete")
    }

    private suspend fun uploadWithRetry(uid: String) {
        var retryDelay = FIRST_RETRY_DELAY
        while (true) {
            val active = _state.value as? CloudBackupState.Active ?: return
            _state.value = active.copy(backingUp = true)
            try {
                upload(uid, checkCloud = true)
                return
            } catch (e: CancellationException) {
                _state.value = active
                throw e
            } catch (e: StopBackingUp) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Cloud backup upload failed", e)
                _state.value = active.copy(failure = cloudBackupErrorFor(e))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    /** The backup JSON in [cloud]: decrypted, or as is in a copy from before encryption. */
    private suspend fun readable(uid: String, cloud: CloudBackupDocument, data: String): String {
        val cloudKey = cloud.key ?: return data
        val key = requireKey(uid)
        if (key.keyId != cloudKey.keyId) throw CloudBackupException(CloudBackupError.INVALID_BACKUP)
        return try {
            crypto.decrypt(key, uid, data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CloudBackupException(CloudBackupError.INVALID_BACKUP, e)
        }
    }

    /** No data key on this device and no encrypted copy in the cloud: the user creates the passphrase. */
    private suspend fun createKey(uid: String) {
        while (passphrases.tryReceive().isSuccess) Unit
        _state.value = CloudBackupState.NeedsPassphrase(unlock = false)
        val action = passphrases.receive()
        _state.value = CloudBackupState.NeedsPassphrase(unlock = false, working = true)
        linkStore.saveKey(uid, crypto.createKey(newKeyId(), action.passphrase))
    }

    /**
     * The cloud copy is encrypted with a key this device does not hold: the passphrase opens it, or the user
     * starts over with a new one, replacing the cloud copy with this device's data. Returns true after
     * starting over, when the cloud copy already matches this device.
     */
    private suspend fun unlock(uid: String, wrapped: WrappedDataKey): Boolean {
        while (passphrases.tryReceive().isSuccess) Unit
        _state.value = CloudBackupState.NeedsPassphrase(unlock = true)
        while (true) {
            val action = passphrases.receive()
            _state.value = CloudBackupState.NeedsPassphrase(unlock = true, working = true)
            when (action) {
                is PassphraseAction.Submit -> try {
                    linkStore.saveKey(uid, crypto.unwrap(wrapped, action.passphrase))
                    return false
                } catch (e: WrongPassphraseException) {
                    _state.value = CloudBackupState.NeedsPassphrase(unlock = true, wrongPassphrase = true)
                }
                is PassphraseAction.StartOver -> {
                    linkStore.saveKey(uid, crypto.createKey(newKeyId(), action.passphrase))
                    upload(uid, checkCloud = false)
                    return true
                }
            }
        }
    }

    private suspend fun requireKey(uid: String): CloudKey =
        linkStore.key(uid) ?: throw IllegalStateException("No data key on this device")

    private suspend fun upload(uid: String, checkCloud: Boolean) = uploadMutex.withLock {
        val idToken = authRepository.idToken()
        val key = requireKey(uid)
        val backup = Json.parseToJsonElement(backupRepository.exportJson()).jsonObject
        val records = recordCount(backup)
        if (checkCloud) {
            val cloud = remote.fetch(uid, idToken, withData = false)
            val link = linkStore.get()
            if (cloud != null && (link?.uid != uid || link.revision != cloud.revision)) {
                throw StopBackingUp(localDataEmptied = false)
            }
            // A copy from before the record count existed (null) is taken to have data.
            if (cloud != null && records == 0 && cloud.records != 0) throw StopBackingUp(localDataEmptied = true)
        }
        val savedAt = clock.now()
        val revision = newRevision()
        // The backup file is pretty-printed for people; the cloud copy does not need the whitespace.
        val data = crypto.encrypt(key, uid, backup.toString())
        remote.upload(uid, idToken, CloudBackupDocument(revision, savedAt, records, key.wrapped, data))
        linkStore.save(CloudLink(uid, revision, savedAt.toEpochMilliseconds()))
        _state.value = CloudBackupState.Active(savedAt)
    }

    private suspend fun awaitChoice(vararg wanted: Choice): Choice {
        while (true) {
            val choice = choices.receive()
            if (choice in wanted) return choice
        }
    }

    /** After signing out the data may change, so the next sign-in compares with the cloud copy again. */
    private suspend fun forgetLastUpload() {
        try {
            linkStore.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "Could not forget the last cloud upload", e)
        }
    }

    private fun newKeyId() = Uuid.random().toString()

    private enum class Choice { RETRY, USE_CLOUD, KEEP_LOCAL }

    private sealed interface PassphraseAction {
        val passphrase: String

        class Submit(override val passphrase: String) : PassphraseAction

        class StartOver(override val passphrase: String) : PassphraseAction
    }

    /** Another device changed the cloud copy, or this device's data was emptied ([localDataEmptied]). */
    private class StopBackingUp(val localDataEmptied: Boolean) : Exception("The cloud copy needs the user's choice")

    private companion object {
        const val TAG = "CloudBackupSync"
        val UPLOAD_DEBOUNCE: Duration = 5.seconds
        val FIRST_RETRY_DELAY: Duration = 30.seconds
        val MAX_RETRY_DELAY: Duration = 15.minutes
    }
}

private val RECORD_KEYS = listOf("assets", "liabilities", "accountGroups", "savingsGoals", "subscriptions")

/** Accounts, debts, groups, goals and subscriptions in a backup: zero means there is nothing to protect. */
internal fun recordCount(backup: JsonObject): Int = RECORD_KEYS.sumOf { (backup[it] as? JsonArray)?.size ?: 0 }

internal fun cloudBackupErrorFor(error: Exception): CloudBackupError = when (error) {
    is CloudBackupException -> error.error
    is InvalidBackupException -> CloudBackupError.INVALID_BACKUP
    is AuthException -> when (error.error) {
        AuthError.NETWORK -> CloudBackupError.NETWORK
        AuthError.NOT_SIGNED_IN, AuthError.SESSION_EXPIRED -> CloudBackupError.SESSION_EXPIRED
        else -> CloudBackupError.UNKNOWN
    }
    else -> CloudBackupError.UNKNOWN
}
