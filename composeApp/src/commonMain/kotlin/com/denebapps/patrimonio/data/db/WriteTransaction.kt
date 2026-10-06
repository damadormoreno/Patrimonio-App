package com.denebapps.patrimonio.data.db

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Multi-DAO write transaction helper. NOT `appDatabase.withTransaction {}` — confirmed at apply
 * time (design.md Open Question c) that the `withTransaction` extension throws
 * `IllegalStateException: Cannot return a SupportSQLiteOpenHelper since no
 * SupportSQLiteOpenHelper.Factory was configured with Room` when the database is built purely
 * with `.setDriver()` (the new Room-KMP driver API, no legacy `SupportSQLiteOpenHelper`
 * configured) — `withTransaction` is a legacy-API wrapper incompatible with that setup. Uses the
 * documented fallback (Decision #5): `useWriterConnection { it.immediateTransaction { ... } }`.
 */
suspend fun <R> RoomDatabase.writeTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
