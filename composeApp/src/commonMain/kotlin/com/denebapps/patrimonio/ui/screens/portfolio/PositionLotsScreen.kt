package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Pushed screen (`PositionLots`) with every open lot of one holding, oldest first, and the note about the ones that
 * came in a transfer. [onBack] returns to the position, also when the holding is deleted while this is open.
 */
@Composable
fun PositionLotsScreen(
    holdingId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: PositionLotsViewModel = koinViewModel(parameters = { parametersOf(holdingId) }),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }
    PositionLotsContent(state = state, onBack = onBack, modifier = modifier)
}

@Composable
internal fun PositionLotsContent(state: PositionLotsUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    PositionListScreen(title = LOTS_TITLE, subtitle = state.holdingName, onBack = onBack, modifier = modifier) {
        if (!state.loaded) return@PositionListScreen
        if (state.lots.isEmpty()) {
            EmptyListText(state.emptyText.orEmpty())
        } else {
            LotsCard(state.lots)
            state.note?.let { CardNote(it) }
        }
    }
}
