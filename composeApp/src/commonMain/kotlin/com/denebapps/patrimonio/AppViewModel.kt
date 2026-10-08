package com.denebapps.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.AppLock
import com.denebapps.patrimonio.domain.repository.AppLockSettings
import com.denebapps.patrimonio.domain.repository.AppLockState
import com.denebapps.patrimonio.domain.repository.CloudBackup
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.notifications.RenewalReminderSync
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val APP_STOP_TIMEOUT_MS = 5_000L

/** App-wide state for the root UI: theme and app lock. Also keeps renewal reminders and the cloud backup going
 *  while the app is open: this ViewModel lives as long as the UI and survives configuration changes, so each
 *  starts once. */
class AppViewModel(
    preferencesRepository: PreferencesRepository,
    renewalReminderSync: RenewalReminderSync,
    cloudBackup: CloudBackup,
    private val appLock: AppLock,
) : ViewModel() {
    val lockState: StateFlow<AppLockState> = appLock.state
    val lockSettings: StateFlow<AppLockSettings> = appLock.settings

    val themeMode: StateFlow<ThemeMode> = preferencesRepository.observeThemeMode().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(APP_STOP_TIMEOUT_MS),
        initialValue = ThemeMode.SYSTEM,
    )

    init {
        viewModelScope.launch { appLock.start() }
        viewModelScope.launch { renewalReminderSync.run() }
        viewModelScope.launch { cloudBackup.run() }
    }

    fun onBackground() = appLock.onBackground()

    fun onForeground() = appLock.onForeground()
}
