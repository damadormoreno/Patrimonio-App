package com.denebapps.patrimonio.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.domain.repository.AccountUser
import com.denebapps.patrimonio.domain.repository.AuthError
import com.denebapps.patrimonio.domain.repository.AuthException
import com.denebapps.patrimonio.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ACCOUNT_STOP_TIMEOUT_MS = 5_000L
private const val MIN_PASSWORD_LENGTH = 6
private val EMAIL_SHAPE = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

enum class AccountMode { SIGN_IN, SIGN_UP }

/** [error] and [info] are one-line messages under the form; [busy] while a request is in flight. */
data class AccountUiState(
    val user: AccountUser? = null,
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

/** The optional account: sign in / create it while signed out; sign out or delete it while signed in. */
class AccountViewModel(private val authRepository: AuthRepository) : ViewModel() {
    private val form = MutableStateFlow(AccountForm())

    val state: StateFlow<AccountUiState> = combine(authRepository.observeUser(), form) { user, current ->
        AccountUiState(user, current.mode, current.email, current.password, current.busy, current.error, current.info)
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

    /** Deletes the account after checking [password]; the data on this device is kept. */
    fun onDeleteAccount(password: String) {
        run(successInfo = "Cuenta borrada. Tus datos siguen en este móvil.") {
            authRepository.deleteAccount(password)
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
