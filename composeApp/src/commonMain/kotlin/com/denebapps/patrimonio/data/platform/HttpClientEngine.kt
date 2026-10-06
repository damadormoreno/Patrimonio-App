package com.denebapps.patrimonio.data.platform

import io.ktor.client.engine.HttpClientEngine

/**
 * Platform Ktor client engine (design.md "Koin Modules & Platform Divergence": Android
 * `ktor-client-android`, iOS `ktor-client-darwin`). Test builds never call this — `FxModuleTest`
 * and `FxRefreshPolicyTest` construct their `HttpClient` directly over Ktor's `MockEngine`.
 */
expect fun platformHttpClientEngine(): HttpClientEngine
