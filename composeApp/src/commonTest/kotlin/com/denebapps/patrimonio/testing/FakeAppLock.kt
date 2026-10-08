package com.denebapps.patrimonio.testing

import com.denebapps.patrimonio.domain.repository.AppLock
import com.denebapps.patrimonio.domain.repository.AppLockSettings
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import com.denebapps.patrimonio.domain.repository.PinCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant

/** In-memory [AppLock] with a plain [pin]. [retryAt] makes wrong PINs ask to wait; [calls] records changes. */
class FakeAppLock(var pin: String? = null) : AppLock {
    override val state = MutableStateFlow<AppLockState>(AppLockState.Checking)
    override val settings = MutableStateFlow(AppLockSettings(pinSet = pin != null))
    val calls = mutableListOf<String>()
    var retryAt: Instant? = null
    private var failed = 0

    override suspend fun start() {
        calls += "start"
        state.value = if (pin != null) AppLockState.Locked() else AppLockState.Unlocked
    }

    override fun onBackground() {
        calls += "background"
    }

    override fun onForeground() {
        calls += "foreground"
    }

    override suspend fun unlock(pin: String): PinCheck = verify(pin).also {
        if (it == PinCheck.Correct) state.value = AppLockState.Unlocked
    }

    override suspend fun unlockWithBiometrics() {
        calls += "biometrics"
        if (settings.value.biometrics) state.value = AppLockState.Unlocked
    }

    override suspend fun verify(pin: String): PinCheck {
        if (pin == this.pin) {
            failed = 0
            return PinCheck.Correct
        }
        failed++
        if (state.value is AppLockState.Locked) state.value = AppLockState.Locked(failed, retryAt)
        return PinCheck.Wrong(retryAt)
    }

    override suspend fun setPin(pin: String) {
        calls += "setPin:$pin"
        this.pin = pin
        settings.value = settings.value.copy(pinSet = true)
    }

    override suspend fun removePin() {
        calls += "removePin"
        pin = null
        settings.value = settings.value.copy(pinSet = false, biometrics = false)
        state.value = AppLockState.Unlocked
    }

    override suspend fun setBiometrics(enabled: Boolean) {
        settings.value = settings.value.copy(biometrics = enabled)
    }

    override suspend fun setAutoLockDelay(delay: AutoLockDelay) {
        settings.value = settings.value.copy(autoLockDelay = delay)
    }
}
