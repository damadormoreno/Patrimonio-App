package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import org.koin.compose.viewmodel.koinViewModel

private const val NO_KEY_SUBTITLE = "Opcional · precios con licencia para fondos, ETF y acciones"
private const val FALLBACK_MODE_SUBTITLE = "EODHD solo se usa si falla la fuente habitual (ahorra llamadas)"
private const val PRIMARY_MODE_SUBTITLE = "Se consulta EODHD primero; recomendable con un plan de pago"

/**
 * Ajustes → Fuentes de datos: the user's own EODHD key (optional), whether EODHD is the primary price source or only a
 * fallback, and how much of the daily quota is used.
 */
@Composable
internal fun DataSourcesSection(viewModel: DataSourcesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    SettingsSection(title = "Fuentes de datos", modifier = Modifier.padding(top = 12.dp))
    SettingsCard {
        SettingsRow(
            title = "Clave de EODHD",
            subtitle = keySubtitle(state),
            leading = { SettingsIcon(AppIcons.globe) },
            trailing = {
                Icon(
                    imageVector = AppIcons.chevronR,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = viewModel::onAddKey,
        )
        state.notice?.let { notice ->
            Text(
                text = notice.message,
                color = if (notice == DataSourceNotice.INVALID_KEY) colors.expense else colors.alert,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        SettingsRow(
            title = "Usar EODHD como fuente principal",
            subtitle = if (state.eodhdPrimary) PRIMARY_MODE_SUBTITLE else FALLBACK_MODE_SUBTITLE,
            leading = { SettingsIcon(AppIcons.trend) },
            trailing = {
                Switch(
                    checked = state.eodhdPrimary,
                    onCheckedChange = viewModel::onPrimaryChange,
                    enabled = state.hasKey,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.brand),
                )
            },
            onClick = if (state.hasKey) ({ viewModel.onPrimaryChange(!state.eodhdPrimary) }) else null,
            showDivider = state.hasKey,
        )
        if (state.hasKey) {
            SettingsRow(
                title = "Quitar clave",
                leading = { SettingsIcon(AppIcons.trash, danger = true) },
                onClick = viewModel::onRemoveRequest,
                showDivider = false,
                danger = true,
            )
        }
    }

    state.entry?.let { entry ->
        KeyEntryDialog(
            entry = entry,
            onDraftChange = viewModel::onDraftChange,
            onSave = viewModel::onSave,
            onDismiss = viewModel::onEntryDismiss,
        )
    }
    if (state.confirmingRemoval) {
        AlertDialog(
            onDismissRequest = viewModel::onRemoveDismiss,
            title = { Text("Quitar clave de EODHD") },
            text = {
                Text(
                    "Se borrará la clave de este móvil. Los precios se obtendrán solo de las fuentes habituales.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onRemoveConfirm) { Text("Quitar", color = colors.expense) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onRemoveDismiss) { Text("Cancelar") }
            },
        )
    }
}

private fun keySubtitle(state: DataSourcesUiState): String =
    listOfNotNull(state.maskedKey ?: NO_KEY_SUBTITLE, state.usage).joinToString("\n")

@Composable
private fun KeyEntryDialog(
    entry: KeyEntry,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clave de EODHD") },
        text = {
            Column {
                Text(
                    "EODHD es un proveedor de datos financieros con licencia. Necesitas tu propia clave, que se " +
                        "obtiene en eodhd.com. Se guarda solo en este móvil (no entra en las copias de seguridad " +
                        "ni en la copia en la nube) y solo se envía a EODHD.",
                )
                ApiKeyField(
                    value = entry.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.padding(top = 16.dp),
                )
                entry.error?.let { error ->
                    Text(
                        text = error,
                        color = colors.expense,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = entry.draft.isNotBlank() && !entry.checking) {
                Text(if (entry.checking) "Comprobando…" else "Comprobar y guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/**
 * The key field: masked until the eye is tapped, single line, no suggestions or autocorrect (so the key never reaches
 * the keyboard's dictionary), same look as the app's text fields.
 */
@Composable
private fun ApiKeyField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.md)
    var visible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colors.surface, shape = shape)
            .border(width = 1.dp, color = colors.line, shape = shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CLAVE DE API",
                    color = colors.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.04.sp,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = TextStyle(color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Normal),
                    cursorBrush = SolidColor(colors.ink),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            Icon(
                imageVector = if (visible) AppIcons.eyeOff else AppIcons.eye,
                contentDescription = if (visible) "Ocultar clave" else "Mostrar clave",
                tint = colors.muted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { visible = !visible },
                    ),
            )
        }
    }
}
