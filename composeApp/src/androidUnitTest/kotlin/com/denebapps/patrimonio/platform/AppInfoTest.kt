package com.denebapps.patrimonio.platform

import com.denebapps.patrimonio.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun `Android app version comes from BuildConfig`() {
        assertEquals(BuildConfig.VERSION_NAME, appVersionName())
    }
}
