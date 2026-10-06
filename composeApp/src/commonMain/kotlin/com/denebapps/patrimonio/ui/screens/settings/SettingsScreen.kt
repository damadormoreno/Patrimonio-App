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
import com.denebapps.patrimonio.domain.repository.ThemeMode
import com.denebapps.patrimonio.ui.components.Avatar
import com.denebapps.patrimonio.ui.components.ScreenHeader
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import com.denebapps.patrimonio.resources.Res
import com.denebapps.patrimonio.resources.delete_data_failure_message
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        onOpenProfile = onOpenProfile,
        onThemeModeSelect = viewModel::onThemeModeSelect,
        onConfirmDeleteAll = viewModel::onConfirmDeleteAll,
        onClearDataResultConsumed = viewModel::onClearDataResultConsumed,
        modifier = modifier,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onOpenProfile: () -> Unit,
    onThemeModeSelect: (ThemeMode) -> Unit,
    onConfirmDeleteAll: () -> Unit,
    onClearDataResultConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

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
            ProfileCard(state, onOpenProfile)
            SettingsSection(title = "Apariencia", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                ThemePicker(selected = state.themeMode, onSelect = onThemeModeSelect)
            }
            SettingsSection(title = "Datos", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
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
                text = "Tus datos viven solo en este dispositivo.",
                color = colors.muted2,
                fontSize = 11.sp,
                letterSpacing = 0.04.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

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
private fun ProfileCard(state: SettingsUiState, onClick: () -> Unit) {
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
        Avatar(
            initials = state.profileInitials,
            icon = AppIcons.user.takeIf { state.profileInitials == null },
            size = 48.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.profileName ?: "Tu perfil",
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Cuenta local · offline",
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
private fun ThemeSegments(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)
    val options = listOf(
        ThemeMode.LIGHT to "Claro",
        ThemeMode.DARK to "Oscuro",
        ThemeMode.SYSTEM to "Sistema",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bg2, shape)
            .border(1.dp, colors.line2, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (mode, label) ->
            val selectedOption = mode == selected
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
                        onClick = { onSelect(mode) },
                    )
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false) {
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
