package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.FxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatform

/**
 * Starts Koin with [dbModule] + [dataModule] + [fxModule] + [authModule]. Idempotent double-start guard
 * (design.md: "Koin init") — safe to call more than once (e.g. iOS `MainViewController` on every
 * recomposition preview build, or hot reload).
 *
 * Also fires the FX refresh-on-app-start (design.md FX Pipeline: "on app start, background scope,
 * never blocks calcs") as fire-and-forget on the shared app [CoroutineScope] — wrapped in its own
 * `runCatching` on top of [FxRepository.refreshIfStale]'s internal one, so a failure here can
 * never crash or block startup (platform wiring, exempt from RED-first per Slice 2's precedent for
 * `PatrimonioApplication`/`MainViewController`).
 */
fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    if (KoinPlatform.getKoinOrNull() != null) return
    val koin = startKoin {
        appDeclaration()
        modules(dbModule, dataModule, fxModule, authModule, presentationModule)
    }.koin
    koin.get<CoroutineScope>().launch {
        runCatching { koin.get<FxRepository>().refreshIfStale() }
            .onFailure { error -> AppLogger.error("InitKoin", "startup FX refresh failed", error) }
    }
}
