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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Pushed screen (`PositionDetail`) for one portfolio holding: its value, the product's price chart over a range, the
 * return per year, annualized returns, risk and the provider's product data. [onEdit] opens the edit sheet; [onBack]
 * returns to Cartera, also when the holding is deleted while this is open.
 */
@Composable
fun PositionDetailScreen(
    holdingId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    viewModel: PositionDetailViewModel = koinViewModel(parameters = { parametersOf(holdingId) }),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }

    PositionDetailContent(
        state = state,
        onBack = onBack,
        onEdit = onEdit,
        onRangeSelect = viewModel::onRangeSelect,
        onRefresh = viewModel::onRefresh,
        onDismissSyncError = viewModel::onSyncErrorDismiss,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PositionDetailContent(
    state: PositionDetailUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRangeSelect: (HistoryRange) -> Unit,
    onRefresh: () -> Unit,
    onDismissSyncError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(modifier.fillMaxSize().background(colors.bg)) {
        TopBar(onBack = onBack, onEdit = onEdit, canEdit = state.loaded)
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (state.loaded) {
                    Header(state.header)
                    if (state.syncError) {
                        RefreshNotice(
                            message = SYNC_ERROR_TEXT,
                            onDismiss = onDismissSyncError,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                    HistorySections(state, onRangeSelect, onRetry = onRefresh)
                    state.product?.let {
                        SectionLabel("Datos del producto")
                        ProductDataCard(it)
                    }
                    Text(
                        text = state.note,
                        color = colors.muted2,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Everything that comes from the price history: the chart, the returns and the risk. */
@Composable
private fun HistorySections(state: PositionDetailUiState, onRangeSelect: (HistoryRange) -> Unit, onRetry: () -> Unit) {
    when (state.historyStatus) {
        HistoryStatus.NOT_APPLICABLE -> Unit
        HistoryStatus.LOADING -> HistoryLoadingCard(Modifier.padding(top = 16.dp))
        HistoryStatus.UNAVAILABLE -> HistoryUnavailableCard(onRetry, Modifier.padding(top = 16.dp))
        HistoryStatus.AVAILABLE -> {
            if (state.isSyncing) SyncingRow(Modifier.padding(top = 14.dp))
            state.chart?.let {
                ChartCard(
                    chart = it,
                    ranges = state.ranges,
                    onRangeSelect = onRangeSelect,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (state.yearReturns.isNotEmpty()) {
                SectionLabel("Rentabilidad por año")
                YearReturnsCard(state.yearReturns)
            }
            SectionLabel("Rentabilidad anualizada")
            AnnualizedCard(state.annualized)
            state.risk?.let {
                SectionLabel("Riesgo")
                RiskCard(it)
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onEdit: () -> Unit, canEdit: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Cartera")
        EditButton(onClick = onEdit, enabled = canEdit)
    }
}

/** "Editar" in the style of the pill buttons of the other headers. */
@Composable
private fun EditButton(onClick: () -> Unit, enabled: Boolean) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.full)
    Row(
        modifier = Modifier
            .background(colors.surface, shape)
            .border(1.dp, colors.line, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClickLabel = "Editar producto",
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.pencil,
            contentDescription = null,
            tint = colors.ink2,
            modifier = Modifier.size(14.dp),
        )
        Text(text = "Editar", color = colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Name, kind and identity of the product, what the position is worth and how much of the portfolio it is. */
@Composable
private fun Header(header: PositionHeaderUi) {
    val colors = LocalAppColors.current
    Text(text = header.name, color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(text = header.typeLabel)
        if (header.identifier.isNotEmpty()) {
            Text(text = header.identifier, color = colors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }
    header.broker?.let {
        Text(
            text = it,
            color = colors.ink2,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Text(
        text = header.valueText,
        color = if (header.valueText == NO_PRICE_TEXT) colors.muted else colors.ink,
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).sp,
        modifier = Modifier.padding(top = 16.dp),
    )
    header.valueEurText?.let {
        Text(text = it, color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    Text(text = header.detailText, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    header.weightText?.let {
        Text(
            text = it,
            color = colors.ink2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
