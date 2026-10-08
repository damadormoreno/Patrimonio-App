package com.denebapps.patrimonio.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.platform.AppLogger
import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.CloudBackup
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.domain.repository.GoogleProfile
import com.denebapps.patrimonio.domain.repository.PreferencesRepository
import com.denebapps.patrimonio.domain.repository.ProfilePhotoRepository
import com.denebapps.patrimonio.domain.repository.Reauthentication
import com.denebapps.patrimonio.ui.components.formatDayMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val ACCOUNT_STOP_TIMEOUT_MS = 5_000L
private const val MIN_PASSWORD_LENGTH = 6
private const val DELETE_CLOUD_COPY_FAILED =
    "No se pudo borrar la copia de la nube, así que la cuenta sigue activa. Inténtalo de nuevo."
private const val GOOGLE_NO_ACCOUNT = "No hay ninguna cuenta de Google en este móvil."
private const val GOOGLE_FAILED = "No se pudo iniciar sesión con Google. Inténtalo de nuevo."
private const val GOOGLE_OTHER_ACCOUNT = "Elige la misma cuenta de Google con la que iniciaste sesión."
private val EMAIL_SHAPE = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

enum class AccountMode { SIGN_IN, SIGN_UP }

/** [error] and [info] are one-line messages under the form; [busy] while a request is in flight. */
data class AccountUiState(
    val user: AccountUser? = null,
    val cloud: CloudBackupState = CloudBackupState.SignedOut,
    val mode: AccountMode = AccountMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
) {
    val canSubmit: Boolean
        get() = !busy && EMAIL_SHAPE.matches(email.trim()) && password.length >= MIN_PASSWORD_LENGTH

    val canResetPassword: Boolean
        get() = !busy && EMAIL_SHAPE.matches(email.trim())
}

private data class AccountForm(
    val mode: AccountMode = AccountMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

/**
 * The optional account: sign in / create it while signed out (email and password, or Google); while signed in,
 * the state of its cloud backup (and the choice when the cloud holds other data), sign out and delete it.
 * The first Google sign-in fills an empty profile with the Google name and photo.
 */
class AccountViewModel(
    private val authRepository: AuthRepository,
    private val cloudBackup: CloudBackup,
    private val preferencesRepository: PreferencesRepository,
    private val photoRepository: ProfilePhotoRepository,
) : ViewModel() {
    private val form = MutableStateFlow(AccountForm())

    val state: StateFlow<AccountUiState> =
        combine(authRepository.observeUser(), cloudBackup.state, form) { user, cloud, current ->
            AccountUiState(
                user = user,
                cloud = cloud,
                mode = current.mode,
                email = current.email,
                password = current.password,
                busy = current.busy,
                error = current.error,
                info = current.info,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ACCOUNT_STOP_TIMEOUT_MS), AccountUiState())

    fun onModeChange(mode: AccountMode) {
        form.update { it.copy(mode = mode, error = null, info = null) }
    }

    fun onEmailChange(email: String) {
        form.update { it.copy(email = email, error = null) }
    }

    fun onPasswordChange(password: String) {
        form.update { it.copy(password = password, error = null) }
    }

    fun onSubmit() {
        if (!state.value.canSubmit) return
        val (mode, email, password) = form.value
        run(successInfo = null) {
            when (mode) {
                AccountMode.SIGN_IN -> authRepository.signIn(email, password)
                AccountMode.SIGN_UP -> authRepository.signUp(email, password)
            }
            form.update { it.copy(password = "") }
        }
    }

    /** What the Google account picker gave back; signs in with the picked account. */
    fun onGoogleResult(result: GoogleIdTokenResult) {
        val idToken = googleIdTokenOrShowError(result) ?: return
        run(successInfo = null) {
            val profile = authRepository.signInWithGoogle(idToken)
            fillProfileFrom(profile)
        }
    }

    fun onForgotPassword() {
        if (!state.value.canResetPassword) return
        val email = form.value.email
        run(successInfo = "Te hemos enviado un correo a ${email.trim()} para cambiar la contraseña.") {
            authRepository.sendPasswordReset(email)
        }
    }

    fun onSignOut() {
        run(successInfo = null) {
            authRepository.signOut()
            form.value = AccountForm()
        }
    }

    /** Deletes the cloud copy and the account after checking [password]; the data on this device is kept. */
    fun onDeleteAccount(password: String) = deleteAccount(Reauthentication.Password(password))

    /** As [onDeleteAccount] for a Google account: the user picks the same Google account again. */
    fun onDeleteAccountWithGoogle(result: GoogleIdTokenResult) {
        val idToken = googleIdTokenOrShowError(result) ?: return
        deleteAccount(Reauthentication.Google(idToken))
    }

    private fun deleteAccount(reauthentication: Reauthentication) {
        // A Google account has no password to get wrong: another Google account was picked.
        val authMessage = { error: AuthError ->
            val otherAccount = reauthentication is Reauthentication.Google && error == AuthError.WRONG_CREDENTIALS
            if (otherAccount) GOOGLE_OTHER_ACCOUNT else messageFor(error)
        }
        run(successInfo = "Cuenta y copia en la nube borradas. Tus datos siguen en este móvil.", authMessage) {
            cloudBackup.deleteAccount(reauthentication)
        }
    }

    fun onUseCloudCopy() = cloudBackup.useCloudCopy()

    fun onKeepLocalData() = cloudBackup.keepLocalData()

    fun onRetryCloud() = cloudBackup.retry()

    fun onBackUpNow() = cloudBackup.backUpNow()

    /** Creates the passphrase, or opens the encrypted cloud copy with it. */
    fun onSubmitPassphrase(passphrase: String) = cloudBackup.submitPassphrase(passphrase)

    /** Forgotten passphrase: the cloud copy is replaced with this device's data under [passphrase]. */
    fun onStartOver(passphrase: String) = cloudBackup.startOver(passphrase)

    fun onChangePassphrase(passphrase: String) {
        run(successInfo = "Frase cambiada. La copia de la nube se abre ya con la nueva.") {
            cloudBackup.changePassphrase(passphrase)
        }
    }

    /** The token to sign in with, or null after showing why there is none (nothing when the user cancelled). */
    private fun googleIdTokenOrShowError(result: GoogleIdTokenResult): String? {
        val error = when (result) {
            is GoogleIdTokenResult.Token -> return result.idToken
            GoogleIdTokenResult.Cancelled -> null
            GoogleIdTokenResult.NoAccount -> GOOGLE_NO_ACCOUNT
            GoogleIdTokenResult.Failed -> GOOGLE_FAILED
        }
        form.update { it.copy(error = error, info = null) }
        return null
    }

    /** Only an empty profile: a name or a photo the user chose is never replaced. A photo that cannot be
     *  downloaded is skipped, the sign-in has already worked. */
    private suspend fun fillProfileFrom(profile: GoogleProfile) {
        val noName = preferencesRepository.observeFirstName().first().isBlank() &&
            preferencesRepository.observeLastName().first().isBlank()
        if (noName) {
            profile.firstName?.let { preferencesRepository.setFirstName(it) }
            profile.lastName?.let { preferencesRepository.setLastName(it) }
        }
        val photoUrl = profile.photoUrl ?: return
        try {
            photoRepository.setFromGoogleIfEmpty(photoUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error("AccountViewModel", "Could not download the Google photo", e)
        }
    }

    private fun run(
        successInfo: String?,
        authMessage: (AuthError) -> String = ::messageFor,
        action: suspend () -> Unit,
    ) {
        form.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                action()
                form.update { it.copy(busy = false, info = successInfo) }
            } catch (e: AuthException) {
                form.update { it.copy(busy = false, error = authMessage(e.error)) }
            } catch (e: CloudBackupException) {
                form.update { it.copy(busy = false, error = DELETE_CLOUD_COPY_FAILED) }
            }
        }
    }
}

