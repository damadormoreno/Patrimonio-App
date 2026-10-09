package com.denebapps.patrimonio.ui.screens.patrimonio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.typeIcon
import com.denebapps.patrimonio.ui.components.typeTone
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel

/** Full-height account-groups list destination, styled like [AddPatrimonioSheet] but rendered as a
 *  normal `composable<Grupos>` entry (design.md Decision: sheets as full pushed destinations). Ports
 *  `design-reference/grupos.jsx`'s `GruposSheet`. Expand/edit/popover states are purely local Compose
 *  state (the JSX component's `expanded`/`editing`/`plusOpen` `useState`s) — [GruposViewModel] only
 *  owns repository-derived data plus the NuevoGrupo form (design.md Decision 1). Tapping or long-pressing
 *  an account of an expanded group, or its pencil while editing, calls [onEditAsset] with its id.
 */
@Composable
fun GruposSheet(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onNewGroup: () -> Unit = {},
    onEditGroup: (String) -> Unit = {},
    onNewAsset: () -> Unit = {},
    onEditAsset: (String) -> Unit = {},
    viewModel: GruposViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = colors.bg,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp, bottom = 6.dp)
                .height(4.dp)
                .width(36.dp)
                .background(color = colors.line, shape = RoundedCornerShape(999.dp)),
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(icon = AppIcons.close, contentDescription = "Cerrar", onClick = onNavigateBack)
                Text(
                    text = "Mis grupos",
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        RoundIconButton(
                            icon = AppIcons.plus,
                            contentDescription = "Añadir",
                            active = plusOpen,
                            onClick = { plusOpen = !plusOpen },
                        )
                        DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Nueva cuenta") },
                                leadingIcon = { Icon(AppIcons.wallet, contentDescription = null) },
                                onClick = {
                                    plusOpen = false
                                    onNewAsset()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Nuevo grupo de cuentas") },
                                leadingIcon = { Icon(AppIcons.folder, contentDescription = null) },
                                onClick = {
                                    plusOpen = false
                                    onNewGroup()
                                },
                            )
                        }
                    }
                    RoundIconButton(
                        icon = AppIcons.pencil,
                        contentDescription = "Editar",
                        active = editing,
                        onClick = { editing = !editing },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.groups.forEach { group ->
                    GrupoRow(
                        group = group,
                        expanded = group.id in expanded,
                        onToggle = {
                            expanded = if (group.id in expanded) expanded - group.id else expanded + group.id
                        },
                        editing = editing,
                        onEdit = { onEditGroup(group.id) },
                        onDelete = { viewModel.onDeleteGroup(group.id) },
                        onMove = { up -> viewModel.onMoveGroup(group.id, up) },
                        onEditMember = onEditAsset,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNewGroup,
                    )
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.plus, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Nuevo grupo de cuentas",
                    color = colors.ink2,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Text(
                text = "Los grupos son vistas: una cuenta puede estar\nen varios grupos a la vez.",
                color = colors.muted2,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 20.dp),
            )
        }
    }

    state.pendingDeletion?.let { pending ->
        DeleteGroupDialog(
            confirmation = pending,
            onConfirm = viewModel::onConfirmDeleteGroup,
            onDismiss = viewModel::onDismissDeleteGroup,
        )
    }
}

/** Confirmation shown before deleting a group that still has linked savings goals. Mirrors the
 *  destructive-confirm styling of the "Borrar todos los datos" dialog in `SettingsScreen`. */
@Composable
private fun DeleteGroupDialog(
    confirmation: GroupDeletionConfirmationUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(deleteGroupDialogTitle(confirmation)) },
        text = { Text(deleteGroupDialogMessage(confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Eliminar", color = colors.expense)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Box(
        modifier = modifier
            .size(36.dp)
            .background(color = if (active) colors.ink else colors.surface, shape = CircleShape)
            .border(width = 1.dp, color = colors.line, shape = CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) colors.bg else colors.ink2,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun GrupoRow(
    group: AccountGroupRowUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    editing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (up: Boolean) -> Unit,
    onEditMember: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val highlight = group.builtin

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (highlight) colors.surface2 else colors.surface,
                shape = RoundedCornerShape(16.dp),
            )
            .border(
                width = if (highlight) 1.5.dp else 1.dp,
                color = if (highlight) colors.ink else colors.line,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = AppIcons.chevronR,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(16.dp).rotate(if (expanded) 90f else 0f),
                )
                Text(
                    text = group.name,
                    color = colors.ink,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (!editing) {
                    Text(
                        text = if (group.showBalance) "+ ${formatMoneyEsGrupos(group.total)}" else "···",
                        color = colors.income,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (editing) {
                Row(
                    modifier = Modifier.padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!group.builtin) {
                        RowActionButton(
                            icon = AppIcons.arrowUp,
                            contentDescription = "Subir ${group.name}",
                            tint = colors.ink2,
                            enabled = group.canMoveUp,
                            onClick = { onMove(true) },
                        )
                        RowActionButton(
                            icon = AppIcons.arrowDown,
                            contentDescription = "Bajar ${group.name}",
                            tint = colors.ink2,
                            enabled = group.canMoveDown,
                            onClick = { onMove(false) },
                        )
                        RowActionButton(
                            icon = AppIcons.pencil,
                            contentDescription = "Editar ${group.name}",
                            tint = colors.ink2,
                            onClick = onEdit,
                        )
                        RowActionButton(
                            icon = AppIcons.trash,
                            contentDescription = "Eliminar ${group.name}",
                            tint = colors.expense,
                            onClick = onDelete,
                        )
                    }
                }
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = colors.line2, shape = RoundedCornerShape(0.dp))
                    .padding(start = 40.dp, end = 14.dp, bottom = 6.dp),
            ) {
                if (group.members.isEmpty()) {
                    Text(
                        text = "Este grupo no tiene cuentas todavía.",
                        color = colors.muted,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    group.members.forEach { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClickLabel = "Editar",
                                    onLongClick = { onEditMember(member.id) },
                                    onClick = { onEditMember(member.id) },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.size(26.dp).background(assetGroupTone(member.group), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = assetGroupIcon(member.group),
                                    contentDescription = null,
                                    tint = colors.surface2,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            Text(
                                text = member.name,
                                color = colors.ink,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                text = formatMoneyEsGrupos(member.amountEur),
                                color = colors.muted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (editing) {
                                RowActionButton(
                                    icon = AppIcons.pencil,
                                    contentDescription = "Editar ${member.name}",
                                    tint = colors.ink2,
                                    onClick = { onEditMember(member.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else LocalAppColors.current.line,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The account's type icon and colour ([typeIcon]/[typeTone]). */
internal fun assetGroupIcon(group: String): ImageVector = typeIcon(group)

@Composable
internal fun assetGroupTone(group: String): Color = typeTone(group)

/** EUR formatting matching [PatrimonioScreen]'s private `formatMoneyEs`, duplicated per-file. */
internal fun formatMoneyEsGrupos(money: com.denebapps.patrimonio.domain.model.Money): String {
    val intPart = money.minorUnits / 100L
    val decPart = (kotlin.math.abs(money.minorUnits) % 100L).toString().padStart(2, '0')
    val grouped = kotlin.math.abs(intPart).toString().reversed().chunked(3).joinToString(".").reversed()
    val sign = if (intPart < 0) "-" else ""
    return "$sign$grouped,$decPart €"
}
