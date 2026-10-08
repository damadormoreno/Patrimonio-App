package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

/** Where the automatic cloud backup of the signed-in account stands. */
sealed interface CloudBackupState {
    /** Nobody is signed in, so nothing goes to the cloud. */
    data object SignedOut : CloudBackupState

    /** Comparing the cloud copy with this device before uploading anything. */
    data object Checking : CloudBackupState

    /** The check (or the choice in [Conflict]) failed; it is retried automatically and by [CloudBackup.retry]. */
    data class CheckFailed(val error: CloudBackupError) : CloudBackupState

    /** The cloud holds a copy this device did not write, saved at [cloudSavedAt]. Nothing is uploaded until the
     *  user picks [CloudBackup.useCloudCopy] or [CloudBackup.keepLocalData]. */
    data class Conflict(val cloudSavedAt: Instant) : CloudBackupState

    /** Applying the choice made in [Conflict]. */
    data object Resolving : CloudBackupState

    /** Every change is uploaded a few seconds later. [lastBackupAt] is the last copy this device saved;
     *  [failure] says why the latest upload failed (it is retried). */
    data class Active(
        val lastBackupAt: Instant,
        val backingUp: Boolean = false,
        val failure: CloudBackupError? = null,
    ) : CloudBackupState
}

/**
 * Automatic backup of the whole data set (the backup JSON) to the signed-in account. One copy per account:
 * each upload replaces the previous one. Before the first upload of a session, and before every upload,
 * the cloud copy is checked: if another device changed it, the user decides which data wins.
 */
interface CloudBackup {
    val state: StateFlow<CloudBackupState>

    /** Follows the signed-in account and backs up its changes. Runs while the app is open. */
    suspend fun run()

    fun retry()

    /** In [CloudBackupState.Conflict]: replaces the data on this device with the cloud copy. */
    fun useCloudCopy()

    /** In [CloudBackupState.Conflict]: replaces the cloud copy with the data on this device. */
    fun keepLocalData()

    /** In [CloudBackupState.Active]: uploads now instead of waiting for the next change. */
    fun backUpNow()

    /**
     * Deletes the cloud copy and then the account, after checking [password] ([AuthRepository.deleteAccount]).
     * The data on this device stays.
     *
     * @throws AuthException when the password is wrong or the account cannot be reached.
     * @throws CloudBackupException when the cloud copy cannot be deleted; the account is kept.
     */
    suspend fun deleteAccount(password: String)
}

enum class CloudBackupError {
    NETWORK,

    /** Firestore refused the request: the database or its security rules are not set up. */
    NOT_AVAILABLE,

    /** The data set is bigger than a Firestore document can hold. */
    TOO_LARGE,

    /** The cloud copy could not be restored (written by a newer app version, or damaged). */
    INVALID_BACKUP,
    SESSION_EXPIRED,
    UNKNOWN,
}

class CloudBackupException(val error: CloudBackupError, cause: Throwable? = null) :
    Exception("Cloud backup failed: $error", cause)