internal fun messageFor(error: AuthError): String = when (error) {
    AuthError.INVALID_EMAIL -> "El correo no es válido."
    AuthError.EMAIL_IN_USE -> "Ya hay una cuenta con ese correo. Inicia sesión."
    AuthError.WEAK_PASSWORD -> "La contraseña debe tener al menos 6 caracteres."
    AuthError.WRONG_CREDENTIALS -> "Correo o contraseña incorrectos."
    AuthError.TOO_MANY_ATTEMPTS -> "Demasiados intentos. Espera unos minutos y vuelve a probar."
    AuthError.NOT_SIGNED_IN, AuthError.SESSION_EXPIRED -> "La sesión ha caducado. Vuelve a iniciar sesión."
    AuthError.NETWORK -> "No hay conexión. Inténtalo de nuevo."
    AuthError.NOT_AVAILABLE -> "Ese método de acceso no está disponible ahora mismo."
    AuthError.UNKNOWN -> "No se ha podido completar. Inténtalo de nuevo."
}

internal const val MIN_PASSPHRASE_LENGTH = 8

/** Why a new passphrase cannot be used yet, or null when it can. */
internal fun passphraseProblem(passphrase: String, confirmation: String): String? = when {
    passphrase.length < MIN_PASSPHRASE_LENGTH -> "La frase debe tener al menos $MIN_PASSPHRASE_LENGTH caracteres."
    passphrase != confirmation -> "Las dos frases no coinciden."
    else -> null
}

internal fun cloudMessageFor(error: CloudBackupError): String = when (error) {
    CloudBackupError.NETWORK -> "No hay conexión. Se volverá a intentar."
    CloudBackupError.NOT_AVAILABLE -> "La copia en la nube no está disponible ahora mismo."
    CloudBackupError.TOO_LARGE -> "Tus datos ocupan demasiado para la copia en la nube."
    CloudBackupError.INVALID_BACKUP -> "La copia de la nube no se puede leer. ¿Necesitas actualizar la app?"
    CloudBackupError.SESSION_EXPIRED -> "La sesión ha caducado. Vuelve a iniciar sesión."
    CloudBackupError.UNKNOWN -> "Algo ha fallado. Se volverá a intentar."
}

/** "8 oct, 10:42" in [zone]. */
internal fun formatBackupTime(instant: Instant, zone: TimeZone): String {
    val local = instant.toLocalDateTime(zone)
    return "${formatDayMonth(local.date)}, ${local.hour.toString().padStart(2, '0')}:" +
        local.minute.toString().padStart(2, '0')
}
