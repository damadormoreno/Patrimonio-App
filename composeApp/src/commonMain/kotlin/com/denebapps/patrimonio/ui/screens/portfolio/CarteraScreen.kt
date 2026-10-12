package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.ui.components.EmptyState
import com.denebapps.patrimonio.ui.components.FabContentBottomSpacing
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.components.ScreenHeader
import com.denebapps.patrimonio.ui.components.formatEur
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.AppShadows
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import com.denebapps.patrimonio.ui.theme.instrumentSerifItalicFontFamily
import org.koin.compose.viewmodel.koinViewModel

/**
 * Cartera tab: the total of the selected portfolio (or of all), its holdings valued at their latest price, and a price
 * refresh (header button or pull down). Under the header, a selector switches between the portfolios and manages them.
 * [onAddHolding] opens the add form (the FAB does too, see `MainScaffold`); [onImport] the import of MyInvestor files
 * (header button); [onOpenHolding] opens one holding's detail and [onOpenPortfolio] (the total) the detail of the
 * selected portfolio, `null` for all of them (the detail and the net worth are never filtered).
 *
 * `Filtrar` in the scope bar opens the filters (class, broker, type). While some are active, the total and the list
 * show only what passes them, a line says how many of the scope's products that is and a chip per filter removes it.
 */
