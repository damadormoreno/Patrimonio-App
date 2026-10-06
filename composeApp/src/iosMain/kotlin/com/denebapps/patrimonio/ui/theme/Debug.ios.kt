package com.denebapps.patrimonio.ui.theme

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
actual fun isDebug(): Boolean = Platform.isDebugBinary
