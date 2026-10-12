package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.icons.AppIcons
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal const val MOVEMENTS_TITLE = "Movimientos"

/**
 * Pushed screen (`PositionMovements`) with every movement of one holding, by year, newest first. A row opens the sheet
 * that edits it ([onOpenMovement], or [onOpenTransfer] for a transfer leg) and "Añadir movimiento" the form of a new
 * one; [onBack] returns to the position, also when the holding is deleted while this is open.
 */
@Composable
fun PositionMovementsScreen(
    holdingId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenMovement: (movementId: String?, openingBalance: Boolean) -> Unit = { _, _ -> },
    onOpenTransfer: (transferId: String?) -> Unit = {},
    viewModel: PositionMovementsViewModel = koinViewModel(parameters = { parametersOf(holdingId) }),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigate.collect { target ->
            when (target) {
                is PositionNavigation.MovementForm -> onOpenMovement(target.movementId, target.openingBalance)
                is PositionNavigation.Transfer -> onOpenTransfer(target.transferId)
            }
        }
    }

    state.choice?.let {
        OpeningChoiceDialog(
            choice = it,
            onOpeningBalance = viewModel::onChooseOpeningBalance,
            onOneByOne = viewModel::onChooseOneByOne,
            onConfirmOneByOne = viewModel::onConfirmOneByOne,
            onDismiss = viewModel::onDismissChoice,
        )
    }
    PositionMovementsContent(
        state = state,
        onBack = onBack,
        onAdd = viewModel::onAddMovement,
        onOpen = viewModel::onOpenMovement,
        modifier = modifier,
    )
}

@Composable
internal fun PositionMovementsContent(
    state: PositionMovementsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PositionListScreen(title = MOVEMENTS_TITLE, subtitle = state.holdingName, onBack = onBack, modifier = modifier) {
        if (!state.loaded) return@PositionListScreen
        if (state.groups.isEmpty()) {
            EmptyListText(state.emptyText.orEmpty())
        }
        state.groups.forEach { group ->
            SectionLabel(group.year.toString())
            DetailCard(horizontalPadding = 16) {
                group.rows.forEachIndexed { index, row ->
                    MovementRow(row = row, showDivider = index != group.rows.lastIndex, onClick = { onOpen(row.id) })
                }
            }
        }
        if (state.canAdd) {
            GhostButton(
                text = "Añadir movimiento",
                onClick = onAdd,
                leading = AppIcons.plus,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}
