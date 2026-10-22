package com.denebapps.patrimonio.di

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.denebapps.patrimonio.data.db.AppDatabase
import com.denebapps.patrimonio.data.db.createAppDatabaseBuilder
import com.denebapps.patrimonio.data.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module
import org.w3c.dom.Worker

/** Room on SQLite WASM: the database lives in the Origin Private File System and every statement runs in the
 *  worker from `web-worker/`, off the page's thread. */
actual val dbModule: org.koin.core.module.Module = module {
    single<PlatformContext> { PlatformContext(value = null) }
    single<AppDatabase> {
        createAppDatabaseBuilder(get<PlatformContext>())
            .setDriver(WebWorkerSQLiteDriver(sqliteWorker()))
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
    }
    bindDatabaseDependents()
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun sqliteWorker(): Worker =
    js("""new Worker(new URL("patrimonio-sqlite-worker/worker.js", import.meta.url))""")
