package com.denebapps.patrimonio.ui.screens.perfil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import org.koin.compose.viewmodel.koinViewModel

/**
 * Perfil route (pushed from the Settings profile card). Ports `design-reference/perfil.jsx`: avatar with
 * the profile photo (picked from the gallery, or the Google one) or the initials, inline-edit
 * Nombre/Apellidos autosaved on focus loss, NO Correo field, NO save button.
 */
@Composable
fun PerfilScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ProfileViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val photoPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        file?.let { viewModel.onPhotoPicked { it.readBytes() } }
    }
    PerfilContent(
        state = state,
        onBack = onBack,
        onSaveFirstName = viewModel::saveFirstName,
        onSaveLastName = viewModel::saveLastName,
        onChangePhoto = photoPicker::launch,
        onRemovePhoto = viewModel::onRemovePhoto,
        modifier = modifier,
    )
}

@Composable
private fun PerfilContent(
    state: ProfileUiState,
    onBack: () -> Unit,
    onSaveFirstName: (String) -> Unit,
    onSaveLastName: (String) -> Unit,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val displayName = listOf(state.firstName.trim(), state.lastName.trim())
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .ifEmpty { "Tu perfil" }

    Column(modifier = modifier.fillMaxSize().background(colors.bg)) {
        // In-screen back header — the scaffold topBar is empty on this route (navigation-shell).
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Ajustes")
            Text(
                text = "Perfil",
                color = colors.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.02).sp,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfileAvatar(initials = state.initials, size = 92.dp)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PhotoAction(text = "Cambiar foto", onClick = onChangePhoto)
                    if (state.hasPhoto) PhotoAction(text = "Quitar foto", onClick = onRemovePhoto)
                }
                state.photoError?.let { error ->
                    Text(text = error, color = colors.expense, fontSize = 12.sp)
                }
                Text(
                    text = displayName,
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "Cuenta local · offline",
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            SettingsSection(title = "Datos personales", modifier = Modifier.padding(top = 12.dp))
            SettingsCard {
                ProfileField(
                    label = "Nombre",
                    value = state.firstName,
                    placeholder = "Nombre",
                    onSave = onSaveFirstName,
                )
                ProfileField(
                    label = "Apellidos",
                    value = state.lastName,
                    placeholder = "Apellidos",
                    onSave = onSaveLastName,
                    showDivider = false,
                )
            }
            Text(
                text = "Los cambios se guardan automáticamente.",
                color = colors.muted2,
                fontSize = 11.sp,
                letterSpacing = 0.04.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PhotoAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = LocalAppColors.current.brand,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * Inline-edit profile row (port of `perfil.jsx`'s `ProfileField`): fixed-width muted label plus a
 * borderless input. The draft is local (keyed on the persisted [value] so external updates reset
 * it) and autosaved EXACTLY once when the field loses focus with changes (design.md D8).
 */
@Composable
private fun ProfileField(
    label: String,
    value: String,
    onSave: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val colors = LocalAppColors.current
    val lineColor = colors.line
    var draft by remember(value) { mutableStateOf(value) }
    // Leaving the screen with the field still focused is ALSO a focus loss from the user's
    // perspective, but `onFocusChanged` does NOT fire on disposal (confirmed on emulator: the
    // dirty value was lost on navigate-away). Flush the pending draft once at disposal — the
    // draft/value guard keeps this from double-saving after a normal focus-loss save.
    val latestDraft by rememberUpdatedState(draft)
    val latestValue by rememberUpdatedState(value)
    DisposableEffect(Unit) {
        onDispose {
            if (latestDraft != latestValue) onSave(latestDraft)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .then(
                if (showDivider) {
                    Modifier.drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(16f, size.height),
                            end = Offset(size.width - 16f, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = colors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(84.dp),
        )
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = TextStyle(
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.005).sp,
            ),
            cursorBrush = SolidColor(colors.ink),
            decorationBox = { innerTextField ->
                Box {
                    if (draft.isEmpty()) {
                        Text(text = placeholder, color = colors.muted2, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused && draft != value) onSave(draft)
                },
        )
    }
}
