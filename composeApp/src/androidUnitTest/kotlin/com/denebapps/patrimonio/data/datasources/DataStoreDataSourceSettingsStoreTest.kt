package com.denebapps.patrimonio.data.datasources

import com.denebapps.patrimonio.data.datastore.DATA_SOURCES_FILE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DataStoreDataSourceSettingsStoreTest {
    private fun tempFilePath(): String = File.createTempFile("patrimonio_datasources", ".preferences_pb").absolutePath

    private val day = LocalDate(2026, 10, 14)

    /** A DataStore allows one instance per file: closing the first one's scope frees the file for the second. */
    private suspend fun reopen(path: String, first: CoroutineScope): DataStoreDataSourceSettingsStore {
        val job = first.coroutineContext.job
        job.cancelAndJoin()
        return DataStoreDataSourceSettingsStore(path)
    }

    @Test
    fun `a new store has no key and every switch off`() = runTest {
        val store = DataStoreDataSourceSettingsStore(tempFilePath())

        assertEquals(DataSourceSettings(), store.current())
        assertNull(store.current().apiKey)
        assertFalse(store.current().eodhdPrimary)
        assertFalse(store.current().invalidKey)
        assertNull(store.current().quotaExhaustedOn)
    }

    @Test
    fun `the key and the primary switch survive a new store on the same file`() = runTest {
        val path = tempFilePath()
        val scope = CoroutineScope(Job())
        val store = DataStoreDataSourceSettingsStore(path, scope)

        store.setApiKey("test-key-123")
        store.setEodhdPrimary(true)

        val reopened = reopen(path, scope)
        assertEquals(DataSourceSettings(apiKey = "test-key-123", eodhdPrimary = true), reopened.observe().first())
    }

    @Test
    fun `the key is trimmed and a blank key removes it`() = runTest {
        val store = DataStoreDataSourceSettingsStore(tempFilePath())

        store.setApiKey("  test-key-123 \n")
        assertEquals("test-key-123", store.current().apiKey)

        store.setApiKey("   ")
        assertNull(store.current().apiKey)
    }

    @Test
    fun `an invalid key is flagged only while it is still the stored key`() = runTest {
        val store = DataStoreDataSourceSettingsStore(tempFilePath())
        store.setApiKey("old-key")
        store.setApiKey("new-key")

        store.markInvalidKey("old-key")
        assertFalse(store.current().invalidKey)

        store.markInvalidKey("new-key")
        assertTrue(store.current().invalidKey)
    }

    @Test
    fun `an exhausted quota is flagged only while it is still the stored key`() = runTest {
        val store = DataStoreDataSourceSettingsStore(tempFilePath())
        store.setApiKey("new-key")

        store.markQuotaExhausted("old-key", day)
        assertNull(store.current().quotaExhaustedOn)

        store.markQuotaExhausted("new-key", day)
        assertEquals(day, store.current().quotaExhaustedOn)
    }

    @Test
    fun `a new key clears the invalid flag, the exhausted quota and the symbol cache but keeps the primary switch`() =
        runTest {
            val store = DataStoreDataSourceSettingsStore(tempFilePath())
            store.setApiKey("first-key")
            store.setEodhdPrimary(true)
            store.markInvalidKey("first-key")
            store.markQuotaExhausted("first-key", day)
            store.cacheSymbol("ISIN:IE00BD0NCM55", CachedSymbol("IE00BD0NCM55.EUFUND", "EUR", day))

            store.setApiKey("second-key")

            assertEquals(DataSourceSettings(apiKey = "second-key", eodhdPrimary = true), store.current())
            assertNull(store.cachedSymbol("ISIN:IE00BD0NCM55"))
        }

    @Test
    fun `removing the key clears every flag, the primary switch and the symbol cache`() = runTest {
        val store = DataStoreDataSourceSettingsStore(tempFilePath())
        store.setApiKey("first-key")
        store.setEodhdPrimary(true)
        store.markInvalidKey("first-key")
        store.markQuotaExhausted("first-key", day)
        store.cacheSymbol("TICKER:IWDA.AS", CachedSymbol("IWDA.AS", "EUR", day))

        store.setApiKey(null)

        assertEquals(DataSourceSettings(), store.current())
        assertNull(store.cachedSymbol("TICKER:IWDA.AS"))
    }

    @Test
    fun `cached symbols, including a not on EODHD answer, survive a new store on the same file`() = runTest {
        val path = tempFilePath()
        val scope = CoroutineScope(Job())
        val store = DataStoreDataSourceSettingsStore(path, scope)
        val found = CachedSymbol("IE00BD0NCM55.EUFUND", "EUR", day)
        val missing = CachedSymbol(symbol = null, currency = null, checkedOn = LocalDate(2026, 9, 1))

        store.cacheSymbol("ISIN:IE00BD0NCM55", found)
        store.cacheSymbol("TICKER:NOPE.AS", missing)

        val reopened = reopen(path, scope)
        assertEquals(found, reopened.cachedSymbol("ISIN:IE00BD0NCM55"))
        assertEquals(missing, reopened.cachedSymbol("TICKER:NOPE.AS"))
        assertNull(reopened.cachedSymbol("ISIN:UNKNOWN"))
    }

    @Test
    fun `caching a symbol again replaces its entry and keeps the others`() = runTest {
        val store = DataStoreDataSourceSettingsStore(tempFilePath())
        store.cacheSymbol("A", CachedSymbol(null, null, day))
        store.cacheSymbol("B", CachedSymbol("B.US", "USD", day))

        store.cacheSymbol("A", CachedSymbol("A.AS", "EUR", day))

        assertEquals("A.AS", store.cachedSymbol("A")?.symbol)
        assertEquals("B.US", store.cachedSymbol("B")?.symbol)
    }

    @Test
    fun `an unreadable symbol cache decodes as empty instead of failing`() {
        assertTrue(decodeSymbolCache("not json").isEmpty())
        assertTrue(decodeSymbolCache(null).isEmpty())
        assertTrue(decodeSymbolCache("").isEmpty())
    }

    @Test
    fun `the settings never print the key`() {
        val settings = DataSourceSettings(apiKey = "test-key-123", eodhdPrimary = true)

        assertFalse(settings.toString().contains("test-key-123"))
        assertTrue(settings.toString().contains("hasKey=true"))
    }

    @Test
    fun `the data source file is excluded from the Android backup and the device transfer`() {
        val rules = listOf("backup_rules.xml", "data_extraction_rules.xml")
            .map { File("src/androidMain/res/xml/$it").readText() }

        assertEquals("patrimonio-datasources.preferences_pb", DATA_SOURCES_FILE_NAME)
        val exclusion = "<exclude domain=\"file\" path=\"$DATA_SOURCES_FILE_NAME\" />"
        assertEquals(1, Regex(Regex.escape(exclusion)).findAll(rules[0]).count())
        // cloud-backup and device-transfer blocks
        assertEquals(2, Regex(Regex.escape(exclusion)).findAll(rules[1]).count())
    }
}
