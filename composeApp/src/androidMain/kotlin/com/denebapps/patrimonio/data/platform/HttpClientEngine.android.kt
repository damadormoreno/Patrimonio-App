package com.denebapps.patrimonio.data.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android

actual fun platformHttpClientEngine(): HttpClientEngine = Android.create()
