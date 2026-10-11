package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.InstrumentRef
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.ui.components.FormAmountField
import com.denebapps.patrimonio.ui.components.FormSectionLabel
import com.denebapps.patrimonio.ui.components.FormSheetGrabber
import com.denebapps.patrimonio.ui.components.FormSheetTopBar
import com.denebapps.patrimonio.ui.components.FormTextField
import com.denebapps.patrimonio.ui.components.LinkButton
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.components.SearchField
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel

/** What the add form can do, so [AddHoldingContent] can be shown without a ViewModel. */
internal class AddHoldingCallbacks(
    val onQueryChange: (String) -> Unit,
    val onOpenPreview: (InstrumentRef) -> Unit,
    val onEntryModeChange: (EntryMode) -> Unit,
    val onUnitsChange: (String) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onStartManual: () -> Unit,
    val onAddManuallyFromPicked: () -> Unit,
    val onBackToSearch: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onTypeChange: (InstrumentType) -> Unit,
    val onCurrencyChange: (Currency) -> Unit,
    val onBrokerChange: (String) -> Unit,
    val onPortfolioChange: (String) -> Unit,
    val onSave: () -> Unit,
)

/**
 * Full-height destination (`AddHolding`) that adds a product to the portfolio, with the same sheet chrome as the
 * Patrimonio form: search by ISIN, ticker or name, open a result's preview ([onOpenPreview], a screen pushed over this
 * one, which keeps the search) and enter what is held; or add a product by hand. Pops the back stack once
 * [AddHoldingViewModel.navigateBack] emits.
 */
@Composable
fun AddHoldingSheet(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onOpenPreview: (InstrumentRef) -> Unit = {},
    viewModel: AddHoldingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val callbacks = remember(viewModel) {
        AddHoldingCallbacks(
            onQueryChange = viewModel::onQueryChange,
            onOpenPreview = viewModel::onOpenPreview,
            onEntryModeChange = viewModel::onEntryModeChange,
            onUnitsChange = viewModel::onUnitsChange,
            onAmountChange = viewModel::onAmountChange,
            onStartManual = viewModel::onStartManual,
            onAddManuallyFromPicked = viewModel::onAddManuallyFromPicked,
            onBackToSearch = viewModel::onBackToSearch,
            onNameChange = viewModel::onNameChange,
            onTypeChange = viewModel::onTypeChange,
            onCurrencyChange = viewModel::onCurrencyChange,
            onBrokerChange = viewModel::onBrokerChange,
            onPortfolioChange = viewModel::onPortfolioChange,
            onSave = viewModel::onSave,
        )
    }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }
    LaunchedEffect(Unit) {
        viewModel.openPreview.collect { onOpenPreview(it) }
    }

    AddHoldingContent(state = state, callbacks = callbacks, onCancel = onNavigateBack, modifier = modifier)
}

