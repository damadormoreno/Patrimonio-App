package com.denebapps.patrimonio

import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.testing.FakeAppLock
import com.denebapps.patrimonio.testing.FakeCloudBackup
import com.denebapps.patrimonio.testing.FakePreferencesRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val cloudBackup = FakeCloudBackup()
    private val appLock = FakeAppLock(pin = "1234")

    private fun viewModel(preferences: FakePreferencesRepository) = AppViewModel(
        preferencesRepository = preferences,
        cloudBackup = cloudBackup,
        appLock = appLock,
    )

    @Test
    fun `starting the app starts the lock and the lifecycle goes to it`() = runTest(dispatcher) {
        val viewModel = viewModel(FakePreferencesRepository())
        advanceUntilIdle()

        viewModel.onBackground()
        viewModel.onForeground()

        assertEquals(listOf("start", "background", "foreground"), appLock.calls)
        assertEquals(AppLockState.Locked(), viewModel.lockState.value)
    }

    @Test
    fun `theme starts at SYSTEM before preferences emit`() {
        val viewModel = viewModel(FakePreferencesRepository(themeMode = ThemeMode.DARK))

        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
    }

    @Test
    fun `theme follows live preference changes`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(themeMode = ThemeMode.LIGHT)
        val viewModel = viewModel(preferences)
        val job = launch { viewModel.themeMode.collect {} }
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)

        preferences.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
        job.cancel()
    }

    @Test
    fun `starting the app starts the cloud backup`() = runTest(dispatcher) {
        viewModel(FakePreferencesRepository())
        advanceUntilIdle()

        assertEquals(listOf("run"), cloudBackup.calls)
    }
}
