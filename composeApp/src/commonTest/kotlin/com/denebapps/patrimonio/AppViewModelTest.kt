package com.denebapps.patrimonio

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.repository.RenewalReminderSettings
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.notifications.RenewalReminderSync
import com.denebapps.patrimonio.testing.FakeCloudBackup
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import com.denebapps.patrimonio.testing.FakeReminderScheduler
import com.denebapps.patrimonio.testing.FakeSubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val scheduler = FakeReminderScheduler()
    private val cloudBackup = FakeCloudBackup()

    private fun viewModel(preferences: FakePreferencesRepository) = AppViewModel(
        preferencesRepository = preferences,
        renewalReminderSync = RenewalReminderSync(
            subscriptionRepository = FakeSubscriptionRepository(),
            preferencesRepository = preferences,
            scheduler = scheduler,
            clock = fixedClock("2026-10-06T10:00:00Z"),
            zoneProvider = { TimeZone.UTC },
        ),
        cloudBackup = cloudBackup,
    )

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
    fun `starting the app starts the renewal reminder sync`() = runTest(dispatcher) {
        val preferences = FakePreferencesRepository()
        preferences.setRenewalReminders(RenewalReminderSettings(enabled = true))

        viewModel(preferences)
        advanceUntilIdle()

        assertTrue(scheduler.calls.isNotEmpty())
    }

    @Test
    fun `starting the app starts the cloud backup`() = runTest(dispatcher) {
        viewModel(FakePreferencesRepository())
        advanceUntilIdle()

        assertEquals(listOf("run"), cloudBackup.calls)
    }
}
