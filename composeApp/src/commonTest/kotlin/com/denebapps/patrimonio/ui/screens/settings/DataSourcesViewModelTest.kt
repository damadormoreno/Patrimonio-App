package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.data.datasources.DataSourceSettings
import com.denebapps.patrimonio.data.quotes.EodhdApi
import com.denebapps.patrimonio.data.quotes.FIXED_NOW
import com.denebapps.patrimonio.data.quotes.FixedClock
import com.denebapps.patrimonio.data.quotes.MockHandler
import com.denebapps.patrimonio.data.quotes.mockClient
import com.denebapps.patrimonio.data.quotes.respondJson
import com.denebapps.patrimonio.testing.FakeDataSourceSettingsStore
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DataSourcesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // FIXED_NOW is 2025-10-16T10:00:00Z.
    private val clock = FixedClock(FIXED_NOW)
    private val key = "demo-key-abcdef123456"
    private val requests = mutableListOf<HttpRequestData>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(store: FakeDataSourceSettingsStore, handler: MockHandler = { respondJson(USER_JSON) }) =
        DataSourcesViewModel(
            store = store,
            api = EodhdApi(
                mockClient { request ->
                    requests += request
                    handler(request)
                },
                clock,
            ),
            clock = clock,
        )

    private fun TestScope.observe(vm: DataSourcesViewModel): Job = launch { vm.state.collect {} }

    /** The mock engine answers on its own threads: give it real time, then run what it resumed. */
    private suspend fun TestScope.settle() {
        repeat(SETTLE_ROUNDS) {
            withContext(Dispatchers.Default) { delay(SETTLE_MILLIS) }
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.type(vm: DataSourcesViewModel, text: String) {
        vm.onAddKey()
        vm.onDraftChange(text)
        vm.onSave()
        settle()
    }

    @Test
    fun `without a key there is nothing to show and the primary switch cannot be set`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val vm = viewModel(store)
        val job = observe(vm)
        settle()

        assertEquals(DataSourcesUiState(), vm.state.value)
        assertFalse(vm.state.value.hasKey)
        assertTrue(requests.isEmpty())

        vm.onPrimaryChange(true)
        settle()
        assertFalse(store.current().eodhdPrimary)
        job.cancel()
    }

    @Test
    fun `a valid key is checked, stored trimmed and its usage is shown`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val vm = viewModel(store)
        val job = observe(vm)

        vm.onAddKey()
        runCurrent()
        assertEquals(KeyEntry(), vm.state.value.entry)
        vm.onDraftChange("  $key  ")
        vm.onSave()
        settle()

        assertEquals(key, store.current().apiKey)
        assertNull(vm.state.value.entry)
        assertEquals("••••••3456", vm.state.value.maskedKey)
        assertEquals("Plan free · 6 de 20 llamadas hoy", vm.state.value.usage)
        assertEquals(1, requests.size, "the usage fetched to validate the key is reused")
        assertEquals("/api/user", requests.single().url.encodedPath)
        assertEquals(key, requests.single().url.parameters["api_token"])
        job.cancel()
    }

    @Test
    fun `while the key is checked the dialog is busy and saving again does nothing`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val release = CompletableDeferred<Unit>()
        val vm = viewModel(store) {
            release.await()
            respondJson(USER_JSON)
        }
        val job = observe(vm)

        vm.onAddKey()
        vm.onDraftChange(key)
        vm.onSave()
        vm.onSave()
        runCurrent()
        withContext(Dispatchers.Default) { delay(SETTLE_MILLIS) }
        assertTrue(vm.state.value.entry?.checking == true)
        assertNull(store.current().apiKey)

        release.complete(Unit)
        settle()
        assertEquals(1, requests.size)
        assertNull(vm.state.value.entry)
        assertEquals(key, store.current().apiKey)
        job.cancel()
    }

    @Test
    fun `a key EODHD does not recognise shows the error and stores nothing`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val vm = viewModel(store) { respondJson("\"Unauthenticated\"", HttpStatusCode.Unauthorized) }
        val job = observe(vm)

        type(vm, key)

        assertNull(store.current().apiKey)
        assertEquals(
            KeyEntry(draft = key, checking = false, error = "EODHD no reconoce esta clave"),
            vm.state.value.entry,
        )
        assertFalse(vm.state.value.hasKey)
        job.cancel()
    }

    @Test
    fun `a failed check shows the connection error and stores nothing`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val vm = viewModel(store) { respondJson("{}", HttpStatusCode.InternalServerError) }
        val job = observe(vm)

        type(vm, key)

        assertNull(store.current().apiKey)
        assertEquals(
            "No se pudo comprobar la clave. Revisa la conexión e inténtalo de nuevo.",
            vm.state.value.entry?.error,
        )
        assertFalse(vm.state.value.entry?.checking ?: true)
        job.cancel()
    }

    @Test
    fun `typing again clears the error and a retry can succeed`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        var healthy = false
        val vm = viewModel(store) {
            if (healthy) respondJson(USER_JSON) else respondJson("{}", HttpStatusCode.ServiceUnavailable)
        }
        val job = observe(vm)

        type(vm, key)
        assertTrue(vm.state.value.entry?.error != null)

        vm.onDraftChange("$key ")
        runCurrent()
        assertNull(vm.state.value.entry?.error)

        healthy = true
        vm.onSave()
        settle()
        assertEquals(key, store.current().apiKey)
        assertNull(vm.state.value.entry)
        job.cancel()
    }

    @Test
    fun `a blank key is not checked`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val vm = viewModel(store)
        val job = observe(vm)

        type(vm, "   ")

        assertTrue(requests.isEmpty())
        assertNull(store.current().apiKey)
        assertEquals(KeyEntry(draft = "   "), vm.state.value.entry)
        job.cancel()
    }

    @Test
    fun `closing the dialog while it checks stores nothing`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val release = CompletableDeferred<Unit>()
        val vm = viewModel(store) {
            release.await()
            respondJson(USER_JSON)
        }
        val job = observe(vm)

        vm.onAddKey()
        vm.onDraftChange(key)
        vm.onSave()
        runCurrent()
        vm.onEntryDismiss()
        release.complete(Unit)
        settle()

        assertNull(store.current().apiKey)
        assertNull(vm.state.value.entry)
        job.cancel()
    }

    @Test
    fun `a stored key shows its usage when the section opens and a failure just hides it`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key))
        val vm = viewModel(store)
        val job = observe(vm)
        settle()

        assertEquals("••••••3456", vm.state.value.maskedKey)
        assertEquals("Plan free · 6 de 20 llamadas hoy", vm.state.value.usage)
        assertEquals(1, requests.size)
        job.cancel()

        val offlineVm = viewModel(store) { respondJson("{}", HttpStatusCode.BadGateway) }
        val offlineJob = observe(offlineVm)
        settle()
        assertEquals("••••••3456", offlineVm.state.value.maskedKey)
        assertNull(offlineVm.state.value.usage)
        offlineJob.cancel()
    }

    @Test
    fun `the usage omits the plan when EODHD does not name one`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key))
        val vm = viewModel(store) { respondJson("""{"apiRequests":1,"dailyRateLimit":100000}""") }
        val job = observe(vm)
        settle()

        assertEquals("1 de 100.000 llamadas hoy", vm.state.value.usage)
        job.cancel()
    }

    @Test
    fun `replacing the key checks the new one and shows its usage`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key, eodhdPrimary = true))
        val vm = viewModel(store)
        val job = observe(vm)
        settle()

        type(vm, "other-key-zz99")

        assertEquals("other-key-zz99", store.current().apiKey)
        assertEquals("••••••zz99", vm.state.value.maskedKey)
        assertTrue(vm.state.value.eodhdPrimary, "the primary choice survives a key change")
        assertEquals(listOf(key, "other-key-zz99"), requests.map { it.url.parameters["api_token"] })
        job.cancel()
    }

    @Test
    fun `the primary switch follows the store when there is a key`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key))
        val vm = viewModel(store)
        val job = observe(vm)
        settle()
        assertFalse(vm.state.value.eodhdPrimary)

        vm.onPrimaryChange(true)
        settle()
        assertTrue(store.current().eodhdPrimary)
        assertTrue(vm.state.value.eodhdPrimary)

        vm.onPrimaryChange(false)
        settle()
        assertFalse(store.current().eodhdPrimary)
        job.cancel()
    }

    @Test
    fun `removing the key asks first and then clears the key and the usage`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key, eodhdPrimary = true))
        val vm = viewModel(store)
        val job = observe(vm)
        settle()

        vm.onRemoveRequest()
        runCurrent()
        assertTrue(vm.state.value.confirmingRemoval)
        vm.onRemoveDismiss()
        runCurrent()
        assertFalse(vm.state.value.confirmingRemoval)
        assertEquals(key, store.current().apiKey)

        vm.onRemoveRequest()
        vm.onRemoveConfirm()
        settle()

        assertEquals(DataSourceSettings(), store.current())
        assertEquals(DataSourcesUiState(), vm.state.value)
        job.cancel()
    }

    @Test
    fun `a refused key is flagged and takes priority over a used up quota`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore(
            DataSourceSettings(apiKey = key, invalidKey = true, quotaExhaustedOn = LocalDate(2025, 10, 16)),
        )
        val vm = viewModel(store)
        val job = observe(vm)
        settle()

        assertEquals(DataSourceNotice.INVALID_KEY, vm.state.value.notice)
        assertEquals(
            "EODHD rechazó la clave: se usan las fuentes habituales. Cámbiala o quítala.",
            DataSourceNotice.INVALID_KEY.message,
        )
        job.cancel()
    }

    @Test
    fun `the quota notice shows only on the UTC day it was used up`() = runTest(dispatcher) {
        val today = DataSourceSettings(apiKey = key, quotaExhaustedOn = LocalDate(2025, 10, 16))
        val yesterday = DataSourceSettings(apiKey = key, quotaExhaustedOn = LocalDate(2025, 10, 15))

        val todayVm = viewModel(FakeDataSourceSettingsStore(today))
        val yesterdayVm = viewModel(FakeDataSourceSettingsStore(yesterday))
        val jobs = listOf(observe(todayVm), observe(yesterdayVm))
        settle()

        assertEquals(DataSourceNotice.QUOTA_EXHAUSTED, todayVm.state.value.notice)
        assertEquals(
            "Cupo diario de EODHD agotado: hasta mañana se usan las fuentes habituales.",
            DataSourceNotice.QUOTA_EXHAUSTED.message,
        )
        assertNull(yesterdayVm.state.value.notice)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `a healthy key has no notice`() = runTest(dispatcher) {
        val vm = viewModel(FakeDataSourceSettingsStore(DataSourceSettings(apiKey = key)))
        val job = observe(vm)
        settle()

        assertNull(vm.state.value.notice)
        job.cancel()
    }

    @Test
    fun `the mask never exposes more than the last four characters`() {
        assertEquals("••••••3456", maskApiKey("demo-key-abcdef123456"))
        assertEquals("••••••", maskApiKey("short"))
        assertEquals("••••••", maskApiKey("12345678"))
        assertEquals("••••••6789", maskApiKey("123456789"))
        listOf("demo-key-abcdef123456", "short", "123456789").forEach { raw ->
            val masked = maskApiKey(raw)
            assertTrue(masked.removePrefix("••••••").length <= 4, masked)
        }
    }

    @Test
    fun `the full key appears nowhere in the state except the text field draft`() = runTest(dispatcher) {
        val store = FakeDataSourceSettingsStore()
        val vm = viewModel(store)
        val job = observe(vm)

        type(vm, key)
        vm.onAddKey()
        vm.onDraftChange(key)
        runCurrent()

        val state = vm.state.value
        assertEquals(key, state.entry?.draft)
        val withoutDraft = state.copy(entry = state.entry?.copy(draft = ""))
        assertFalse(key in withoutDraft.toString(), withoutDraft.toString())
        assertFalse(key in (state.maskedKey.orEmpty() + state.usage.orEmpty()))
        job.cancel()
    }

    private companion object {
        const val SETTLE_ROUNDS = 3
        const val SETTLE_MILLIS = 30L
        const val USER_JSON =
            """{"subscriptionType":"free","apiRequests":6,"dailyRateLimit":20,"extraLimit":500}"""
    }
}
