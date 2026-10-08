package com.denebapps.patrimonio.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.AuthRepository
import com.denebapps.patrimonio.domain.repository.CloudBackup
import com.denebapps.patrimonio.domain.repository.CloudBackupError
import com.denebapps.patrimonio.domain.repository.CloudBackupException
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.ui.components.formatDayMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 * The optional account: sign in / create it while signed out; while signed in, the state of its cloud backup
 * (and the choice when the cloud holds other data), sign out and delete it.
 */
class AccountViewModel(private val authRepository: AuthRepository, private val cloudBackup: CloudBackup) :
    ViewModel() {
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
    fun onDeleteAccount(password: String) {
        run(successInfo = "Cuenta y copia en la nube borradas. Tus datos siguen en este móvil.") {
            cloudBackup.deleteAccount(password)
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

    private fun run(successInfo: String?, action: suspend () -> Unit) {
        form.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                action()
                form.update { it.copy(busy = false, info = successInfo) }
            } catch (e: AuthException) {
                form.update { it.copy(busy = false, error = messageFor(e.error)) }
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
