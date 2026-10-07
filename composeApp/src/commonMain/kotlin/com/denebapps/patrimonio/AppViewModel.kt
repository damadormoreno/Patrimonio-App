package com.denebapps.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.notifications.RenewalReminderSync
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val APP_STOP_TIMEOUT_MS = 5_000L

/** App-wide state for the root UI. Also keeps renewal reminders in sync while the app is open: this
 *  ViewModel lives as long as the UI and survives configuration changes, so the sync starts once. */
class AppViewModel(
    preferencesRepository: PreferencesRepository,
    renewalReminderSync: RenewalReminderSync,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = preferencesRepository.observeThemeMode().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(APP_STOP_TIMEOUT_MS),
        initialValue = ThemeMode.SYSTEM,
    )

    init {
        viewModelScope.launch { renewalReminderSync.run() }
    }
}
