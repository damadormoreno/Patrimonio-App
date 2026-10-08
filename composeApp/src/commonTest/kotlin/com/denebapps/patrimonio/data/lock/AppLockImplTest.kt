package com.denebapps.patrimonio.data.lock

import com.denebapps.patrimonio.domain.repository.AppLockSettings
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import com.denebapps.patrimonio.domain.repository.PinCheck
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private class InMemoryLockStore(var stored: StoredLock = StoredLock()) : LockStore {
    override suspend fun get() = stored

    override suspend fun update(transform: (StoredLock) -> StoredLock) = transform(stored).also { stored = it }
}

private class MutableClock(var now: Instant = Instant.fromEpochMilliseconds(1_000_000)) : Clock {
    override fun now() = now
}

class AppLockImplTest {
    private val store = InMemoryLockStore()
    private val clock = MutableClock()
    private val time = TestTimeSource()
    private val hasher = PinHasher(iterations = 1)

    private fun lock() = AppLockImpl(store, hasher, clock, time)

    private suspend fun lockWithPin(pin: String = "1234"): AppLockImpl {
        lock().apply { start() }.setPin(pin)
        return lock().apply { start() }
    }

    @Test
    fun `without a PIN the app opens unlocked and never locks`() = runTest {
        val lock = lock()
        assertEquals(AppLockState.Checking, lock.state.value)

        lock.start()
        lock.onBackground()
        time += 10.minutes
        lock.onForeground()

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(AppLockSettings(), lock.settings.value)
    }

    @Test
    fun `with a PIN it starts locked and the right PIN opens it`() = runTest {
        val lock = lockWithPin()

        assertEquals(AppLockState.Locked(), lock.state.value)
        assertEquals(PinCheck.Wrong(retryAt = null), lock.unlock("0000"))
        assertEquals(AppLockState.Locked(failedAttempts = 1), lock.state.value)
        assertEquals(PinCheck.Correct, lock.unlock("1234"))
        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, store.stored.failedAttempts)
        assertTrue(store.stored.pin!!.hash.isNotEmpty())
    }

    @Test
    fun `it locks again only after the delay in the background`() = runTest {
        val lock = lockWithPin()
        lock.unlock("1234")
        lock.setAutoLockDelay(AutoLockDelay.HALF_MINUTE)

        lock.onBackground()
        time += 29.seconds
        lock.onForeground()
        assertEquals(AppLockState.Unlocked, lock.state.value)

        lock.onBackground()
        time += 30.seconds
        lock.onForeground()
        assertEquals(AppLockState.Locked(), lock.state.value)
    }

    @Test
    fun `starting twice does not lock again`() = runTest {
        val lock = lockWithPin()
        lock.unlock("1234")

        lock.start()

        assertEquals(AppLockState.Unlocked, lock.state.value)
    }

    @Test
    fun `wrong PINs make the next one wait, also after a restart`() = runTest {
        val lock = lockWithPin()
        repeat(4) { assertEquals(PinCheck.Wrong(retryAt = null), lock.unlock("0000")) }

        val fifth = lock.unlock("0000")
        val retryAt = clock.now + 30.seconds
        assertEquals(PinCheck.Wrong(retryAt), fifth)
        assertEquals(AppLockState.Locked(5, retryAt), lock.state.value)

        val restarted = lock().apply { start() }
        assertEquals(AppLockState.Locked(5, retryAt), restarted.state.value)
        assertEquals(PinCheck.TooSoon(retryAt), restarted.unlock("1234"))

        clock.now = retryAt
        assertEquals(PinCheck.Wrong(clock.now + 1.minutes), restarted.unlock("0000"))
        clock.now += 1.minutes
        assertEquals(PinCheck.Correct, restarted.unlock("1234"))
        assertNull(store.stored.retryAtEpochMs)
    }

    @Test
    fun `the wait doubles up to 15 minutes`() = runTest {
        val lock = lockWithPin()
        val waits = (1..12).map {
            val check = lock.unlock("0000") as PinCheck.Wrong
            val wait = check.retryAt?.let { at -> at - clock.now }
            check.retryAt?.let { at -> clock.now = at }
            wait
        }

        assertEquals(listOf(null, null, null, null), waits.take(4))
        assertEquals(
            listOf(30.seconds, 1.minutes, 2.minutes, 4.minutes, 8.minutes, 15.minutes, 15.minutes, 15.minutes),
            waits.drop(4),
        )
    }

    @Test
    fun `biometrics open it only when they are on, and reset the wrong PINs`() = runTest {
        val lock = lockWithPin()
        lock.unlock("0000")

        lock.unlockWithBiometrics()
        assertIs<AppLockState.Locked>(lock.state.value)

        lock.setBiometrics(true)
        lock.unlockWithBiometrics()
        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(0, store.stored.failedAttempts)
    }

    @Test
    fun `verify checks the PIN without unlocking`() = runTest {
        val lock = lockWithPin()

        assertEquals(PinCheck.Correct, lock.verify("1234"))
        assertIs<AppLockState.Locked>(lock.state.value)
    }

    @Test
    fun `removing the PIN unlocks and turns biometrics off, keeping the delay`() = runTest {
        val lock = lockWithPin()
        lock.setBiometrics(true)
        lock.setAutoLockDelay(AutoLockDelay.FIVE_MINUTES)

        lock.removePin()

        assertEquals(AppLockState.Unlocked, lock.state.value)
        assertEquals(AppLockSettings(autoLockDelay = AutoLockDelay.FIVE_MINUTES), lock.settings.value)
        lock.setBiometrics(true)
        assertEquals(false, lock.settings.value.biometrics)
    }

    @Test
    fun `only 4 digit PINs are accepted`() = runTest {
        val lock = lock().apply { start() }

        assertFailsWith<IllegalArgumentException> { lock.setPin("123") }
        assertFailsWith<IllegalArgumentException> { lock.setPin("12a4") }
    }
}
