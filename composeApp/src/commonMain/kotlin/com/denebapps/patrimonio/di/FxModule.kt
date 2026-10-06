package com.denebapps.patrimonio.di

import com.denebapps.patrimonio.data.fx.FrankfurterApi
import com.denebapps.patrimonio.data.fx.FxRepositoryImpl
import com.denebapps.patrimonio.data.platform.platformHttpClientEngine
import com.denebapps.patrimonio.domain.repository.FxRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Platform-independent: [HttpClient] (built with the platform engine expect/actual),
 * [FrankfurterApi], and [FxRepositoryImpl] — unlike `dbModule` this needs no platform-specific
 * Koin binding, so it stays a single `val`, not `expect`/`actual`. Depends on `dbModule`'s
 * `FxRateDao`/`SeedingGate` bindings and `dataModule`'s `Clock` binding.
 */
val fxModule = module {
    single<HttpClient> {
        HttpClient(platformHttpClientEngine()) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    single { FrankfurterApi(get()) }
    single<FxRepository> { FxRepositoryImpl(get(), get(), get(), get()) }
}
