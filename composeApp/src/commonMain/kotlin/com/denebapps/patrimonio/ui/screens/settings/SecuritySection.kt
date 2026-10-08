package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.denebapps.patrimonio.domain.repository.AutoLockDelay
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.screens.lock.PinEntry
import com.denebapps.patrimonio.ui.screens.lock.biometricIcon
import com.denebapps.patrimonio.ui.screens.lock.rememberBiometricAuthenticator
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Ajustes → Seguridad: PIN lock, biometrics (where the phone has them) and how soon it locks again. */
@Composable
internal fun SecuritySection(viewModel: SecurityViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current
    val biometric = rememberBiometricAuthenticator()
    val scope = rememberCoroutineScope()
    val pinSet = state.settings.pinSet

    SettingsSection(title = "Seguridad", modifier = Modifier.padding(top = 12.dp))
    SettingsCard {
        SettingsRow(
            title = "Bloqueo con PIN",
            subtitle = "Pedir un PIN de 4 cifras al abrir la app",
            leading = { SettingsIcon(AppIcons.lock) },
            trailing = {
                Switch(
                    checked = pinSet,
                    onCheckedChange = viewModel::onLockToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.brand),
                )
            },
            onClick = { viewModel.onLockToggle(!pinSet) },
            showDivider = pinSet,
        )
        if (pinSet) {
            if (biometric != null) {
                val enable: (Boolean) -> Unit = { enabled ->
                    if (enabled) {
                        scope.launch {
                            if (biometric.authenticate("Activa el desbloqueo con ${biometric.label}")) {
                                viewModel.onBiometricsChange(true)
                            }
                        }
                    } else {
                        viewModel.onBiometricsChange(false)
                    }
                }
                SettingsRow(
                    title = "Desbloqueo con ${biometric.label}",
                    subtitle = "En lugar del PIN",
                    leading = { SettingsIcon(biometricIcon(biometric.kind)) },
                    trailing = {
                        Switch(
                            checked = state.settings.biometrics,
                            onCheckedChange = enable,
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.brand),
                        )
                    },
                    onClick = { enable(!state.settings.biometrics) },
                )
            }
            SettingsRow(title = "Cambiar PIN", onClick = viewModel::onChangePin)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(text = "Bloqueo automático", color = colors.ink, fontSize = 15.sp)
                Text(
                    text = "Tiempo fuera de la app antes de pedir el PIN otra vez",
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                SegmentedPicker(
                    options = AutoLockDelay.entries.map { it to autoLockLabel(it) },
                    selected = state.settings.autoLockDelay,
                    onSelect = viewModel::onAutoLockDelaySelect,
                )
            }
        }
    }

    state.pinFlow?.let { flow ->
        PinFlowDialog(flow = flow, onPinEntered = viewModel::onPinEntered, onDismiss = viewModel::onPinFlowDismiss)
    }
}

private fun autoLockLabel(delay: AutoLockDelay): String = when (delay) {
    AutoLockDelay.HALF_MINUTE -> "30 s"
    AutoLockDelay.ONE_MINUTE -> "1 min"
    AutoLockDelay.FIVE_MINUTES -> "5 min"
}

/** Full screen, like the lock itself: type the current PIN, choose the new one, repeat it. */
@Composable
private fun PinFlowDialog(flow: PinFlow, onPinEntered: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.bg).statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancelar", color = colors.ink2) }
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = when (flow.step) {
                        PinFlowStep.CURRENT -> "Introduce tu PIN actual"
                        PinFlowStep.NEW -> "Elige un PIN de 4 cifras"
                        PinFlowStep.CONFIRM -> "Repite el PIN"
                    },
                    color = colors.ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = flow.error ?: when {
                        flow.step == PinFlowStep.CURRENT && flow.mode == PinFlowMode.REMOVE -> "Para quitar el bloqueo"
                        flow.step == PinFlowStep.NEW -> "Te lo pediremos al abrir la app"
                        else -> " "
                    },
                    color = if (flow.error != null) colors.expense else colors.muted,
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // A new step starts with empty dots.
            key(flow.step) {
                PinEntry(onPinComplete = onPinEntered, wrongPinCount = flow.wrongPinCount)
            }
            Spacer(Modifier.padding(bottom = 40.dp))
        }
    }
}
