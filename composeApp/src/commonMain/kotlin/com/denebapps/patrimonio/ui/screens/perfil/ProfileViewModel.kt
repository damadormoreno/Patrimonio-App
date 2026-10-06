package com.denebapps.patrimonio.ui.screens.perfil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROFILE_STOP_TIMEOUT_MS = 5_000L

data class ProfileUiState(
    val firstName: String,
    val lastName: String,
    val initials: String?,
)

/**
 * Perfil screen state: pre-fills the inline-edit fields from the profile preferences and persists
 * each field exactly once on focus loss (design.md D8 — drafts stay local to the composable, so
 * these commands are the only persistence events).
 */
class ProfileViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
    val state: StateFlow<ProfileUiState> = combine(
        preferencesRepository.observeFirstName(),
        preferencesRepository.observeLastName(),
    ) { firstName, lastName ->
        ProfileUiState(
            firstName = firstName,
            lastName = lastName,
            initials = profileInitials(firstName, lastName),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(PROFILE_STOP_TIMEOUT_MS),
        initialValue = ProfileUiState(firstName = "", lastName = "", initials = null),
    )

    // Saves are wrapped in NonCancellable: the focus-loss-on-disposal flush (see PerfilScreen)
    // fires while the route is being popped, and plain viewModelScope writes were cancelled by
    // the back-stack-entry teardown before DataStore committed (confirmed on emulator: a dirty
    // focused field was lost on navigate-away). The write MUST complete once requested.
    fun saveFirstName(value: String) {
        viewModelScope.launch {
            withContext(NonCancellable) { preferencesRepository.setFirstName(value) }
        }
    }

    fun saveLastName(value: String) {
        viewModelScope.launch {
            withContext(NonCancellable) { preferencesRepository.setLastName(value) }
        }
    }
}
