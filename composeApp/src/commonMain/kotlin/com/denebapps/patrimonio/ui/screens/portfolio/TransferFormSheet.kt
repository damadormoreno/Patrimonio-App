package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.ui.components.FormDateField
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.FormSheetGrabber
import com.denebapps.patrimonio.ui.components.FormSheetTopBar
import com.denebapps.patrimonio.ui.components.FormTextField
import com.denebapps.patrimonio.ui.components.LinkButton
import com.denebapps.patrimonio.ui.components.SearchField
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the transfer sheet can do, so [TransferFormContent] can be shown without a ViewModel. */
internal class TransferFormCallbacks(
    val onDestinationChange: (String) -> Unit,
    val onOtherFund: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onPickCandidate: (InstrumentRef) -> Unit,
    val onClearNewFund: () -> Unit,
    val onPortfolioChange: (String) -> Unit,
    val onOutDateChange: (LocalDate) -> Unit,
    val onInDateChange: (LocalDate) -> Unit,
    val onUnitsOutChange: (String) -> Unit,
    val onAllUnits: () -> Unit,
    val onUnitsInChange: (String) -> Unit,
    val onNoteChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onCancel: () -> Unit,
)

private const val OTHER_FUND_LABEL = "Otro fondo…"

/**
 * Full-height destination (`TransferForm`) that makes a fund transfer, or edits and deletes one. Pops the back stack
 * once [TransferFormViewModel.navigateBack] emits.
 */
@Composable
fun TransferFormSheet(
    holdingId: String,
    transferId: String?,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: TransferFormViewModel = koinViewModel(parameters = { parametersOf(holdingId, transferId) }),
) {
    val state by viewModel.state.collectAsState()
    val callbacks = remember(viewModel) {
        TransferFormCallbacks(
            onDestinationChange = viewModel::onDestinationChange,
            onOtherFund = viewModel::onOtherFund,
            onQueryChange = viewModel::onQueryChange,
            onPickCandidate = viewModel::onPickCandidate,
            onClearNewFund = viewModel::onClearNewFund,
            onPortfolioChange = viewModel::onPortfolioChange,
            onOutDateChange = viewModel::onOutDateChange,
            onInDateChange = viewModel::onInDateChange,
            onUnitsOutChange = viewModel::onUnitsOutChange,
            onAllUnits = viewModel::onAllUnits,
            onUnitsInChange = viewModel::onUnitsInChange,
            onNoteChange = viewModel::onNoteChange,
            onSave = viewModel::onSave,
            onDelete = viewModel::onDelete,
            onCancel = onNavigateBack,
        )
    }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    TransferFormContent(state = state, callbacks = callbacks, modifier = modifier)
}

