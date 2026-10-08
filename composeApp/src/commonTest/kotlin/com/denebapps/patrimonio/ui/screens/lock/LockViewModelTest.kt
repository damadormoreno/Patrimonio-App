package com.denebapps.patrimonio.ui.screens.lock

import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.testing.FakeAppLock
import com.denebapps.patrimonio.testing.FakeAuthRepository
import com.denebapps.patrimonio.testing.FakePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

private class RecordingDataMaintenance(private val calls: MutableList<String>) : DataMaintenanceRepository {
    var failure: Exception? = null

    override suspend fun clearAllFinancialData() {
        failure?.let { throw it }
        calls += "clearData"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val appLock = FakeAppLock(pin = "1234")
    private val auth = FakeAuthRepository(AccountUser("uid-1", "ana@example.com"))
    private val order = mutableListOf<String>()
    private val maintenance = RecordingDataMaintenance(order)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        LockViewModel(appLock, FakePreferencesRepository(firstName = " David "), maintenance, auth)

    @Test
    fun `a wrong PIN shakes and counts, the right one unlocks`() = runTest(dispatcher) {
        appLock.start()
        val vm = viewModel()
        val job = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("David", vm.state.value.firstName)

        vm.onPinEntered("0000")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.wrongPinCount)
        assertEquals(1, vm.state.value.failedAttempts)

        vm.onPinEntered("1234")
        advanceUntilIdle()
        assertEquals(AppLockState.Unlocked, appLock.state.value)
        job.cancel()
    }

    @Test
    fun `biometrics unlock only when they are on`() = runTest(dispatcher) {
        appLock.start()
        val vm = viewModel()

        vm.onBiometricsRecognised()
        advanceUntilIdle()
        assertEquals(AppLockState.Locked(), appLock.state.value)

        appLock.setBiometrics(true)
        vm.onBiometricsRecognised()
        advanceUntilIdle()
        assertEquals(AppLockState.Unlocked, appLock.state.value)
    }

    @Test
    fun `a forgotten PIN signs out before wiping, then removes the lock`() = runTest(dispatcher) {
        appLock.start()
        val vm = viewModel()

        vm.onForgotPin()
        advanceUntilIdle()

        assertEquals(listOf("signOut"), auth.calls)
        assertEquals(listOf("clearData"), order)
        assertNull(auth.observeUser().first())
        assertEquals(AppLockState.Unlocked, appLock.state.value)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a failed wipe keeps the lock and says so`() = runTest(dispatcher) {
        appLock.start()
        maintenance.failure = IllegalStateException("disk")
        val vm = viewModel()
        val job = launch { vm.state.collect {} }

        vm.onForgotPin()
        advanceUntilIdle()

        assertEquals(AppLockState.Locked(), appLock.state.value)
        assertEquals("No se pudieron borrar los datos. Inténtalo de nuevo.", vm.state.value.error)
        job.cancel()
    }
}
