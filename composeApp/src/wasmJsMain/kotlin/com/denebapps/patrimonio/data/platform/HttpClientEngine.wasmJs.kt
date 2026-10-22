package com.denebapps.patrimonio.data.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

actual fun platformHttpClientEngine(): HttpClientEngine = Js.create()