@Composable
internal fun TransferFormContent(
    state: TransferFormUiState,
    callbacks: TransferFormCallbacks,
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
            title = if (state.isEditing) "Editar traspaso" else "Traspaso",
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
                Origin(state)
                Spacer(Modifier.height(20.dp))
                Destination(state, callbacks)
                Spacer(Modifier.height(20.dp))
                Amounts(state, callbacks)
                state.blockedText?.let { Notice(it) }
                state.error?.let { Notice(it, LocalAppColors.current.expense) }
                if (state.canDelete) {
                    Text(
                        text = "Eliminar traspaso",
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
            title = { Text("Eliminar traspaso") },
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

@Composable
private fun Notice(text: String, color: Color = LocalAppColors.current.muted) {
    Text(text = text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 16.dp))
}

/** The fund the units leave, with how many it holds. */
@Composable
private fun Origin(state: TransferFormUiState) {
    val colors = LocalAppColors.current
    FormSectionLabel("Desde")
    Text(text = state.fromName, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(text = state.availableText, color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
}

/**
 * The fund the units go to: fixed when editing; otherwise a picker of the funds that can receive them with the
 * "Otro fondo…" entry, which opens the product search for a fund that is not held yet.
 */
@Composable
private fun Destination(state: TransferFormUiState, callbacks: TransferFormCallbacks) {
    val colors = LocalAppColors.current
    if (state.isEditing) {
        FormSectionLabel("A")
        Text(
            text = state.destinationName.orEmpty(),
            color = colors.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        return
    }
    OptionPicker(
        title = "A",
        options = state.destinations.map { it.id to it.label },
        selected = state.destinationId,
        placeholder = if (state.searchingNew) OTHER_FUND_LABEL else "Elige el fondo de destino",
        clickLabel = "Elegir fondo de destino",
        onSelect = callbacks.onDestinationChange,
        extra = OTHER_FUND_LABEL to callbacks.onOtherFund,
    )
    state.excludedHint?.let {
        Text(
            text = it,
            color = colors.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (state.searchingNew) {
        Spacer(Modifier.height(16.dp))
        NewFund(state, callbacks)
    }
}

/** The search for a fund not held yet, or the one picked, and the portfolio it is created in. */
@Composable
private fun NewFund(state: TransferFormUiState, callbacks: TransferFormCallbacks) {
    val newFund = state.newFund
    if (newFund != null) {
        PickedSummary(newFund)
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            LinkButton(text = "Cambiar", onClick = callbacks.onClearNewFund)
        }
        if (state.canPickPortfolio) {
            PortfolioPicker(
                options = state.portfolios,
                selectedId = state.portfolioId,
                onSelect = callbacks.onPortfolioChange,
            )
        }
        return
    }
    SearchField(
        query = state.query,
        onQueryChange = callbacks.onQueryChange,
        placeholder = "Busca el fondo por ISIN o nombre",
    )
    Spacer(Modifier.height(8.dp))
    when (state.searchStatus) {
        SearchStatus.IDLE -> StatusText("Escribe al menos 2 caracteres. Solo aparecen fondos que aún no tienes.")
        SearchStatus.LOADING -> LoadingRow("Buscando…")
        SearchStatus.EMPTY -> StatusText("No se encontraron fondos nuevos")
        SearchStatus.ERROR -> StatusText("No se pudo buscar. Revisa la conexión.", LocalAppColors.current.expense)
        SearchStatus.RESULTS -> Column {
            state.results.forEachIndexed { index, candidate ->
                CandidateRow(
                    candidate = candidate,
                    showDivider = index != state.results.lastIndex,
                    onClick = { callbacks.onPickCandidate(candidate.ref) },
                )
            }
        }
    }
    if (state.newFundLoading) LoadingRow("Obteniendo el precio…")
    state.newFundError?.let { StatusText(it, LocalAppColors.current.expense) }
}

/** The dates and the units on each side of the transfer, and the note. */
@Composable
private fun Amounts(state: TransferFormUiState, callbacks: TransferFormCallbacks) {
    val colors = LocalAppColors.current
    FormSectionLabel("Fecha de salida")
    FormDateField(date = state.outDate, onDateChange = callbacks.onOutDateChange)
    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FormSectionLabel("Participaciones que salen")
        LinkButton(text = "Todo", onClick = callbacks.onAllUnits)
    }
    UnitsField(text = state.unitsOutText, onTextChange = callbacks.onUnitsOutChange, suffix = "part.")
    Spacer(Modifier.height(20.dp))
    FormSectionLabel("Fecha de entrada")
    FormDateField(date = state.inDate, onDateChange = callbacks.onInDateChange)
    Spacer(Modifier.height(20.dp))
    FormSectionLabel("Participaciones que entran")
    UnitsField(text = state.unitsInText, onTextChange = callbacks.onUnitsInChange, suffix = "part.")
    Text(
        text = "El coste y las fechas de compra viajan con las participaciones: un traspaso no realiza ganancia.",
        color = colors.muted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(20.dp))
    FormSectionLabel("Nota (opcional)")
    FormTextField(value = state.noteText, placeholder = "Ej: cambio de gestora", onValueChange = callbacks.onNoteChange)
}
