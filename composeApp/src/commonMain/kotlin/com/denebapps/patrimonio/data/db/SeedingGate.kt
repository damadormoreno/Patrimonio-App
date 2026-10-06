package com.denebapps.patrimonio.data.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Suspend seeding gate: [DatabaseInitializer.ensureSeeded] starts running the moment this is
 * constructed (Koin creates it eagerly as part of `dbModule`), and every repository awaits
 * [await] before its first query — so no repository can observe an unseeded DB.
 */
class SeedingGate(scope: CoroutineScope, initializer: DatabaseInitializer) {
    private val deferred: Deferred<Unit> = scope.async { initializer.ensureSeeded() }

    suspend fun await() = deferred.await()
}
