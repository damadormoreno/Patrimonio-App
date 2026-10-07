package com.denebapps.patrimonio.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.DataMaintenanceRepository
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.RenewalReminderSettings
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.platform.appVersionName
import com.denebapps.patrimonio.ui.screens.perfil.profileInitials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SETTINGS_STOP_TIMEOUT_MS = 5_000L

data class SettingsUiState(
    val profileName: String?,
    val profileInitials: String?,
    val themeMode: ThemeMode,
    val reminders: RenewalReminderSettings,
    val versionName: String,
    val clearDataStatus: ClearDataStatus,
)

enum class ClearDataStatus {
    IDLE,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val dataMaintenanceRepository: DataMaintenanceRepository,
    versionName: String = appVersionName(),
) : ViewModel() {
    private val clearDataStatus = MutableStateFlow(ClearDataStatus.IDLE)

    val state: StateFlow<SettingsUiState> = combine(
        preferencesRepository.observeFirstName(),
        preferencesRepository.observeLastName(),
        preferencesRepository.observeThemeMode(),
        preferencesRepository.observeRenewalReminders(),
        clearDataStatus,
    ) { firstName, lastName, themeMode, reminders, clearDataStatus ->
        SettingsUiState(
            profileName = profileName(firstName, lastName),
            profileInitials = profileInitials(firstName, lastName),
            themeMode = themeMode,
            reminders = reminders,
            versionName = versionName,
            clearDataStatus = clearDataStatus,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SETTINGS_STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(
            profileName = null,
            profileInitials = null,
            themeMode = ThemeMode.SYSTEM,
            reminders = RenewalReminderSettings(),
            versionName = versionName,
            clearDataStatus = ClearDataStatus.IDLE,
        ),
    )

    fun onThemeModeSelect(themeMode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(themeMode) }
    }

    /** Call with `true` only once the platform granted notification permission. */
    fun onRemindersEnabledChange(enabled: Boolean) {
        updateReminders { it.copy(enabled = enabled) }
    }

    fun onReminderLeadDaysSelect(leadDays: Int) {
        updateReminders { it.copy(leadDays = leadDays) }
    }

    private fun updateReminders(change: (RenewalReminderSettings) -> RenewalReminderSettings) {
        viewModelScope.launch {
            val current = preferencesRepository.observeRenewalReminders().first()
            preferencesRepository.setRenewalReminders(change(current))
        }
    }

    fun onConfirmDeleteAll() {
        val currentStatus = clearDataStatus.value
        if (currentStatus != ClearDataStatus.IDLE && currentStatus != ClearDataStatus.FAILED) return
        if (!clearDataStatus.compareAndSet(currentStatus, ClearDataStatus.IN_PROGRESS)) return

        viewModelScope.launch {
            try {
                dataMaintenanceRepository.clearAllFinancialData()
                clearDataStatus.value = ClearDataStatus.SUCCEEDED
            } catch (error: CancellationException) {
                clearDataStatus.value = ClearDataStatus.IDLE
                throw error
            } catch (error: Exception) {
                AppLogger.error("SettingsViewModel", "Failed to clear financial data", error)
                clearDataStatus.value = ClearDataStatus.FAILED
            }
        }
    }

    fun onClearDataResultConsumed() {
        val currentStatus = clearDataStatus.value
        if (currentStatus == ClearDataStatus.SUCCEEDED || currentStatus == ClearDataStatus.FAILED) {
            clearDataStatus.compareAndSet(currentStatus, ClearDataStatus.IDLE)
        }
    }
}

private fun profileName(firstName: String, lastName: String): String? =
    listOf(firstName.trim(), lastName.trim()).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }
