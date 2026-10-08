package com.denebapps.patrimonio.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.CustomAccountType
import com.denebapps.patrimonio.ui.components.SettingsCard
import com.denebapps.patrimonio.ui.components.SettingsRow
import com.denebapps.patrimonio.ui.components.SettingsSection
import com.denebapps.patrimonio.ui.components.TypeBadge
import com.denebapps.patrimonio.ui.components.TypeEditorDialog
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel

/** Ajustes → Tipos de cuenta: a row that opens the list of the user's own types. */
@Composable
internal fun AccountTypesSection(viewModel: AccountTypesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    val count = state.assetTypes.size + state.liabilityTypes.size

    SettingsSection(title = "Tipos de cuenta", modifier = Modifier.padding(top = 12.dp))
    SettingsCard {
        SettingsRow(
            title = "Tus tipos",
            subtitle = when (count) {
                0 -> "Crea los tuyos, con su emoji y su color"
                1 -> "1 tipo propio"
                else -> "$count tipos propios"
            },
            leading = { SettingsIcon(AppIcons.tag) },
            trailing = {
                Icon(AppIcons.chevronR, contentDescription = null, tint = LocalAppColors.current.muted)
            },
            onClick = { open = true },
            showDivider = false,
        )
    }
    if (open) AccountTypesDialog(state, viewModel, onDismiss = { open = false })
}

/** What is being edited: a new type of [kind], or the existing [type]. */
private sealed interface TypeEdit {
    data class New(val kind: AccountKind) : TypeEdit

    data class Existing(val type: CustomAccountType) : TypeEdit
}

@Composable
private fun AccountTypesDialog(state: AccountTypesUiState, viewModel: AccountTypesViewModel, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    var editing by remember { mutableStateOf<TypeEdit?>(null) }
    var deleting by remember { mutableStateOf<CustomTypeRowUi?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.bg).statusBarsPadding().navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Tipos de cuenta",
                    color = colors.ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Hecho", color = colors.ink2) }
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                Text(
                    "Los tipos de la app no se pueden cambiar. Los tuyos aparecen detrás de ellos al crear una cuenta.",
                    color = colors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TypeList(
                    title = "Activos",
                    rows = state.assetTypes,
                    onNew = { editing = TypeEdit.New(AccountKind.ASSET) },
                    onEdit = { editing = TypeEdit.Existing(it.type) },
                    onDelete = { deleting = it },
                )
                TypeList(
                    title = "Pasivos",
                    rows = state.liabilityTypes,
                    onNew = { editing = TypeEdit.New(AccountKind.LIABILITY) },
                    onEdit = { editing = TypeEdit.Existing(it.type) },
                    onDelete = { deleting = it },
                )
            }
        }
    }

    when (val edit = editing) {
        is TypeEdit.New -> TypeEditorDialog(
            title = if (edit.kind == AccountKind.ASSET) "Nuevo tipo de activo" else "Nuevo tipo de pasivo",
            onSave = { name, emoji, color ->
                editing = null
                viewModel.onCreate(edit.kind, name, emoji, color)
            },
            onDismiss = { editing = null },
        )
        is TypeEdit.Existing -> TypeEditorDialog(
            title = "Editar tipo",
            initialName = edit.type.name,
            initialEmoji = edit.type.emoji,
            initialColor = edit.type.color,
            onSave = { name, emoji, color ->
                editing = null
                viewModel.onUpdate(edit.type.id, name, emoji, color)
            },
            onDismiss = { editing = null },
        )
        null -> Unit
    }

    deleting?.let { row ->
        val other = if (row.type.kind == AccountKind.ASSET) "Otros" else "Otras deudas"
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar «${row.type.name}»?") },
            text = {
                Text(
                    when (row.accountCount) {
                        0 -> "Ninguna cuenta lo usa."
                        1 -> "La cuenta que lo usa pasará a «$other»."
                        else -> "Las ${row.accountCount} cuentas que lo usan pasarán a «$other»."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        viewModel.onDelete(row.type.id)
                    },
                ) { Text("Borrar", color = colors.expense) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TypeList(
    title: String,
    rows: List<CustomTypeRowUi>,
    onNew: () -> Unit,
    onEdit: (CustomTypeRowUi) -> Unit,
    onDelete: (CustomTypeRowUi) -> Unit,
) {
    val colors = LocalAppColors.current
    SettingsSection(title = title, modifier = Modifier.padding(top = 18.dp))
    SettingsCard {
        rows.forEach { row ->
            SettingsRow(
                title = row.type.name,
                subtitle = when (row.accountCount) {
                    0 -> "Sin cuentas"
                    1 -> "1 cuenta"
                    else -> "${row.accountCount} cuentas"
                },
                leading = { TypeBadge(row.look, size = 30.dp, cornerRadius = 8.dp) },
                trailing = {
                    Icon(
                        AppIcons.trash,
                        contentDescription = "Borrar ${row.type.name}",
                        tint = colors.muted,
                        modifier = Modifier.size(20.dp).clickable { onDelete(row) },
                    )
                },
                onClick = { onEdit(row) },
            )
        }
        SettingsRow(
            title = "Nuevo tipo",
            leading = { SettingsIcon(AppIcons.plus) },
            onClick = onNew,
            showDivider = false,
        )
    }
    if (rows.isEmpty()) {
        Text(
            text = "Aún no tienes tipos propios de ${title.lowercase()}.",
            color = colors.muted2,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}
