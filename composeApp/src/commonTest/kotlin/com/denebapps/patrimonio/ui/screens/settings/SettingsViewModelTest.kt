package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.testing.FakeDataMaintenanceRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial state uses SYSTEM empty profile fallback and supplied version`() {
        val viewModel = SettingsViewModel(FakePreferencesRepository(), FakeDataMaintenanceRepository(), "2.4.1")

        assertEquals(ThemeMode.SYSTEM, viewModel.state.value.themeMode)
        assertNull(viewModel.state.value.profileName)
        assertNull(viewModel.state.value.profileInitials)
        assertEquals("2.4.1", viewModel.state.value.versionName)
        assertEquals(ClearDataStatus.IDLE, viewModel.state.value.clearDataStatus)
    }

    @Test
    fun `profile name and initials follow both names live`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = " Ana ", lastName = " Gil ")
        val viewModel = SettingsViewModel(preferences, FakeDataMaintenanceRepository(), "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        assertEquals("Ana Gil", viewModel.state.value.profileName)
        assertEquals("AG", viewModel.state.value.profileInitials)

        preferences.setFirstName("")
        advanceUntilIdle()

        assertEquals("Gil", viewModel.state.value.profileName)
        assertEquals("G", viewModel.state.value.profileInitials)
        job.cancel()
    }

    @Test
    fun `blank names restore neutral profile fallback`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(firstName = "Ana", lastName = "Gil")
        val viewModel = SettingsViewModel(preferences, FakeDataMaintenanceRepository(), "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        preferences.setFirstName("  ")
        preferences.setLastName("")
        advanceUntilIdle()

        assertNull(viewModel.state.value.profileName)
        assertNull(viewModel.state.value.profileInitials)
        job.cancel()
    }

    @Test
    fun `theme selection persists and updates state`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(themeMode = ThemeMode.SYSTEM)
        val viewModel = SettingsViewModel(preferences, FakeDataMaintenanceRepository(), "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.onThemeModeSelect(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, preferences.observeThemeMode().value)
        assertEquals(ThemeMode.DARK, viewModel.state.value.themeMode)
        job.cancel()
    }

    @Test
    fun `back-to-back confirms before dispatch invoke maintenance once`() = runTest(dispatcher) {
        val maintenance = FakeDataMaintenanceRepository()
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")

        viewModel.onConfirmDeleteAll()
        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(1, maintenance.clearCalls)
    }

    @Test
    fun `a second confirm while clearing is in progress is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val maintenance = FakeDataMaintenanceRepository()
        maintenance.gate = gate
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()
        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(1, maintenance.clearCalls)
        assertEquals(ClearDataStatus.IN_PROGRESS, viewModel.state.value.clearDataStatus)

        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(1, maintenance.clearCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ClearDataStatus.SUCCEEDED, viewModel.state.value.clearDataStatus)
        job.cancel()
    }

    @Test
    fun `successful clear exposes terminal state until consumed`() = runTest(dispatcher) {
        val maintenance = FakeDataMaintenanceRepository()
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(ClearDataStatus.SUCCEEDED, viewModel.state.value.clearDataStatus)

        viewModel.onClearDataResultConsumed()
        advanceUntilIdle()

        assertEquals(ClearDataStatus.IDLE, viewModel.state.value.clearDataStatus)
        job.cancel()
    }

    @Test
    fun `failed clear stays visible and can be retried safely`() = runTest(dispatcher) {
        val maintenance = FakeDataMaintenanceRepository().apply {
            clearFailure = IllegalStateException("database failure")
        }
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(ClearDataStatus.FAILED, viewModel.state.value.clearDataStatus)
        assertEquals(1, maintenance.clearCalls)

        maintenance.clearFailure = null
        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(2, maintenance.clearCalls)
        assertEquals(ClearDataStatus.SUCCEEDED, viewModel.state.value.clearDataStatus)
        job.cancel()
    }

    @Test
    fun `failed clear returns to idle when consumed`() = runTest(dispatcher) {
        val maintenance = FakeDataMaintenanceRepository().apply {
            clearFailure = IllegalStateException("database failure")
        }
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()
        viewModel.onClearDataResultConsumed()
        advanceUntilIdle()

        assertEquals(ClearDataStatus.IDLE, viewModel.state.value.clearDataStatus)
        job.cancel()
    }

    @Test
    fun `cancellation resets clear state and permits retry`() = runTest(dispatcher) {
        val maintenance = FakeDataMaintenanceRepository().apply {
            clearFailure = CancellationException("cancelled")
        }
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(ClearDataStatus.IDLE, viewModel.state.value.clearDataStatus)

        maintenance.clearFailure = null
        viewModel.onConfirmDeleteAll()
        advanceUntilIdle()

        assertEquals(2, maintenance.clearCalls)
        assertEquals(ClearDataStatus.SUCCEEDED, viewModel.state.value.clearDataStatus)
        job.cancel()
    }

    @Test
    fun `no delete command runs without an explicit confirm`() = runTest(dispatcher) {
        val maintenance = FakeDataMaintenanceRepository()
        val viewModel = SettingsViewModel(FakePreferencesRepository(), maintenance, "1.0.0")
        val job = launch { viewModel.state.collect {} }
        advanceUntilIdle()

        // Dialog cancel/dismiss stays UI-only (local rememberSaveable) — it never reaches the VM,
        // so the destructive command only ever runs from the explicit confirm action.
        assertEquals(0, maintenance.clearCalls)
        job.cancel()
    }
}
