package com.denebapps.patrimonio.ui.screens.subscriptions

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.SubscriptionTotals
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.ui.components.EmptyState
import com.denebapps.patrimonio.ui.components.FabContentBottomSpacing
import com.denebapps.patrimonio.ui.components.Pill
import com.denebapps.patrimonio.ui.components.PillTone
import com.denebapps.patrimonio.ui.components.ScreenHeader
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.components.formatDayMonth
import com.denebapps.patrimonio.ui.components.formatEur
import com.denebapps.patrimonio.ui.components.formatMoney
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.AppShadows
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes
import com.denebapps.patrimonio.ui.theme.instrumentSerifItalicFontFamily
import org.koin.compose.viewmodel.koinViewModel

/** Subscriptions tab: monthly/yearly spend in EUR, active subscriptions by next charge, paused ones
 *  below. [onAdd]/[onOpen] push the create/edit sheet. */
@Composable
fun SubscriptionsScreen(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalAppColors.current

    Column(modifier.fillMaxSize().background(colors.bg)) {
        ScreenHeader(title = "Suscripciones", eyebrow = activeCountLabel(state.totals.activeCount))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            SpendCard(state.totals)

            if (state.isEmpty) {
                EmptyState(
                    icon = AppIcons.receipt,
                    title = "Sin suscripciones",
                    message = "Añade lo que pagas cada mes o cada año y verás cuánto suma en total.",
                )
            } else {
                if (state.upcoming.isNotEmpty()) {
                    SectionRow(title = "Próximos cargos", showDivider = false)
                    SubscriptionList(rows = state.upcoming, paused = false, onOpen = onOpen)
                }
                if (state.paused.isNotEmpty()) {
                    SectionRow(title = "Pausadas", showDivider = false)
                    SubscriptionList(rows = state.paused, paused = true, onOpen = onOpen)
                }
            }

            AddSubscriptionCta(onClick = onAdd, modifier = Modifier.padding(top = 16.dp))

            Text(
                text = "Totales en EUR al cambio actual · no incluyen las pausadas",
                color = colors.muted2,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
            )
            Spacer(Modifier.height(FabContentBottomSpacing))
        }
    }
}

@Composable
private fun SpendCard(totals: SubscriptionTotals, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current

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
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "GASTO MENSUAL",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Pill(text = "EUR · €", tone = PillTone.Neutral)
        }
        Text(
            text = formatEur(totals.monthlyEur),
            color = colors.ink,
            fontSize = 44.sp,
            fontFamily = instrumentSerifItalicFontFamily(),
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "${formatEur(totals.yearlyEur)} al año",
            color = colors.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SubscriptionList(rows: List<SubscriptionRowUi>, paused: Boolean, onOpen: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
    ) {
        rows.forEachIndexed { index, row ->
            SubscriptionRow(
                row = row,
                paused = paused,
                showDivider = index < rows.lastIndex,
                onClick = { onOpen(row.id) },
            )
        }
    }
}

@Composable
private fun SubscriptionRow(row: SubscriptionRowUi, paused: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val lineColor = colors.line2
    val eurHint = if (row.currency != Currency.EUR) "≈ ${formatEur(row.monthlyEur)}/mes" else null
    val subtitle = listOfNotNull(cycleLabel(row.cycle), row.paidFrom, eurHint).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .drawBehind {
                if (showDivider) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = if (paused) colors.muted else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatMoney(row.amount, row.currency),
                color = if (paused) colors.muted else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (paused) {
                    "Pausada"
                } else {
                    "${nextChargeLabel(row.daysUntilNextCharge)} · ${formatDayMonth(row.nextCharge)}"
                },
                color = if (!paused && row.daysUntilNextCharge <= 1) colors.brand else colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun AddSubscriptionCta(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.line, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.plus, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = "Nueva suscripción", color = colors.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
