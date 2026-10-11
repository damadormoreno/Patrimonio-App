package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.model.InstrumentCandidate
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal const val PREVIEW_LOADING_TEXT = "Cargando información del producto…"
internal const val PREVIEW_UNAVAILABLE_TEXT = "No se pudo cargar la información del producto."
internal const val PREVIEW_NO_DATA_TEXT = "Sin histórico disponible para este producto."
internal const val PREVIEW_FOOTER_TEXT =
    "Rentabilidades pasadas del producto; no garantizan rentabilidades futuras."

/**
 * Pushed screen (`InstrumentPreview`) for a search result [candidate]: its price history and product data, and how it
 * fits the portfolio, before it is added. [onAdd] continues to the add form with the product picked and receives the
 * portfolio the preview compared it with (null for all of them); [onBack] returns to the search. Adding never waits
 * for the preview: it is available while the information loads and when it fails.
 */
@Composable
fun InstrumentPreviewScreen(
    candidate: InstrumentCandidate,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onAdd: (portfolioId: String?) -> Unit = {},
    viewModel: InstrumentPreviewViewModel = koinViewModel(parameters = { parametersOf(candidate) }),
) {
    val state by viewModel.state.collectAsState()

    InstrumentPreviewContent(
        state = state,
        onBack = onBack,
        onAdd = { onAdd(state.scopePortfolioId) },
        onRangeSelect = viewModel::onRangeSelect,
        onAmountChange = viewModel::onAmountChange,
        onRetry = viewModel::onRetry,
        modifier = modifier,
    )
}

@Composable
internal fun InstrumentPreviewContent(
    state: InstrumentPreviewUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRangeSelect: (HistoryRange) -> Unit,
    onAmountChange: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a la búsqueda")
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            PreviewHeader(state.header)
            PreviewBody(state, onRangeSelect, onAmountChange, onRetry)
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton(
            text = "Añadir a la cartera",
            onClick = onAdd,
            icon = AppIcons.plus,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        )
    }
}

/** What goes under the header: the loading or failure card, or the sections of a product that loaded. */
@Composable
private fun PreviewBody(
    state: InstrumentPreviewUiState,
    onRangeSelect: (HistoryRange) -> Unit,
    onAmountChange: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state.status) {
        PreviewStatus.LOADING -> HistoryLoadingCard(Modifier.padding(top = 16.dp), text = PREVIEW_LOADING_TEXT)
        PreviewStatus.UNAVAILABLE -> HistoryUnavailableCard(
            onRetry = onRetry,
            modifier = Modifier.padding(top = 16.dp),
            message = PREVIEW_UNAVAILABLE_TEXT,
        )
        PreviewStatus.NO_DATA -> HistoryNoteCard(PREVIEW_NO_DATA_TEXT, Modifier.padding(top = 16.dp))
        PreviewStatus.READY -> {
            PriceHistoryCards(
                ranges = state.ranges,
                chart = state.chart,
                yearReturns = state.yearReturns,
                annualized = state.annualized,
                risk = state.risk,
                onRangeSelect = onRangeSelect,
            )
            state.product?.let {
                SectionLabel("Datos del producto")
                ProductDataCard(it)
            }
            state.fit?.let { InstrumentFitSection(it, state.amountText, onAmountChange) }
            Text(
                text = PREVIEW_FOOTER_TEXT,
                color = LocalAppColors.current.muted2,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
        }
    }
}

/** Name, kind and identity of the product, and its last price with the day it is from. */
@Composable
private fun PreviewHeader(header: PreviewHeaderUi) {
    val colors = LocalAppColors.current
    Text(text = header.name, color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(text = header.typeLabel)
        Text(text = header.identifier, color = colors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
    header.exchange?.let {
        Text(text = it, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    header.priceText?.let {
        Text(
            text = it,
            color = colors.ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).sp,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
    header.dateText?.let {
        Text(
            text = "Último precio · $it",
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
