package com.denebapps.patrimonio.ui.screens.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.AppLock
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.PinCheck
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

private const val LOCK_STOP_TIMEOUT_MS = 5_000L

/**
 * [retryAt]: no PIN is checked before then. [wrongPinCount] grows with each wrong PIN (the dots shake).
 * [failedAttempts] > 0 offers the forgotten-PIN way out.
 */
data class LockUiState(
    val firstName: String = "",
    val biometrics: Boolean = false,
    val failedAttempts: Int = 0,
    val retryAt: Instant? = null,
    val wrongPinCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The lock screen. Forgetting the PIN wipes this device's data (it stays in the cloud copy or exports). */
class LockViewModel(
    private val appLock: AppLock,
    preferencesRepository: PreferencesRepository,
    private val dataMaintenanceRepository: DataMaintenanceRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LockUiState())

    val state: StateFlow<LockUiState> = combine(
        appLock.state,
        appLock.settings,
        preferencesRepository.observeFirstName(),
        local,
    ) { lock, settings, firstName, current ->
        val locked = lock as? AppLockState.Locked
        current.copy(
            firstName = firstName.trim(),
            biometrics = settings.biometrics,
            failedAttempts = locked?.failedAttempts ?: 0,
            retryAt = locked?.retryAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(LOCK_STOP_TIMEOUT_MS), LockUiState())

    fun onPinEntered(pin: String) {
        viewModelScope.launch {
            when (appLock.unlock(pin)) {
                PinCheck.Correct -> local.update { it.copy(error = null) }
                is PinCheck.Wrong, is PinCheck.TooSoon -> local.update { it.copy(wrongPinCount = it.wrongPinCount + 1) }
            }
        }
    }

    fun onBiometricsRecognised() {
        viewModelScope.launch { appLock.unlockWithBiometrics() }
    }

    /** Signs out first, so the emptied device cannot upload over the cloud copy, then wipes and unlocks. */
    fun onForgotPin() {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.signOut()
                dataMaintenanceRepository.clearAllFinancialData()
                appLock.removePin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("LockViewModel", "Could not wipe the data for a forgotten PIN", e)
                local.update { it.copy(error = "No se pudieron borrar los datos. Inténtalo de nuevo.") }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }
}

/** "0:25" until [until]. */
internal fun countdown(now: Instant, until: Instant): String {
    val seconds = (until - now).inWholeSeconds.coerceAtLeast(0) + 1
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
