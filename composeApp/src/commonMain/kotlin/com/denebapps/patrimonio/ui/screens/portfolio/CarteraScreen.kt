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
import com.denebapps.patrimonio.ui.components.HeaderIconBtn
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PrimaryButton
import com.denebapps.patrimonio.ui.components.RefreshIcon
import com.denebapps.patrimonio.ui.components.ScreenHeader
import com.denebapps.patrimonio.ui.components.formatEur
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.AppShadows
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import com.denebapps.patrimonio.ui.theme.instrumentSerifItalicFontFamily
import org.koin.compose.viewmodel.koinViewModel

/** Side gutter of the whole tab, the same as the header's. */
private val GUTTER = 20.dp

/** Vertical gap between the blocks of the tab (scope row, total, notices, list header, list). */
private val BLOCK_GAP = 12.dp

/** Gap between a block and the line or chips that qualify it (the list header and its active filters). */
private val INNER_GAP = 8.dp

/** Space above and below the two lines of a product row. */
private val ROW_PADDING = 13.dp

/**
 * Cartera tab: the total of the selected portfolio (or of all), its holdings valued at their latest price, and a price
 * refresh (header button or pull down). Under the header, a row of chips switches between the portfolios (and creates
 * one); renaming or deleting a portfolio is done from its detail. [onAddHolding] opens the add form (the FAB does too,
 * see `MainScaffold`); [onImport] the import of MyInvestor files (header button); [onOpenHolding] opens one holding's
 * detail and [onOpenPortfolio] (the total) the detail of the selected portfolio, `null` for all of them (the detail
 * and the net worth are never filtered).
 *
 * The filter button above the list opens the filters (class, broker, type), with the number of active ones in a
 * badge. While some are active, the total and the list show only what passes them, a line says how many of the
 * scope's products that is and a chip per filter removes it. Each product takes two lines: its name and value, and a
 * muted line with its type, class, bank or broker and units.
 *
 * When a portfolio drifted out of its target band, a notice under the total says so and opens that portfolio's detail
 * through [onOpenPortfolio] (with all of them shown, the first one that needs it); the filters do not affect it.
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
        PortfolioScopeActions(onSelect = viewModel::onSelectPortfolio, onNew = viewModel::onNewPortfolio)
    }
    val dialogActions = remember(viewModel) {
        PortfolioDialogActions(
            onNameChange = viewModel::onDialogNameChange,
            // Only the creation dialog lives here, and it has no portfolio to pick.
            onTargetChange = {},
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
                    horizontalArrangement = Arrangement.spacedBy(INNER_GAP),
                ) {
                    HeaderIconBtn(icon = AppIcons.download, onClick = onImport, label = "Importar de MyInvestor")
                    HeaderIconBtn(
                        icon = RefreshIcon,
                        onClick = onRefresh,
                        label = "Actualizar precios",
                        loading = state.isRefreshing,
                    )
                }
            },
        )
        PortfolioScopeBar(state = state, actions = scopeActions)
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = GUTTER, end = GUTTER, top = BLOCK_GAP),
                verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
            ) {
                TotalCard(
                    total = state.total,
                    onClick = { onOpenPortfolio(state.selectedPortfolioId) }.takeUnless { state.isEmpty },
                )
                state.rebalanceBanner?.let {
                    RebalanceBannerView(banner = it, onClick = { onOpenPortfolio(it.portfolioId) })
                }
                state.refreshMessage?.let { RefreshNotice(message = it, onDismiss = onDismissMessage) }
                if (state.filter.hasOptions) {
                    ProductsHeader(
                        summary = state.filterSummary,
                        filter = state.filter,
                        onFilter = { filterOpen = true },
                        onRemove = filterActions.onRemove,
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
                    )
                    PrimaryButton(text = "Añadir producto", onClick = onAddHolding, icon = AppIcons.plus)
                } else if (state.noMatches) {
                    NoMatchesState(onClear = filterActions.onClear)
                } else {
                    HoldingsCard(rows = state.rows, onOpenHolding = onOpenHolding)
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

/**
 * The line above the list: "Productos" (or, while some filters are on, how many of the scope's products pass them) and
 * the filter button at the end, with the active filters as removable chips right under it.
 */
@Composable
private fun ProductsHeader(
    summary: String?,
    filter: CarteraFilterUi,
    onFilter: () -> Unit,
    onRemove: (FilterDimension, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(INNER_GAP)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterSummaryLine(text = summary ?: "Productos", modifier = Modifier.weight(1f))
            FilterButton(activeCount = filter.activeCount, onClick = onFilter)
        }
        if (filter.active.isNotEmpty()) {
            ActiveFilterChips(active = filter.active, onRemove = onRemove)
        }
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

/**
 * A product in two lines. First its name (ellipsized) and its value, right-aligned (below it, in a euro line, what it
 * is worth in euros when it is not quoted in them). Second, muted and on one line, [HoldingRowUi.meta] after the dot
 * of its class color, when it has a class.
 */
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
            .padding(vertical = ROW_PADDING),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = if (row.isClosed) colors.muted else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.assetClass?.let { Box(Modifier.size(6.dp).background(assetClassColor(it), CircleShape)) }
                Text(
                    text = row.meta,
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = row.valueText,
                color = if (row.valueText == NO_PRICE_TEXT || row.isClosed) colors.muted else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            row.valueEurText?.let {
                Text(
                    text = it,
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
