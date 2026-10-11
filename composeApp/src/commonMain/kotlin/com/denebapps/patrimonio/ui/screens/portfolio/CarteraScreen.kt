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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Cartera tab: the portfolio total, the holdings valued at their latest price, and a price refresh (header button or
 * pull down). [onAddHolding] opens the add form (the FAB does too, see `MainScaffold`); [onOpenHolding] opens one
 * holding's detail.
 */
@Composable
fun CarteraScreen(
    modifier: Modifier = Modifier,
    viewModel: CarteraViewModel = koinViewModel(),
    onAddHolding: () -> Unit = {},
    onOpenHolding: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    CarteraContent(
        state = state,
        onRefresh = viewModel::onRefresh,
        onDismissMessage = viewModel::onMessageDismiss,
        onAddHolding = onAddHolding,
        onOpenHolding = onOpenHolding,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarteraContent(
    state: CarteraUiState,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
    onAddHolding: () -> Unit,
    onOpenHolding: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current

    Column(modifier.fillMaxSize().background(colors.bg)) {
        ScreenHeader(
            title = "Cartera",
            eyebrow = state.countLabel,
            right = { RefreshButton(refreshing = state.isRefreshing, onClick = onRefresh) },
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                TotalCard(total = state.total)
                state.refreshMessage?.let { message ->
                    RefreshNotice(
                        message = message,
                        onDismiss = onDismissMessage,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.isEmpty) {
                    EmptyState(
                        icon = AppIcons.trend,
                        title = "Aún no tienes productos",
                        message = "Añade tu primer fondo, ETF o acción para ver cuánto vale tu cartera.",
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    PrimaryButton(text = "Añadir producto", onClick = onAddHolding, icon = AppIcons.plus)
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
}

/** Text button in the header: "Actualizar", or a spinner while the prices are being fetched. */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(LocalAppShapes.current.full)
    Row(
        modifier = Modifier
            .background(colors.surface, shape)
            .border(1.dp, colors.line, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !refreshing,
                onClickLabel = "Actualizar precios",
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.ink2)
        }
        Text(
            text = if (refreshing) "Actualizando…" else "Actualizar",
            color = colors.ink2,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The portfolio total in EUR, big, in the style of the net-worth card. */
@Composable
private fun TotalCard(total: Money, modifier: Modifier = Modifier) {
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
private fun RefreshNotice(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
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
                    color = colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Pill(text = row.typeLabel)
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
                color = if (row.valueText == NO_PRICE_TEXT) colors.muted else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            row.valueEurText?.let {
                Text(text = it, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}
