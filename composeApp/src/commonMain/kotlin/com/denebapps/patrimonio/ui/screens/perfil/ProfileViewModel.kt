package com.denebapps.patrimonio.ui.screens.perfil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ProfilePhotoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
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
    val hasPhoto: Boolean = false,
    /** One-line message after a picked image could not be used. */
    val photoError: String? = null,
)

/**
 * Perfil screen state: pre-fills the inline-edit fields from the profile preferences and persists
 * each field exactly once on focus loss (design.md D8 — drafts stay local to the composable, so
 * these commands are the only persistence events).
 */
class ProfileViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val photoRepository: ProfilePhotoRepository,
) : ViewModel() {
    private val photoError = MutableStateFlow<String?>(null)

    val state: StateFlow<ProfileUiState> = combine(
        preferencesRepository.observeFirstName(),
        preferencesRepository.observeLastName(),
        photoRepository.photo,
        photoError,
    ) { firstName, lastName, photo, error ->
        ProfileUiState(
            firstName = firstName,
            lastName = lastName,
            initials = profileInitials(firstName, lastName),
            hasPhoto = photo != null,
            photoError = error,
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

    /** [read] gives the bytes of the image picked in the gallery. */
    fun onPhotoPicked(read: suspend () -> ByteArray) {
        photoError.value = null
        viewModelScope.launch {
            try {
                photoRepository.setImage(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("ProfileViewModel", "Could not use the picked image", e)
                photoError.value = "No se pudo usar esa imagen."
            }
        }
    }

    fun onRemovePhoto() {
        photoError.value = null
        viewModelScope.launch { photoRepository.clear() }
    }
}
