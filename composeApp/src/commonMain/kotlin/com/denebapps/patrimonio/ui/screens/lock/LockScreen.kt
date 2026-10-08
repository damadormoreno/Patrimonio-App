package com.denebapps.patrimonio.ui.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.denebapps.patrimonio.ui.components.TextLink
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.koin.compose.viewmodel.koinViewModel

/** Covers the app while it is locked (`lock.jsx`): the PIN, or the fingerprint / face when it is on. */
@Composable
fun LockScreen(modifier: Modifier = Modifier, viewModel: LockViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current
    val biometric = rememberBiometricAuthenticator()
    val canUseBiometrics = state.biometrics && biometric != null
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirmForgot by rememberSaveable { mutableStateOf(false) }

    fun askBiometrics() {
        val authenticator = biometric ?: return
        scope.launch {
            if (authenticator.authenticate("Entra en Patrimonio")) viewModel.onBiometricsRecognised()
        }
    }

    // Offered straight away, once the screen is in front (the system prompt needs a resumed activity).
    LaunchedEffect(canUseBiometrics) {
        if (canUseBiometrics) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            askBiometrics()
        }
    }
    val now by produceState(Clock.System.now(), state.retryAt) {
        while (state.retryAt?.let { value < it } == true) {
            delay(1_000)
            value = Clock.System.now()
        }
    }
    val waitingUntil = state.retryAt?.takeIf { now < it }

    Column(
        modifier = modifier.fillMaxSize().background(colors.bg).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppBadge(AppIcons.wallet)
            Text(
                text = if (state.firstName.isEmpty()) "Hola." else "Hola, ${state.firstName}.",
                color = colors.ink,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 32.sp),
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            )
            Text(
                text = when {
                    waitingUntil != null -> "Demasiados intentos. Prueba otra vez en ${countdown(now, waitingUntil)}"
                    state.failedAttempts > 0 -> "PIN incorrecto, prueba otra vez"
                    else -> "Introduce tu PIN para entrar"
                },
                color = if (waitingUntil != null || state.failedAttempts > 0) colors.expense else colors.muted,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            state.error?.let {
                Text(it, color = colors.expense, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        PinEntry(
            onPinComplete = viewModel::onPinEntered,
            enabled = waitingUntil == null && !state.busy,
            wrongPinCount = state.wrongPinCount,
            extraKey = biometric?.takeIf { canUseBiometrics }?.let {
                PinPadExtraKey(biometricIcon(it.kind), "Entrar con ${it.label}", ::askBiometrics)
            },
        )
        Column(
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (canUseBiometrics) TextLink(text = "Entrar con ${biometric?.label}", onClick = ::askBiometrics)
            if (state.failedAttempts > 0) TextLink(text = "¿Has olvidado el PIN?", onClick = { confirmForgot = true })
            if (!canUseBiometrics && state.failedAttempts == 0) Spacer(Modifier.height(18.dp))
        }
    }

    if (confirmForgot) {
        AlertDialog(
            onDismissRequest = { confirmForgot = false },
            title = { Text("¿Has olvidado el PIN?") },
            text = {
                Text(
                    "El PIN no se puede recuperar. Para volver a entrar hay que borrar los datos de este móvil " +
                        "y cerrar la sesión. Si tienes copia en la nube o exportada, podrás recuperarlos después.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForgot = false
                        viewModel.onForgotPin()
                    },
                ) { Text("Borrar datos y entrar", color = colors.expense) }
            },
            dismissButton = { TextButton(onClick = { confirmForgot = false }) { Text("Cancelar") } },
        )
    }
}

/** What covers the app while the lock state is read at start-up, and in the iOS app switcher. */
@Composable
fun LockCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(LocalAppColors.current.bg),
        contentAlignment = Alignment.Center,
    ) {
        AppBadge(AppIcons.wallet)
    }
}

@Composable
private fun AppBadge(icon: ImageVector) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier.size(52.dp).background(colors.brand, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.brandInk, modifier = Modifier.size(26.dp))
    }
}

internal fun biometricIcon(kind: BiometricKind): ImageVector =
    if (kind == BiometricKind.FACE) AppIcons.faceid else AppIcons.finger
