package com.denebapps.patrimonio.platform

import platform.Foundation.NSBundle

actual fun appVersionName(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.0.0"
