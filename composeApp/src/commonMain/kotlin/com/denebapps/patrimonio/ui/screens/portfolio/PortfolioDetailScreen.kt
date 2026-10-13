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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Pushed screen (`PortfolioDetail`) for one portfolio ([portfolioId]) or for all of them (`null`): its total, the value
 * of today's holdings over time, the correlation between the products, how the portfolio splits, its average cost and
 * risk, and its largest holding; for one portfolio (or the only one) it ends with "Gestionar cartera" (rename, delete).
 * [onBack] returns to Cartera, which also happens by itself once the portfolio is deleted.
 */
@Composable
fun PortfolioDetailScreen(
    portfolioId: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: PortfolioDetailViewModel = koinViewModel(parameters = { parametersOf(portfolioId) }),
) {
    val state by viewModel.state.collectAsState()
    val targetsActions = remember(viewModel) {
        TargetsActions(
            onEdit = viewModel::onEditTargets,
            onCalculatorChange = viewModel::onCalculatorChange,
            editor = TargetsEditorActions(
                onTargetChange = viewModel::onTargetChange,
                onToleranceChange = viewModel::onToleranceChange,
                onSave = viewModel::onSaveTargets,
                onClear = viewModel::onClearTargets,
                onDismiss = viewModel::onDismissEditor,
            ),
        )
    }

    LaunchedEffect(viewModel) { viewModel.navigateBack.collect { onBack() } }

    val managementActions = remember(viewModel) {
        ManagementActions(
            onRename = viewModel::onRenamePortfolio,
            onDelete = viewModel::onDeletePortfolio,
            dialog = PortfolioDialogActions(
                onNameChange = viewModel::onDialogNameChange,
                onTargetChange = viewModel::onDeleteTargetChange,
                onConfirm = viewModel::onDialogConfirm,
                onDismiss = viewModel::onDialogDismiss,
            ),
        )
    }

    PortfolioDetailContent(
        state = state,
        onBack = onBack,
        onRangeSelect = viewModel::onRangeSelect,
        onRefresh = viewModel::onRefresh,
        onDismissSyncError = viewModel::onSyncErrorDismiss,
        targetsActions = targetsActions,
        managementActions = managementActions,
        modifier = modifier,
    )
}

/** What the "Objetivo de reparto" section and its editor can do, so they can be shown without a ViewModel. */
internal class TargetsActions(
    val onEdit: () -> Unit,
    val onCalculatorChange: (String) -> Unit,
    val editor: TargetsEditorActions,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioDetailContent(
    state: PortfolioDetailUiState,
    onBack: () -> Unit,
    onRangeSelect: (HistoryRange) -> Unit,
    onRefresh: () -> Unit,
    onDismissSyncError: () -> Unit,
    targetsActions: TargetsActions,
    managementActions: ManagementActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            HeaderIconBtn(icon = AppIcons.chevronL, onClick = onBack, label = "Volver a Cartera")
        }
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                if (state.loaded) {
                    LoadedSections(
                        state,
                        onRangeSelect,
                        onRefresh,
                        onDismissSyncError,
                        targetsActions,
                        managementActions,
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    state.targetsEditor?.let { TargetsEditorSheet(it, targetsActions.editor) }
    state.dialog?.let { PortfolioDialog(dialog = it, actions = managementActions.dialog) }
}

@Composable
private fun LoadedSections(
    state: PortfolioDetailUiState,
    onRangeSelect: (HistoryRange) -> Unit,
    onRetry: () -> Unit,
    onDismissSyncError: () -> Unit,
    targetsActions: TargetsActions,
    managementActions: ManagementActions,
) {
    val colors = LocalAppColors.current
    val available = state.historyStatus == HistoryStatus.AVAILABLE

    PortfolioHeader(state.header)
    state.yourReturns?.let { YourReturnsSection(it) }
    if (available && state.isSyncing) SyncingRow(Modifier.padding(top = 14.dp))
    if (available && state.syncError) {
        RefreshNotice(
            message = SYNC_ERROR_TEXT,
            onDismiss = onDismissSyncError,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
    state.classAllocation?.let { ClassAllocationSection(it) }
    state.targetSection?.let { TargetsSection(it, targetsActions.onEdit, targetsActions.onCalculatorChange) }
    when (state.historyStatus) {
        HistoryStatus.NOT_APPLICABLE, HistoryStatus.AVAILABLE -> Unit
        HistoryStatus.LOADING -> HistoryLoadingCard(Modifier.padding(top = 16.dp))
        HistoryStatus.UNAVAILABLE -> HistoryUnavailableCard(onRetry, Modifier.padding(top = 16.dp))
    }
    state.evolution?.let { EvolutionSection(it, onRangeSelect) }
    state.correlation?.let { CorrelationSection(it) }
    if (state.allocations.isNotEmpty()) AllocationSection(state.allocations)
    state.costsRisk?.let { CostsRiskSection(it) }
    state.concentration?.let { ConcentrationSection(it) }
    state.management?.let { ManagementSection(it, managementActions) }
    state.note?.let {
        Text(
            text = it,
            color = colors.muted2,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )
    }
}