@Composable
internal fun AddHoldingContent(
    state: AddHoldingUiState,
    callbacks: AddHoldingCallbacks,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = colors.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        FormSheetGrabber()
        FormSheetTopBar(
            title = if (state.stage == AddHoldingStage.MANUAL) "Valor manual" else "Añadir producto",
            canSave = state.canSave,
            onCancel = onCancel,
            onSave = callbacks.onSave,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            when (state.stage) {
                AddHoldingStage.SEARCH -> SearchStep(state, callbacks)
                AddHoldingStage.LOADING_QUOTE -> LoadingQuoteStep(state, callbacks)
                AddHoldingStage.QUOTE_FAILED -> QuoteFailedStep(state, callbacks)
                AddHoldingStage.ENTRY -> EntryStep(state, callbacks)
                AddHoldingStage.MANUAL -> ManualStep(state, callbacks)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SearchStep(state: AddHoldingUiState, callbacks: AddHoldingCallbacks) {
    val colors = LocalAppColors.current
    SearchField(
        query = state.query,
        onQueryChange = callbacks.onQueryChange,
        placeholder = "Busca por ISIN, ticker o nombre",
    )
    Spacer(Modifier.height(12.dp))
    when (state.searchStatus) {
        SearchStatus.IDLE -> StatusText("Fondos, ETF y acciones. Escribe al menos 2 caracteres.")
        SearchStatus.LOADING -> LoadingRow("Buscando…")
        SearchStatus.EMPTY -> StatusText("No se encontraron resultados")
        SearchStatus.ERROR -> StatusText("No se pudo buscar. Revisa la conexión.", colors.expense)
        SearchStatus.RESULTS -> Column {
            state.results.forEachIndexed { index, candidate ->
                CandidateRow(
                    candidate = candidate,
                    showDivider = index != state.results.lastIndex,
                    onClick = { callbacks.onOpenPreview(candidate.ref) },
                )
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    CenteredLink(text = "Añadir manualmente", onClick = callbacks.onStartManual)
}

@Composable
private fun LoadingQuoteStep(state: AddHoldingUiState, callbacks: AddHoldingCallbacks) {
    PickedSummary(state.picked)
    Spacer(Modifier.height(16.dp))
    LoadingRow("Obteniendo el precio…")
    Spacer(Modifier.height(16.dp))
    BackToSearchLink(callbacks)
}

@Composable
private fun QuoteFailedStep(state: AddHoldingUiState, callbacks: AddHoldingCallbacks) {
    PickedSummary(state.picked)
    Spacer(Modifier.height(16.dp))
    StatusText("No hay precio disponible para este producto.", LocalAppColors.current.expense)
    Spacer(Modifier.height(8.dp))
    PrimaryButton(text = "Añadir con valor manual", onClick = callbacks.onAddManuallyFromPicked)
    Spacer(Modifier.height(16.dp))
    BackToSearchLink(callbacks)
}

@Composable
private fun EntryStep(state: AddHoldingUiState, callbacks: AddHoldingCallbacks) {
    val colors = LocalAppColors.current
    val quote = state.quote ?: return
    PickedSummary(state.picked)
    Text(
        text = "Último precio: ${quote.priceText} · ${quote.dateText}",
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
    )
    SegmentedToggle(
        options = listOf(EntryMode.UNITS to "Participaciones", EntryMode.AMOUNT to "Importe actual"),
        selected = state.entryMode,
        onSelect = callbacks.onEntryModeChange,
    )
    Spacer(Modifier.height(18.dp))
    when (state.entryMode) {
        EntryMode.UNITS -> {
            FormSectionLabel("Participaciones")
            UnitsField(text = state.unitsText, onTextChange = callbacks.onUnitsChange, suffix = "part.")
        }
        EntryMode.AMOUNT -> {
            FormSectionLabel("Importe actual")
            FixedCurrencyAmountField(
                text = state.amountText,
                currency = quote.currency,
                onTextChange = callbacks.onAmountChange,
            )
        }
    }
    PreviewLine(state.preview)
    Spacer(Modifier.height(20.dp))
    BrokerField(text = state.broker, onTextChange = callbacks.onBrokerChange)
    Spacer(Modifier.height(20.dp))
    AddToPortfolio(state, callbacks)
    BackToSearchLink(callbacks, text = "Elegir otro producto")
}

@Composable
private fun ManualStep(state: AddHoldingUiState, callbacks: AddHoldingCallbacks) {
    val colors = LocalAppColors.current
    FormSectionLabel("Nombre")
    FormTextField(value = state.name, placeholder = "Ej: Plan de pensiones", onValueChange = callbacks.onNameChange)
    Spacer(Modifier.height(20.dp))
    FormSectionLabel("Tipo")
    SegmentedToggle(
        options = InstrumentType.entries.map { it to instrumentTypeLabel(it) },
        selected = state.type,
        onSelect = callbacks.onTypeChange,
    )
    Spacer(Modifier.height(20.dp))
    FormSectionLabel("Valor actual")
    FormAmountField(
        amountText = state.amountText,
        currency = state.currency,
        onAmountChange = callbacks.onAmountChange,
        onCurrencyChange = callbacks.onCurrencyChange,
    )
    Text(
        text = "El valor se suma al patrimonio neto. Si está en otra divisa se convierte a EUR al cambio actual.",
        color = colors.muted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    Spacer(Modifier.height(20.dp))
    BrokerField(text = state.broker, onTextChange = callbacks.onBrokerChange)
    Spacer(Modifier.height(20.dp))
    AddToPortfolio(state, callbacks)
    BackToSearchLink(callbacks)
}

/** The "Cartera" picker, with the space after it; nothing at all while there is a single portfolio. */
@Composable
private fun AddToPortfolio(state: AddHoldingUiState, callbacks: AddHoldingCallbacks) {
    if (!state.canPickPortfolio) return
    PortfolioPicker(options = state.portfolios, selectedId = state.portfolioId, onSelect = callbacks.onPortfolioChange)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun BackToSearchLink(callbacks: AddHoldingCallbacks, text: String = "Volver a la búsqueda") {
    CenteredLink(text = text, onClick = callbacks.onBackToSearch)
}

/** A [LinkButton] centered in its own row. */
@Composable
private fun CenteredLink(text: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        LinkButton(text = text, onClick = onClick)
    }
}

@Composable
private fun StatusText(text: String, color: Color = LocalAppColors.current.muted) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = LocalAppColors.current.ink2,
        )
        Text(text = text, color = LocalAppColors.current.muted, fontSize = 13.sp)
    }
}

/** The product being added: its name and what identifies it, with its type. */
@Composable
private fun PickedSummary(picked: CandidateUi?) {
    val colors = LocalAppColors.current
    if (picked == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = picked.name, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = listOf(picked.identifier, picked.source).filter { it.isNotEmpty() }.joinToString(" · "),
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Pill(text = picked.typeLabel)
    }
}

@Composable
private fun CandidateRow(candidate: CandidateUi, showDivider: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val lineColor = colors.line2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (showDivider) {
                    Modifier.drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClickLabel = "Ver producto", onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = candidate.name,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (candidate.identifier.isNotEmpty()) {
                Text(
                    text = candidate.identifier,
                    color = colors.ink2,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = candidate.source,
                color = colors.muted2,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Pill(text = candidate.typeLabel)
    }
}
