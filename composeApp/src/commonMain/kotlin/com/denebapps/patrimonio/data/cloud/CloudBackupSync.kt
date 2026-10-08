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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [CloudBackup] for the signed-in account. For each session:
 *
 * 1. **Check**: read the cloud copy's revision. No copy: upload this device's data. Same revision this device
 *    last wrote ([CloudLinkStore]): nothing changed elsewhere. Anything else: [CloudBackupState.Conflict] until
 *    the user picks a side.
 * 2. **Back up**: every change (debounced) uploads the whole backup JSON under a new revision. Each upload
 *    first checks that the cloud still has this device's revision; if another device wrote meanwhile, it goes
 *    back to step 1 instead of overwriting that copy.
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
    private val newRevision: () -> String = { Uuid.random().toString() },
) : CloudBackup {
    private val _state = MutableStateFlow<CloudBackupState>(CloudBackupState.SignedOut)
    override val state: StateFlow<CloudBackupState> = _state.asStateFlow()

    private val choices = Channel<Choice>(Channel.CONFLATED)
    private val uploadRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One upload at a time, and none while the account is being deleted. */
    private val uploadMutex = Mutex()

    override suspend fun run() {
        authRepository.observeUser().collectLatest { user ->
            if (user == null) {
                _state.value = CloudBackupState.SignedOut
                return@collectLatest
            }
            while (true) {
                val inSync = check(user.uid)
                backUpChanges(user.uid, skipFirst = inSync)
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

    override suspend fun deleteAccount(password: String) {
        uploadMutex.withLock {
            authRepository.deleteAccount(password) {
                val uid = authRepository.observeUser().first()?.uid ?: throw AuthException(AuthError.NOT_SIGNED_IN)
                remote.delete(uid, authRepository.idToken())
            }
        }
    }

    /** Step 1. Returns true when the cloud copy now matches this device's data, so no upload is due. */
    private suspend fun check(uid: String): Boolean {
        var retryDelay = FIRST_RETRY_DELAY
        while (true) {
            _state.value = CloudBackupState.Checking
            try {
                val cloud = remote.fetch(uid, authRepository.idToken(), withData = false)
                val link = linkStore.get()
                return when {
                    cloud == null -> {
                        upload(uid, checkCloud = false)
                        true
                    }
                    link?.uid == uid && link.revision == cloud.revision -> {
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
        if (cloud?.json == null) {
            // The copy was deleted meanwhile: this device's data becomes the copy.
            upload(uid, checkCloud = false)
            return true
        }
        backupRepository.importJson(cloud.json)
        linkStore.save(CloudLink(uid, cloud.revision, cloud.savedAt.toEpochMilliseconds()))
        _state.value = CloudBackupState.Active(cloud.savedAt)
        return true
    }

    /** Step 2. Returns when another device changed the cloud copy, so [check] runs again. */
    private suspend fun backUpChanges(uid: String, skipFirst: Boolean) {
        val changes = localChanges.observe().let { if (skipFirst) it.drop(1) else it }
        try {
            merge(changes.debounce(UPLOAD_DEBOUNCE), uploadRequests).collectLatest { uploadWithRetry(uid) }
        } catch (e: CloudChangedElsewhere) {
            // Back to the check, which lets the user choose between both copies.
        }
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
            } catch (e: CloudChangedElsewhere) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Cloud backup upload failed", e)
                _state.value = active.copy(failure = cloudBackupErrorFor(e))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
            }
        }
    }

    private suspend fun upload(uid: String, checkCloud: Boolean) = uploadMutex.withLock {
        val idToken = authRepository.idToken()
        if (checkCloud) {
            val cloud = remote.fetch(uid, idToken, withData = false)
            val link = linkStore.get()
            if (cloud != null && (link?.uid != uid || link.revision != cloud.revision)) throw CloudChangedElsewhere()
        }
        val json = compact(backupRepository.exportJson())
        val savedAt = clock.now()
        val revision = newRevision()
        remote.upload(uid, idToken, CloudBackupDocument(revision, savedAt, json))
        linkStore.save(CloudLink(uid, revision, savedAt.toEpochMilliseconds()))
        _state.value = CloudBackupState.Active(savedAt)
    }

    private suspend fun awaitChoice(vararg wanted: Choice): Choice {
        while (true) {
            val choice = choices.receive()
            if (choice in wanted) return choice
        }
    }

    private enum class Choice { RETRY, USE_CLOUD, KEEP_LOCAL }

    private class CloudChangedElsewhere : Exception("The cloud copy was changed by another device")

    private companion object {
        const val TAG = "CloudBackupSync"
        val UPLOAD_DEBOUNCE: Duration = 5.seconds
        val FIRST_RETRY_DELAY: Duration = 30.seconds
        val MAX_RETRY_DELAY: Duration = 15.minutes
    }
}

/** The backup file is pretty-printed for people; the cloud copy does not need the whitespace. */
private fun compact(json: String): String = Json.parseToJsonElement(json).toString()

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