@Composable
fun CarteraScreen(
    modifier: Modifier = Modifier,
    viewModel: CarteraViewModel = koinViewModel(),
    onAddHolding: () -> Unit = {},
    onImport: () -> Unit = {},
    onOpenHolding: (String) -> Unit = {},
    onOpenPortfolio: (String?) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val scopeActions = remember(viewModel) {
        PortfolioScopeActions(
            onSelect = viewModel::onSelectPortfolio,
            onNew = viewModel::onNewPortfolio,
            onRename = viewModel::onRenamePortfolio,
            onDelete = viewModel::onDeletePortfolio,
        )
    }
    val dialogActions = remember(viewModel) {
        PortfolioDialogActions(
            onNameChange = viewModel::onDialogNameChange,
            onTargetChange = viewModel::onDeleteTargetChange,
            onConfirm = viewModel::onDialogConfirm,
            onDismiss = viewModel::onDialogDismiss,
        )
    }
    val filterActions = remember(viewModel) {
        CarteraFilterActions(
            onToggle = viewModel::onToggleFilter,
            onRemove = viewModel::onRemoveFilter,
            onClear = viewModel::onClearFilters,
        )
    }
    CarteraContent(
        state = state,
        scopeActions = scopeActions,
        dialogActions = dialogActions,
        filterActions = filterActions,
        onRefresh = viewModel::onRefresh,
        onDismissMessage = viewModel::onMessageDismiss,
        onAddHolding = onAddHolding,
        onImport = onImport,
        onOpenHolding = onOpenHolding,
        onOpenPortfolio = onOpenPortfolio,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarteraContent(
    state: CarteraUiState,
    scopeActions: PortfolioScopeActions,
    dialogActions: PortfolioDialogActions,
    filterActions: CarteraFilterActions,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    onAddHolding: () -> Unit,
    onImport: () -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenPortfolio: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var filterOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        ScreenHeader(
            title = "Cartera",
            eyebrow = state.scopeLabel?.let { "$it · ${state.countLabel}" } ?: state.countLabel,
            right = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Hidden while the prices refresh: with "Actualizando…" both would not fit a narrow phone.
                    if (!state.isRefreshing) {
                        HeaderTextButton(text = "Importar", onClickLabel = "Importar de MyInvestor", onClick = onImport)
                    }
                    RefreshButton(refreshing = state.isRefreshing, onClick = onRefresh)
                }
            },
        )
        PortfolioScopeBar(state = state, actions = scopeActions, onFilter = { filterOpen = true })
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                TotalCard(
                    total = state.total,
                    onClick = { onOpenPortfolio(state.selectedPortfolioId) }.takeUnless { state.isEmpty },
                )
                state.filterSummary?.let { FilterSummaryLine(text = it, modifier = Modifier.padding(top = 10.dp)) }
                if (state.filter.active.isNotEmpty()) {
                    ActiveFilterChips(
                        active = state.filter.active,
                        onRemove = filterActions.onRemove,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                state.refreshMessage?.let { message ->
                    RefreshNotice(
                        message = message,
                        onDismiss = onDismissMessage,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.isEmpty) {
                    val inPortfolio = state.selectedPortfolioId != null
                    EmptyState(
                        icon = AppIcons.trend,
                        title = if (inPortfolio) "Esta cartera está vacía" else "Aún no tienes productos",
                        message = if (inPortfolio) {
                            "Añade un producto o muévelo aquí desde su ficha."
                        } else {
                            "Añade tu primer fondo, ETF o acción para ver cuánto vale tu cartera."
                        },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    PrimaryButton(text = "Añadir producto", onClick = onAddHolding, icon = AppIcons.plus)
                } else if (state.noMatches) {
                    NoMatchesState(onClear = filterActions.onClear, modifier = Modifier.padding(top = 14.dp))
                } else {
                    HoldingsCard(
                        rows = state.rows,
                        onOpenHolding = onOpenHolding,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                Spacer(Modifier.height(FabContentBottomSpacing))
            }
        }
    }
    state.dialog?.let { PortfolioDialog(dialog = it, actions = dialogActions) }
    if (filterOpen && state.filter.hasOptions) {
        CarteraFilterSheet(filter = state.filter, actions = filterActions, onDismiss = { filterOpen = false })
    }
}

/** Text button in the header: "Actualizar", or a spinner while the prices are being fetched. */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    HeaderTextButton(
        text = if (refreshing) "Actualizando…" else "Actualizar",
        onClickLabel = "Actualizar precios",
        onClick = onClick,
        enabled = !refreshing,
        loading = refreshing,
    )
}

/** The pill-shaped text button of the Cartera header ("Importar", "Actualizar"); [loading] adds a spinner. */
@Composable
private fun HeaderTextButton(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
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
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.ink2)
        }
        Text(text = text, color = colors.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The portfolio total in EUR, big, in the style of the net-worth card; with an [onClick] it opens the detail. */
@Composable
private fun TotalCard(total: Money, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val amount = formatEur(total).removeSuffix(" €")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .then(AppShadows.sh2(shapes.xl))
            .background(
                brush = Brush.verticalGradient(listOf(colors.surface2, colors.surface)),
                shape = RoundedCornerShape(shapes.xl),
            )
            .border(width = 1.dp, color = colors.line, shape = RoundedCornerShape(shapes.xl))
            .clip(RoundedCornerShape(shapes.xl))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = "Ver detalle de la cartera", onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "VALOR DE LA CARTERA",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Pill(text = "EUR · €")
            if (onClick != null) {
                Spacer(Modifier.weight(1f))
                Text(text = "Ver detalle", color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Icon(
                    imageVector = AppIcons.chevronR,
                    contentDescription = null,
                    tint = colors.ink2,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    append(amount.substringBeforeLast(','))
                    withStyle(SpanStyle(color = colors.ink.copy(alpha = 0.45f))) {
                        append(",")
                        append(amount.substringAfterLast(','))
                    }
                },
                color = colors.ink,
                fontSize = 44.sp,
                fontFamily = instrumentSerifItalicFontFamily(),
                fontStyle = FontStyle.Italic,
                lineHeight = 46.sp,
            )
            Text(text = "€", color = colors.ink2, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Short, non-blocking note after a refresh that could not update every price; tap the cross to dismiss it. */
@Composable
internal fun RefreshNotice(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.alertSoft, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            color = colors.alert,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AppIcons.close,
            contentDescription = "Cerrar aviso",
            tint = colors.alert,
            modifier = Modifier
                .size(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}

@Composable
private fun HoldingsCard(rows: List<HoldingRowUi>, onOpenHolding: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
    ) {
        rows.forEachIndexed { index, row ->
            HoldingRow(row = row, showDivider = index != rows.lastIndex, onClick = { onOpenHolding(row.id) })
        }
    }
}

/** The class of a holding: its color dot, as in the class bars, and its name, muted and on one line. */
@Composable
private fun AssetClassTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(
            text = text,
            color = LocalAppColors.current.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun HoldingRow(row: HoldingRowUi, showDivider: Boolean, onClick: () -> Unit) {
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
            .clickable(onClickLabel = "Ver detalle", onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = row.name,
                    color = if (row.isClosed) colors.muted else colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Pill(text = row.typeLabel)
            }
            row.assetClassText?.let { AssetClassTag(text = it, color = assetClassColor(row.assetClass)) }
            row.portfolioName?.let {
                Text(
                    text = it,
                    color = colors.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Own line, ellipsized, so a long name never pushes the units and price of the detail line out.
            row.broker?.let {
                Text(
                    text = it,
                    color = colors.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = row.detail,
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = row.valueText,
                color = if (row.valueText == NO_PRICE_TEXT || row.isClosed) colors.muted else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            row.valueEurText?.let {
                Text(text = it, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}
