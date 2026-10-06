package com.denebapps.patrimonio.domain.repository

/**
 * Whole-dataset backup as a versioned JSON document: assets, liabilities, account groups and
 * members, net-worth history and savings goals with their ledgers. FX rates (re-fetchable cache)
 * and DataStore preferences are not part of a backup.
 */
interface BackupRepository {
    /** Serializes a consistent snapshot of every financial table. */
    suspend fun exportJson(): String

    /**
     * REPLACES every financial table with the contents of [json], atomically. The document is
     * fully validated before the database is touched, so an invalid file leaves the current data
     * intact.
     *
     * @throws InvalidBackupException if [json] is not a valid backup this app version can read.
     */
    suspend fun importJson(json: String)
}

/** [message] is user-facing (Spanish) and says what is wrong with the file. */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
