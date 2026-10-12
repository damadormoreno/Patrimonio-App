package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.Divider
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** What the movements section can do, so it can be shown without a ViewModel. */
internal class MovementsCallbacks(
    val onAdd: () -> Unit,
    val onTransfer: () -> Unit,
    val onOpen: (String) -> Unit,
)

/**
 * "Movimientos" of the position detail: the list newest first, or the note that the units are edited by hand, with
 * the buttons to add a movement and (for a fund that can) to transfer. Nothing for a manual position.
 */
@Composable
internal fun MovementsSection(movements: MovementsUi, callbacks: MovementsCallbacks) {
    if (!movements.visible) return
    SectionLabel("Movimientos")
    DetailCard(horizontalPadding = 16) {
        if (movements.rows.isEmpty()) {
            Text(
                text = movements.emptyText.orEmpty(),
                color = LocalAppColors.current.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            )
        } else {
            movements.rows.forEachIndexed { index, row ->
                MovementRow(
                    row = row,
                    showDivider = index != movements.rows.lastIndex,
                    onClick = { callbacks.onOpen(row.id) },
                )
            }
        }
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(text = "Añadir movimiento", onClick = callbacks.onAdd, leading = AppIcons.plus)
        if (movements.canTransfer) {
            GhostButton(text = "Traspasar", onClick = callbacks.onTransfer)
        }
    }
}

@Composable
private fun MovementRow(row: MovementRowUi, showDivider: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Editar movimiento", onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.dateText,
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                row.noteText?.let {
                    Text(
                        text = it,
                        color = colors.muted2,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                row.unitsText?.let {
                    Text(text = it, color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                row.amountText?.let {
                    Text(
                        text = it,
                        color = if (it == UNKNOWN_COST_TEXT) colors.muted2 else colors.ink2,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
        if (showDivider) Divider()
    }
}

/**
 * The question asked when the first movement is added to a position that already has units: the two ways to start,
 * and, for the second, what happens to the units before going on.
 */
@Composable
internal fun OpeningChoiceDialog(
    choice: OpeningChoiceUi,
    onOpeningBalance: () -> Unit,
    onOneByOne: () -> Unit,
    onConfirmOneByOne: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    when (choice.step) {
        OpeningChoiceStep.CHOOSE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Añadir movimiento") },
            text = { Text("Esta posición ya tiene participaciones. ¿Cómo quieres empezar su historial?") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onOpeningBalance) { Text(choice.openingLabel, color = colors.brand) }
                    TextButton(onClick = onOneByOne) { Text(choice.oneByOneLabel, color = colors.brand) }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        )
        OpeningChoiceStep.WARN_ONE_BY_ONE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(choice.oneByOneLabel) },
            text = { Text(choice.warning.orEmpty()) },
            confirmButton = { TextButton(onClick = onConfirmOneByOne) { Text("Continuar", color = colors.brand) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        )
    }
}
