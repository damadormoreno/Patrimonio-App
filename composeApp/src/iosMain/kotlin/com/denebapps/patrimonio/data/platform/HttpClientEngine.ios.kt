package com.denebapps.patrimonio.data.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpClientEngine(): HttpClientEngine = Darwin.create()
