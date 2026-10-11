package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.ui.components.FormDateField
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.FormSheetGrabber
import com.denebapps.patrimonio.ui.components.FormSheetTopBar
import com.denebapps.patrimonio.ui.components.FormTextField
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the movement sheet can do, so [MovementFormContent] can be shown without a ViewModel. */
internal class MovementFormCallbacks(
    val onChoiceChange: (MovementChoice) -> Unit,
    val onDateChange: (LocalDate) -> Unit,
    val onUnitsChange: (String) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onFeeChange: (String) -> Unit,
    val onEurChange: (String) -> Unit,
    val onNoteChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Full-height destination (`MovementForm`) that adds, edits or deletes one movement of a position, or its opening
 * balance. Pops the back stack once [MovementFormViewModel.navigateBack] emits; choosing a transfer calls
 * [onOpenTransfer] so that the caller can replace this sheet with the transfer one.
 */
@Composable
fun MovementFormSheet(
    holdingId: String,
    movementId: String?,
    openingBalance: Boolean,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    viewModel: MovementFormViewModel = koinViewModel(
        parameters = { parametersOf(holdingId, movementId, openingBalance) },
    ),
) {
    val state by viewModel.state.collectAsState()
    val callbacks = remember(viewModel) {
        MovementFormCallbacks(
            onChoiceChange = viewModel::onChoiceChange,
            onDateChange = viewModel::onDateChange,
            onUnitsChange = viewModel::onUnitsChange,
            onAmountChange = viewModel::onAmountChange,
            onFeeChange = viewModel::onFeeChange,
            onEurChange = viewModel::onEurChange,
            onNoteChange = viewModel::onNoteChange,
            onSave = viewModel::onSave,
            onDelete = viewModel::onDelete,
            onCancel = onNavigateBack,
        )
    }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }
    LaunchedEffect(Unit) {
        viewModel.openTransfer.collect { onOpenTransfer() }
    }

    MovementFormContent(state = state, callbacks = callbacks, modifier = modifier)
}

@Composable
internal fun MovementFormContent(
    state: MovementFormUiState,
    callbacks: MovementFormCallbacks,
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
        FormSheetTopBar(
            title = state.title,
            canSave = state.canSave,
            onCancel = callbacks.onCancel,
            onSave = callbacks.onSave,
        )
        if (state.loaded) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                MovementFields(state, callbacks)
                state.error?.let {
                    Text(
                        text = it,
                        color = colors.expense,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                if (state.canDelete) {
                    Text(
                        text = "Eliminar movimiento",
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
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar movimiento") },
            text = { Text(state.deleteMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        callbacks.onDelete()
                    },
                ) { Text("Eliminar", color = colors.expense) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/** The fields the type needs: units, amount (with the price it implies), fee, euro equivalent, date and note. */
@Composable
private fun MovementFields(state: MovementFormUiState, callbacks: MovementFormCallbacks) {
    val colors = LocalAppColors.current
    Text(text = state.holdingName, color = colors.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    if (state.choices.isNotEmpty()) {
        OptionPicker(
            title = "Tipo",
            options = state.choices.map { it to it.label },
            selected = state.choice,
            placeholder = state.choice.label,
            clickLabel = "Elegir tipo de movimiento",
            onSelect = callbacks.onChoiceChange,
        )
        Spacer(Modifier.height(20.dp))
    }
    FormSectionLabel("Fecha")
    FormDateField(date = state.date, onDateChange = callbacks.onDateChange)
    if (state.showUnits) {
        Spacer(Modifier.height(20.dp))
        FormSectionLabel("Participaciones")
        UnitsField(text = state.unitsText, onTextChange = callbacks.onUnitsChange, suffix = "part.")
    }
    Spacer(Modifier.height(20.dp))
    FormSectionLabel(state.amountLabel)
    FixedCurrencyAmountField(
        text = state.amountText,
        currency = state.currency,
        onTextChange = callbacks.onAmountChange,
    )
    PreviewLine(state.impliedPriceText)
    if (state.showFee) {
        Spacer(Modifier.height(20.dp))
        FormSectionLabel("Comisión (opcional)")
        FixedCurrencyAmountField(
            text = state.feeText,
            currency = state.currency,
            onTextChange = callbacks.onFeeChange,
        )
    }
    if (state.showEur) {
        Spacer(Modifier.height(20.dp))
        FormSectionLabel("Equivalente en EUR")
        FixedCurrencyAmountField(text = state.eurText, currency = Currency.EUR, onTextChange = callbacks.onEurChange)
        state.eurHint?.let { PreviewLine(it) }
    }
    Spacer(Modifier.height(20.dp))
    FormSectionLabel("Nota (opcional)")
    FormTextField(
        value = state.noteText,
        placeholder = "Ej: aportación mensual",
        onValueChange = callbacks.onNoteChange,
    )
}
