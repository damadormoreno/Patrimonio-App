package com.denebapps.patrimonio.data.lock

import com.denebapps.patrimonio.domain.repository.AppLock
import com.denebapps.patrimonio.domain.repository.AppLockSettings
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import com.denebapps.patrimonio.domain.repository.PIN_LENGTH
import com.denebapps.patrimonio.domain.repository.PinCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [AppLock] kept in a [LockStore]. Time in the background is measured with [timeSource] (monotonic, so
 * changing the phone's clock does not skip the lock); the wrong-PIN waits use [clock], as they outlive the
 * process.
 */
class AppLockImpl(
    private val store: LockStore,
    private val hasher: PinHasher,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AppLock {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AppLockState>(AppLockState.Checking)
    override val state: StateFlow<AppLockState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(AppLockSettings())
    override val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()
    private var backgroundedAt: TimeMark? = null

    override suspend fun start() = mutex.withLock {
        if (_state.value != AppLockState.Checking) return@withLock
        val stored = store.get()
        publish(stored)
        _state.value = if (stored.pin != null) stored.lockedState() else AppLockState.Unlocked
    }

    override fun onBackground() {
        if (_state.value == AppLockState.Unlocked) backgroundedAt = timeSource.markNow()
    }

    override fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val current = _settings.value
        if (current.pinSet && _state.value == AppLockState.Unlocked &&
            since.elapsedNow() >= current.autoLockDelay.seconds.seconds
        ) {
            _state.value = AppLockState.Locked()
        }
    }

    override suspend fun unlock(pin: String): PinCheck = mutex.withLock {
        val result = check(pin)
        if (result == PinCheck.Correct) _state.value = AppLockState.Unlocked
        result
    }

    override suspend fun unlockWithBiometrics() = mutex.withLock {
        if (!_settings.value.biometrics || _state.value !is AppLockState.Locked) return@withLock
        store.update { it.copy(failedAttempts = 0, retryAtEpochMs = null) }
        _state.value = AppLockState.Unlocked
    }

    override suspend fun verify(pin: String): PinCheck = mutex.withLock { check(pin) }

    override suspend fun setPin(pin: String) = mutex.withLock {
        require(pin.length == PIN_LENGTH && pin.all(Char::isDigit)) { "The PIN must be $PIN_LENGTH digits" }
        val hash = hasher.hash(pin)
        publish(store.update { it.copy(pin = hash, failedAttempts = 0, retryAtEpochMs = null) })
    }

    override suspend fun removePin() = mutex.withLock {
        publish(store.update { StoredLock(autoLockDelay = it.autoLockDelay) })
        _state.value = AppLockState.Unlocked
    }

    override suspend fun setBiometrics(enabled: Boolean) = mutex.withLock {
        publish(store.update { it.copy(biometrics = enabled && it.pin != null) })
    }

    override suspend fun setAutoLockDelay(delay: AutoLockDelay) = mutex.withLock {
        publish(store.update { it.copy(autoLockDelay = delay) })
    }

    /** Checks [pin] against the stored hash, counting a wrong one. Call with [mutex] held. */
    private suspend fun check(pin: String): PinCheck {
        val stored = store.get()
        val hash = stored.pin ?: return PinCheck.Correct
        val now = clock.now()
        stored.retryAt()?.let { if (now < it) return PinCheck.TooSoon(it) }
        if (hasher.matches(pin, hash)) {
            if (stored.failedAttempts > 0) store.update { it.copy(failedAttempts = 0, retryAtEpochMs = null) }
            return PinCheck.Correct
        }
        val failed = stored.failedAttempts + 1
        val retryAt = waitAfter(failed)?.let { now + it }
        val updated = store.update { it.copy(failedAttempts = failed, retryAtEpochMs = retryAt?.toEpochMilliseconds()) }
        if (_state.value is AppLockState.Locked) _state.value = updated.lockedState()
        return PinCheck.Wrong(retryAt)
    }

    private fun publish(stored: StoredLock) {
        _settings.value = AppLockSettings(
            pinSet = stored.pin != null,
            biometrics = stored.biometrics,
            autoLockDelay = stored.autoLockDelay,
        )
    }

    private fun StoredLock.retryAt(): Instant? = retryAtEpochMs?.let(Instant::fromEpochMilliseconds)

    private fun StoredLock.lockedState() = AppLockState.Locked(failedAttempts, retryAt())

    private companion object {
        const val FREE_ATTEMPTS = 4

        /** 30 s after the 5th wrong PIN, doubling with each further one up to 15 min. */
        fun waitAfter(failedAttempts: Int) = if (failedAttempts <= FREE_ATTEMPTS) {
            null
        } else {
            (30.seconds * (1 shl (failedAttempts - FREE_ATTEMPTS - 1).coerceAtMost(5))).coerceAtMost(15.minutes)
        }
    }
}
