package com.denebapps.patrimonio.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.AppLock
import com.denebapps.patrimonio.domain.repository.AppLockSettings
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import com.denebapps.patrimonio.domain.repository.PinCheck
import com.denebapps.patrimonio.ui.screens.lock.countdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

private const val SECURITY_STOP_TIMEOUT_MS = 5_000L

enum class PinFlowMode { CREATE, CHANGE, REMOVE }

enum class PinFlowStep { CURRENT, NEW, CONFIRM }

/** A PIN being set, changed or removed: which [step] it is on and why the last PIN was refused. */
data class PinFlow(
    val mode: PinFlowMode,
    val step: PinFlowStep,
    val error: String? = null,
    val wrongPinCount: Int = 0,
)

data class SecurityUiState(val settings: AppLockSettings = AppLockSettings(), val pinFlow: PinFlow? = null)

/** Ajustes → Seguridad: the PIN lock, biometrics and the auto-lock delay. */
class SecurityViewModel(private val appLock: AppLock, private val clock: Clock) : ViewModel() {
    private val pinFlow = MutableStateFlow<PinFlow?>(null)

    /** The new PIN between [PinFlowStep.NEW] and [PinFlowStep.CONFIRM]; never in the UI state. */
    private var newPin: String? = null

    val state: StateFlow<SecurityUiState> = combine(appLock.settings, pinFlow, ::SecurityUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SECURITY_STOP_TIMEOUT_MS), SecurityUiState())

    fun onLockToggle(enabled: Boolean) = start(if (enabled) PinFlowMode.CREATE else PinFlowMode.REMOVE)

    fun onChangePin() = start(PinFlowMode.CHANGE)

    fun onPinFlowDismiss() {
        newPin = null
        pinFlow.value = null
    }

    fun onPinEntered(pin: String) {
        val flow = pinFlow.value ?: return
        when (flow.step) {
            PinFlowStep.CURRENT -> viewModelScope.launch { checkCurrent(flow, pin) }
            PinFlowStep.NEW -> {
                newPin = pin
                pinFlow.value = flow.copy(step = PinFlowStep.CONFIRM, error = null)
            }
            PinFlowStep.CONFIRM -> if (pin == newPin) {
                viewModelScope.launch {
                    appLock.setPin(pin)
                    onPinFlowDismiss()
                }
            } else {
                newPin = null
                pinFlow.value = flow.copy(
                    step = PinFlowStep.NEW,
                    error = "Los dos PIN no coinciden. Vuelve a elegirlo.",
                    wrongPinCount = flow.wrongPinCount + 1,
                )
            }
        }
    }

    /** Turn it on only after the platform prompt recognised the user. */
    fun onBiometricsChange(enabled: Boolean) {
        viewModelScope.launch { appLock.setBiometrics(enabled) }
    }

    fun onAutoLockDelaySelect(delay: AutoLockDelay) {
        viewModelScope.launch { appLock.setAutoLockDelay(delay) }
    }

    private fun start(mode: PinFlowMode) {
        newPin = null
        pinFlow.value = PinFlow(mode, if (mode == PinFlowMode.CREATE) PinFlowStep.NEW else PinFlowStep.CURRENT)
    }

    private suspend fun checkCurrent(flow: PinFlow, pin: String) {
        val error = when (val check = appLock.verify(pin)) {
            PinCheck.Correct -> null
            is PinCheck.Wrong -> check.retryAt?.let { tooManyAttempts(countdown(clock.now(), it)) } ?: "PIN incorrecto."
            is PinCheck.TooSoon -> tooManyAttempts(countdown(clock.now(), check.retryAt))
        }
        when {
            error != null -> pinFlow.update { it?.copy(error = error, wrongPinCount = flow.wrongPinCount + 1) }
            flow.mode == PinFlowMode.REMOVE -> {
                appLock.removePin()
                onPinFlowDismiss()
            }
            else -> pinFlow.value = flow.copy(step = PinFlowStep.NEW, error = null)
        }
    }

    private fun tooManyAttempts(wait: String) = "Demasiados intentos. Prueba otra vez en $wait."
}
