package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.repository.CloudBackupState
import com.denebapps.patrimonio.domain.repository.RenewalReminderSettings
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.resources.Res
import com.denebapps.patrimonio.resources.delete_data_failure_message
import com.denebapps.patrimonio.ui.components.ScreenHeader
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.screens.account.AccountViewModel
import com.denebapps.patrimonio.ui.screens.perfil.ProfileAvatar
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAccount: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
    backupViewModel: BackupViewModel = koinViewModel(),
    accountViewModel: AccountViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val backupStatus by backupViewModel.status.collectAsState()
    val accountState by accountViewModel.state.collectAsState()

    // FileKit hands back a platform file handle (SAF Uri / NSURL); the ViewModel only ever sees
    // the read/write lambdas. A null result means the user cancelled the dialog.
    val exportLauncher = rememberFileSaverLauncher { file ->
        file?.let { destination -> backupViewModel.onExportDestinationChosen { json -> destination.writeString(json) } }
    }
    // No extension filter: some Android document providers report .json as octet-stream and
    // would grey the file out. The content is validated on import anyway.
    val importLauncher = rememberFilePickerLauncher { file ->
        file?.let { source -> backupViewModel.onImportFileChosen { source.readString() } }
    }
    var showNotificationsBlocked by rememberSaveable { mutableStateOf(false) }
    val requestNotifications = rememberNotificationPermissionRequest { granted ->
        if (granted) viewModel.onRemindersEnabledChange(true) else showNotificationsBlocked = true
    }

    SettingsContent(
        state = state,
        backupStatus = backupStatus,
        accountEmail = accountState.user?.email,
        accountCloud = accountState.cloud,
        onOpenProfile = onOpenProfile,
        onOpenAccount = onOpenAccount,
        onThemeModeSelect = viewModel::onThemeModeSelect,
        onRemindersToggle = { enabled ->
            if (enabled) requestNotifications() else viewModel.onRemindersEnabledChange(false)
        },
        onReminderLeadDaysSelect = viewModel::onReminderLeadDaysSelect,
        onExport = { exportLauncher.launch(suggestedName = backupViewModel.suggestedFileName(), extension = "json") },
        onConfirmImport = { importLauncher.launch() },
        onBackupResultConsumed = backupViewModel::onResultConsumed,
        onConfirmDeleteAll = viewModel::onConfirmDeleteAll,
        onClearDataResultConsumed = viewModel::onClearDataResultConsumed,
        modifier = modifier,
    )

    if (showNotificationsBlocked) {
        AlertDialog(
            onDismissRequest = { showNotificationsBlocked = false },
            title = { Text("Notificaciones desactivadas") },
            text = {
                Text("Para recibir los avisos, permite las notificaciones de la app en los ajustes del sistema.")
            },
            confirmButton = {
                TextButton(onClick = { showNotificationsBlocked = false }) { Text("Aceptar") }
            },
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    backupStatus: BackupStatus,
    accountEmail: String?,
    accountCloud: CloudBackupState,
    onOpenProfile: () -> Unit,
    onOpenAccount: () -> Unit,
    onThemeModeSelect: (ThemeMode) -> Unit,
    onRemindersToggle: (Boolean) -> Unit,
    onReminderLeadDaysSelect: (Int) -> Unit,
    onExport: () -> Unit,
    onConfirmImport: () -> Unit,
    onBackupResultConsumed: () -> Unit,
    onConfirmDeleteAll: () -> Unit,
    onClearDataResultConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    val backupBusy = backupStatus == BackupStatus.InProgress

    LaunchedEffect(state.clearDataStatus) {
        if (state.clearDataStatus == ClearDataStatus.SUCCEEDED) {
            showDeleteDialog = false
            onClearDataResultConsumed()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(colors.bg),
    ) {
        ScreenHeader(title = "Ajustes")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            ProfileCard(state, accountEmail, onOpenProfile)
            SettingsSection(title = "Cuenta", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                SettingsRow(
                    title = accountEmail ?: "Iniciar sesión o crear cuenta",
                    subtitle = if (accountEmail != null) {
                        accountSubtitle(accountCloud)
                    } else {
                        "Opcional · para guardar tus datos en la nube"
                    },
                    leading = { SettingsIcon(AppIcons.cloud) },
                    trailing = {
                        Icon(
                            imageVector = AppIcons.chevronR,
                            contentDescription = null,
                            tint = colors.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = onOpenAccount,
                    showDivider = false,
                )
            }
            SettingsSection(title = "Apariencia", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                ThemePicker(selected = state.themeMode, onSelect = onThemeModeSelect)
            }
            SettingsSection(title = "Avisos", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                RenewalReminders(
                    settings = state.reminders,
                    onToggle = onRemindersToggle,
                    onLeadDaysSelect = onReminderLeadDaysSelect,
                )
            }
            SecuritySection()
            AccountTypesSection()
            SettingsSection(title = "Datos", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                SettingsRow(
                    title = "Exportar copia",
                    subtitle = "Guarda todos tus datos en un archivo JSON",
                    leading = { SettingsIcon(AppIcons.download) },
                    onClick = { if (!backupBusy) onExport() },
                )
                SettingsRow(
                    title = "Importar copia",
                    subtitle = "Reemplaza tus datos por los de un archivo",
                    leading = { SettingsIcon(AppIcons.upload) },
                    onClick = { if (!backupBusy) showImportDialog = true },
                )
                SettingsRow(
                    title = "Borrar todos los datos",
                    leading = { SettingsIcon(AppIcons.trash, danger = true) },
                    onClick = { showDeleteDialog = true },
                    showDivider = false,
                    danger = true,
                )
            }
            SettingsSection(title = "Información", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                SettingsRow(
                    title = "Versión",
                    leading = { SettingsIcon(AppIcons.info) },
                    trailing = {
                        Text(text = state.versionName, color = colors.muted, fontSize = 13.sp)
                    },
                    showDivider = false,
                )
            }
            Text(
                text = "Tus datos viven solo en este dispositivo. Exporta una copia de vez en cuando.",
                color = colors.muted2,
                fontSize = 11.sp,
                letterSpacing = 0.04.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Importar copia") },
            text = {
                Text(
                    "Todos tus datos actuales se reemplazarán por los del archivo. " +
                        "Si no tienes una copia de lo que hay ahora, expórtala antes.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        onConfirmImport()
                    },
                ) {
                    Text("Elegir archivo", color = colors.expense)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancelar")
                }
            },
        )
    }

    BackupResultDialog(status = backupStatus, onDismiss = onBackupResultConsumed)

    if (showDeleteDialog) {
        val clearingInProgress = state.clearDataStatus == ClearDataStatus.IN_PROGRESS
        val dismissDialog = {
            if (!clearingInProgress) {
                showDeleteDialog = false
                if (state.clearDataStatus == ClearDataStatus.FAILED) onClearDataResultConsumed()
            }
        }
        AlertDialog(
            onDismissRequest = dismissDialog,
            title = { Text("Borrar todos los datos") },
            text = {
                Column {
                    Text(
                        "Se eliminarán permanentemente todos tus datos financieros. " +
                            "Tu perfil y tus preferencias se conservarán.",
                    )
                    if (state.clearDataStatus == ClearDataStatus.FAILED) {
                        Text(
                            text = stringResource(Res.string.delete_data_failure_message),
                            color = colors.expense,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmDeleteAll,
                    enabled = !clearingInProgress,
                ) {
                    Text("Borrar", color = colors.expense)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = dismissDialog,
                    enabled = !clearingInProgress,
                ) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun BackupResultDialog(status: BackupStatus, onDismiss: () -> Unit) {
    val (title, message) = when (status) {
        BackupStatus.Idle, BackupStatus.InProgress -> return
        BackupStatus.Exported -> "Copia exportada" to "Guarda el archivo en un sitio seguro (Drive, iCloud Drive…)."
        BackupStatus.Imported -> "Copia importada" to "Tus datos se han restaurado desde el archivo."
        is BackupStatus.Failed -> "Algo ha fallado" to status.message
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Aceptar") }
        },
    )
}

@Composable
private fun ProfileCard(state: SettingsUiState, accountEmail: String?, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.line, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProfileAvatar(initials = state.profileInitials, size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.profileName ?: "Tu perfil",
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = accountEmail ?: "Cuenta local · offline",
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = AppIcons.chevronR,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            SettingsIcon(if (selected == ThemeMode.DARK) AppIcons.moon else AppIcons.sun)
            Text(text = "Tema", color = LocalAppColors.current.ink, fontSize = 15.sp)
        }
        ThemeSegments(selected = selected, onSelect = onSelect)
    }
}

@Composable
private fun RenewalReminders(
    settings: RenewalReminderSettings,
    onToggle: (Boolean) -> Unit,
    onLeadDaysSelect: (Int) -> Unit,
) {
    val colors = LocalAppColors.current
    SettingsRow(
        title = "Avisos de renovación",
        subtitle = "Una notificación antes de cada cargo",
        leading = { SettingsIcon(AppIcons.bell) },
        trailing = {
            Switch(
                checked = settings.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.brand),
            )
        },
        onClick = { onToggle(!settings.enabled) },
        showDivider = settings.enabled,
    )
    if (settings.enabled) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "Antelación",
                color = colors.ink,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SegmentedPicker(
                options = RenewalReminderSettings.LEAD_DAY_OPTIONS.map { it to leadDaysLabel(it) },
                selected = settings.leadDays,
                onSelect = onLeadDaysSelect,
            )
        }
    }
}

private fun leadDaysLabel(leadDays: Int): String = when (leadDays) {
    0 -> "Mismo día"
    1 -> "1 día"
    7 -> "1 semana"
    else -> "$leadDays días"
}

@Composable
private fun ThemeSegments(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SegmentedPicker(
        options = listOf(
            ThemeMode.LIGHT to "Claro",
            ThemeMode.DARK to "Oscuro",
            ThemeMode.SYSTEM to "Sistema",
        ),
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
internal fun <T> SegmentedPicker(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bg2, shape)
            .border(1.dp, colors.line2, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (option, label) ->
            val selectedOption = option == selected
            Text(
                text = label,
                color = if (selectedOption) colors.ink else colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedOption) colors.surface2 else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false) {
    val colors = LocalAppColors.current
    val radius = LocalAppShapes.current.sm
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(if (danger) colors.expenseSoft else colors.bg2, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) colors.expense else colors.ink2,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun accountSubtitle(cloud: CloudBackupState): String = when (cloud) {
    is CloudBackupState.Conflict -> "Elige qué datos usar"
    is CloudBackupState.Active ->
        if (cloud.failure != null) "No se pudo guardar la última copia" else "Copia en la nube activada"
    is CloudBackupState.CheckFailed -> "La copia en la nube no está al día"
    is CloudBackupState.NeedsPassphrase ->
        if (cloud.unlock) "Escribe tu frase de cifrado" else "Crea una frase para activar la copia"
    else -> "Sesión iniciada"
}
