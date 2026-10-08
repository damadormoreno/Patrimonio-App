package com.denebapps.patrimonio.ui.screens.settings

import com.denebapps.patrimonio.domain.calc.fixedClock
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import com.denebapps.patrimonio.testing.FakeAppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = fixedClock("2026-10-08T10:00:00Z")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `turning the lock on asks for the PIN twice`() = runTest(dispatcher) {
        val lock = FakeAppLock()
        val vm = SecurityViewModel(lock, clock)
        val job = launch { vm.state.collect {} }

        vm.onLockToggle(true)
        advanceUntilIdle()
        assertEquals(PinFlow(PinFlowMode.CREATE, PinFlowStep.NEW), vm.state.value.pinFlow)

        vm.onPinEntered("1234")
        advanceUntilIdle()
        assertEquals(PinFlowStep.CONFIRM, vm.state.value.pinFlow?.step)

        vm.onPinEntered("1243")
        advanceUntilIdle()
        assertEquals(PinFlowStep.NEW, vm.state.value.pinFlow?.step)
        assertEquals("Los dos PIN no coinciden. Vuelve a elegirlo.", vm.state.value.pinFlow?.error)

        vm.onPinEntered("5678")
        vm.onPinEntered("5678")
        advanceUntilIdle()
        assertEquals(listOf("setPin:5678"), lock.calls)
        assertNull(vm.state.value.pinFlow)
        assertTrue(vm.state.value.settings.pinSet)
        job.cancel()
    }

    @Test
    fun `turning it off needs the current PIN`() = runTest(dispatcher) {
        val lock = FakeAppLock(pin = "1234")
        val vm = SecurityViewModel(lock, clock)
        val job = launch { vm.state.collect {} }

        vm.onLockToggle(false)
        vm.onPinEntered("0000")
        advanceUntilIdle()
        assertEquals("PIN incorrecto.", vm.state.value.pinFlow?.error)
        assertEquals(1, vm.state.value.pinFlow?.wrongPinCount)

        vm.onPinEntered("1234")
        advanceUntilIdle()
        assertEquals(listOf("removePin"), lock.calls)
        assertNull(vm.state.value.pinFlow)
        job.cancel()
    }

    @Test
    fun `changing the PIN checks the current one, then asks for the new one twice`() = runTest(dispatcher) {
        val lock = FakeAppLock(pin = "1234")
        val vm = SecurityViewModel(lock, clock)
        val job = launch { vm.state.collect {} }

        vm.onChangePin()
        vm.onPinEntered("1234")
        advanceUntilIdle()
        assertEquals(PinFlow(PinFlowMode.CHANGE, PinFlowStep.NEW), vm.state.value.pinFlow)

        vm.onPinEntered("9999")
        vm.onPinEntered("9999")
        advanceUntilIdle()
        assertEquals(listOf("setPin:9999"), lock.calls)
        job.cancel()
    }

    @Test
    fun `too many wrong PINs say how long to wait`() = runTest(dispatcher) {
        val lock = FakeAppLock(pin = "1234").apply { retryAt = Instant.parse("2026-10-08T10:00:29Z") }
        val vm = SecurityViewModel(lock, clock)
        val job = launch { vm.state.collect {} }

        vm.onChangePin()
        vm.onPinEntered("0000")
        advanceUntilIdle()

        assertEquals("Demasiados intentos. Prueba otra vez en 0:30.", vm.state.value.pinFlow?.error)
        job.cancel()
    }

    @Test
    fun `cancelling forgets the flow and the delay goes to the lock`() = runTest(dispatcher) {
        val lock = FakeAppLock(pin = "1234")
        val vm = SecurityViewModel(lock, clock)
        val job = launch { vm.state.collect {} }

        vm.onChangePin()
        vm.onPinFlowDismiss()
        vm.onAutoLockDelaySelect(AutoLockDelay.FIVE_MINUTES)
        advanceUntilIdle()

        assertNull(vm.state.value.pinFlow)
        assertEquals(AutoLockDelay.FIVE_MINUTES, vm.state.value.settings.autoLockDelay)
        job.cancel()
    }
}
