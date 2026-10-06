package com.denebapps.patrimonio

import com.denebapps.patrimonio.domain.repository.ThemeMode
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

    @Test
    fun `theme starts at SYSTEM before preferences emit`() {
        val viewModel = AppViewModel(FakePreferencesRepository(themeMode = ThemeMode.DARK))

        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
    }

    @Test
    fun `theme follows live preference changes`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository(themeMode = ThemeMode.LIGHT)
        val viewModel = AppViewModel(preferences)
        val job = launch { viewModel.themeMode.collect {} }
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)

        preferences.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
        job.cancel()
    }
}
