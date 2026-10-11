package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.FormOptionRow
import com.denebapps.patrimonio.ui.components.FormTextField
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** What the portfolio dialogs can do, so they can be shown without a ViewModel. */
internal class PortfolioDialogActions(
    val onNameChange: (String) -> Unit,
    val onTargetChange: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/** The dialog of portfolio management that is open: a name to type, or a delete to confirm. */
@Composable
internal fun PortfolioDialog(dialog: PortfolioDialogUi, actions: PortfolioDialogActions) {
    when (dialog) {
        is PortfolioDialogUi.Name -> NameDialog(dialog, actions)
        is PortfolioDialogUi.Delete -> DeleteDialog(dialog, actions)
    }
}

@Composable
private fun NameDialog(dialog: PortfolioDialogUi.Name, actions: PortfolioDialogActions) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField(value = dialog.name, placeholder = "Ej: Jubilación", onValueChange = actions.onNameChange)
                dialog.error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirm) { Text(dialog.confirmLabel, color = colors.brand) }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun DeleteDialog(dialog: PortfolioDialogUi.Delete, actions: PortfolioDialogActions) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dialog.message)
                if (dialog.targets.isNotEmpty()) {
                    Column(
                        modifier = Modifier.heightIn(max = TARGETS_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dialog.targets.forEach { target ->
                            FormOptionRow(
                                label = target.name,
                                selected = target.id == dialog.targetId,
                                onClick = { actions.onTargetChange(target.id) },
                            )
                        }
                    }
                }
                dialog.error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirm) { Text(dialog.confirmLabel, color = colors.expense) }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, color = LocalAppColors.current.expense, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
}

private val TARGETS_MAX_HEIGHT = 240.dp
