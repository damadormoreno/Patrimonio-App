package com.denebapps.patrimonio.data.auth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class DataStoreAuthSessionStoreTest {
    private fun tempFilePath(): String = File.createTempFile("patrimonio_auth", ".preferences_pb").absolutePath

    @Test
    fun `a saved session survives a new store on the same file and clearing removes it`() = runTest {
        val path = tempFilePath()
        val session = AuthSession("uid-1", "ana@example.com", "id-1", "refresh-1", expiresAtEpochMs = 42L)
        val store = DataStoreAuthSessionStore(path)
        assertNull(store.observe().first())

        store.save(session)
        assertEquals(session, store.observe().first())

        store.clear()
        assertNull(store.observe().first())
    }
}
