package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.FormSheetGrabber
import com.denebapps.patrimonio.ui.components.FormSheetTopBar
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Full-height destination (`HoldingDetail`) for one portfolio holding: what it is, what it is worth and at which
 * price, the units (or value, for a manual holding) to edit, and a confirmed delete. Pops the back stack once
 * [HoldingDetailViewModel.navigateBack] emits.
 */
@Composable
fun HoldingDetailSheet(
    holdingId: String,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: HoldingDetailViewModel = koinViewModel(parameters = { parametersOf(holdingId) }),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    HoldingDetailContent(
        state = state,
        onEditChange = viewModel::onEditChange,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        onCancel = onNavigateBack,
        modifier = modifier,
    )
}

@Composable
internal fun HoldingDetailContent(
    state: HoldingDetailUiState,
    onEditChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = colors.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        FormSheetGrabber()
        FormSheetTopBar(title = "Producto", canSave = state.canSave, onCancel = onCancel, onSave = onSave)
        if (state.loaded) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Identity(state)
                ValueCard(state, modifier = Modifier.padding(top = 18.dp))
                EditSection(state, onEditChange, modifier = Modifier.padding(top = 22.dp))
                Text(
                    text = "Eliminar",
                    color = colors.expense,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { confirmDelete = true },
                        )
                        .padding(vertical = 12.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar «${state.name}»") },
            text = { Text("Se quitará de tu cartera.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Eliminar", color = colors.expense) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Identity(state: HoldingDetailUiState) {
    val colors = LocalAppColors.current
    Text(text = state.name, color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(text = state.typeLabel)
        Text(
            text = listOf(state.identifier, state.source).filter { it.isNotEmpty() }.joinToString(" · "),
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Current value, with the euro equivalent below when it is in another currency, and the price it comes from. */
@Composable
private fun ValueCard(state: HoldingDetailUiState, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        FormSectionLabel("Valor actual")
        Text(text = state.valueText, color = colors.ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        state.valueEurText?.let {
            Text(text = it, color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        val priceLine = when {
            state.priceText != null && state.priceDateText != null ->
                "Precio ${state.priceText} · ${state.priceDateText}"
            state.priceDateText != null -> "Valorado el ${state.priceDateText}"
            else -> null
        }
        priceLine?.let {
            Text(text = it, color = colors.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun EditSection(state: HoldingDetailUiState, onEditChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (state.isQuoted) {
            FormSectionLabel("Participaciones")
            UnitsField(text = state.editText, onTextChange = onEditChange, suffix = "part.")
        } else {
            FormSectionLabel("Valor manual")
            FixedCurrencyAmountField(text = state.editText, currency = state.currency, onTextChange = onEditChange)
        }
        PreviewLine(state.preview)
    }
}
