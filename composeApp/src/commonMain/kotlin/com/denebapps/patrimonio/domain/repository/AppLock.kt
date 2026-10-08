package com.denebapps.patrimonio.domain.repository

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

/** How long the app may stay in the background before it asks for the PIN again. */
enum class AutoLockDelay(val seconds: Int) {
    HALF_MINUTE(30),
    ONE_MINUTE(60),
    FIVE_MINUTES(300),
}

/** [pinSet]: the lock is on. [biometrics]: the fingerprint or face also opens it. */
data class AppLockSettings(
    val pinSet: Boolean = false,
    val biometrics: Boolean = false,
    val autoLockDelay: AutoLockDelay = AutoLockDelay.ONE_MINUTE,
)

sealed interface AppLockState {
    /** Reading the settings at start-up: nothing may be shown yet. */
    data object Checking : AppLockState

    /** The lock is off, or the PIN (or a biometric) opened it. */
    data object Unlocked : AppLockState

    /** Waiting for the PIN. After too many wrong ones, no PIN is checked before [retryAt]. */
    data class Locked(val failedAttempts: Int = 0, val retryAt: Instant? = null) : AppLockState
}

sealed interface PinCheck {
    data object Correct : PinCheck

    /** [retryAt]: too many wrong PINs, the next one is not checked before then. */
    data class Wrong(val retryAt: Instant?) : PinCheck

    /** Asked before [retryAt]; the PIN was not checked. */
    data class TooSoon(val retryAt: Instant) : PinCheck
}

/**
 * The app lock: a 4-digit PIN, optionally opened with the fingerprint or face too. It locks when the app
 * starts and when it comes back after more than [AppLockSettings.autoLockDelay] in the background. Wrong
 * PINs are counted across restarts and, after a few, each further one makes the next wait longer.
 *
 * It hides the app from whoever picks up an unlocked phone; the data itself stays as protected as any app's
 * private storage.
 */
interface AppLock {
    val state: StateFlow<AppLockState>
    val settings: StateFlow<AppLockSettings>

    /** Reads the settings and locks if the PIN is set. Only the first call does anything. */
    suspend fun start()

    fun onBackground()

    fun onForeground()

    /** On the lock screen: checks [pin] and unlocks when it is right. */
    suspend fun unlock(pin: String): PinCheck

    /** On the lock screen, after the platform recognised the fingerprint or face. */
    suspend fun unlockWithBiometrics()

    /** Checks [pin] without changing the lock state, to confirm a change in the settings. Counts as an
     *  attempt like [unlock]. */
    suspend fun verify(pin: String): PinCheck

    /** Turns the lock on with [pin], or replaces the current PIN. */
    suspend fun setPin(pin: String)

    /** Turns the lock off (biometrics too). */
    suspend fun removePin()

    suspend fun setBiometrics(enabled: Boolean)

    suspend fun setAutoLockDelay(delay: AutoLockDelay)
}

const val PIN_LENGTH = 4
