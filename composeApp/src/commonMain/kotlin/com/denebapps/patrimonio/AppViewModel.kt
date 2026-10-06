package com.denebapps.patrimonio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

private const val APP_STOP_TIMEOUT_MS = 5_000L

class AppViewModel(preferencesRepository: PreferencesRepository) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = preferencesRepository.observeThemeMode().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(APP_STOP_TIMEOUT_MS),
        initialValue = ThemeMode.SYSTEM,
    )
}
