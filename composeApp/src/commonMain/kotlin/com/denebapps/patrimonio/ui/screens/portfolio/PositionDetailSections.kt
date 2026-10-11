package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.domain.model.MAX_RISK_LEVEL
import com.denebapps.patrimonio.domain.model.MIN_RISK_LEVEL
import com.denebapps.patrimonio.ui.components.GhostButton
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.components.charts.LineChart
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private const val DISABLED_ALPHA = 0.4f
private val CARD_SHAPE = RoundedCornerShape(16.dp)

/** The widest a long text (category, management company) gets on the right of a row before it wraps. */
private val LONG_VALUE_MAX_WIDTH = 190.dp

/** The color a signed figure is drawn in: income for a gain, expense for a loss, quiet when nothing moved. */
@Composable
internal fun toneColor(tone: PerformanceTone): Color {
    val colors = LocalAppColors.current
    return when (tone) {
        PerformanceTone.GAIN -> colors.income
        PerformanceTone.LOSS -> colors.expense
        PerformanceTone.NEUTRAL -> colors.ink2
    }
}

/** Small uppercase muted heading above a card. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = LocalAppColors.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.sp,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

/** The surface rows and charts sit on, in the style of the other cards of the app. */
@Composable
internal fun DetailCard(
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(colors.surface)
            .border(1.dp, colors.line, CARD_SHAPE)
            .padding(horizontal = horizontalPadding.dp),
        content = content,
    )
}

/** "Actualizando histórico…" with a small spinner, shown while the history is being downloaded. */
@Composable
internal fun SyncingRow(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.ink2)
        Text(text = SYNCING_TEXT, color = colors.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** What the chart area says when the history is not there: nothing yet, and a way to try again. */
@Composable
internal fun HistoryUnavailableCard(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    DetailCard(modifier = modifier, horizontalPadding = 20) {
        Text(
            text = UNAVAILABLE_TEXT,
            color = colors.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 14.dp),
        )
        GhostButton(text = "Reintentar", onClick = onRetry, modifier = Modifier.padding(bottom = 20.dp))
    }
}

/** The loading state of the first download: nothing cached to show yet. */
@Composable
internal fun HistoryLoadingCard(modifier: Modifier = Modifier) {
    DetailCard(modifier = modifier, horizontalPadding = 20) {
        SyncingRow(modifier = Modifier.padding(vertical = 28.dp))
    }
}

/**
 * The price chart of the product with its range chips and the change over the range. Pressing and dragging over the
 * chart replaces the change with the date and price of the point under the finger.
 */
@Composable
internal fun ChartCard(
    chart: ChartUi,
    ranges: List<RangeChipUi>,
    onRangeSelect: (HistoryRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var scrubbed by remember(chart.prices) { mutableStateOf<Int?>(null) }
    val tone = toneColor(chart.changeTone)
    val point = scrubbed?.let { chart.prices.getOrNull(it) }

    DetailCard(modifier = modifier, horizontalPadding = 16) {
        RangeChips(ranges = ranges, onSelect = onRangeSelect, modifier = Modifier.padding(top = 14.dp))
        Column(modifier = Modifier.padding(top = 14.dp).defaultMinSize(minHeight = 52.dp)) {
            Text(
                text = if (point == null) "VARIACIÓN DEL PRECIO" else "PRECIO",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.sp,
            )
            Text(
                text = if (point == null) chart.changeText ?: NO_FIGURE_TEXT else scrubText(point),
                color = if (point == null) tone else colors.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        LineChart(
            points = chart.points,
            lineColor = tone,
            onScrub = { scrubbed = it },
            modifier = Modifier.padding(top = 6.dp),
        )
        AxisLabels(start = chart.firstDateText, end = chart.lastDateText, modifier = Modifier.padding(top = 4.dp))
        AxisLabels(start = chart.minText, end = chart.maxText, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
    }
}

@Composable
private fun AxisLabels(start: String, end: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = start, color = colors.muted, fontSize = 11.sp)
        Text(text = end, color = colors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun RangeChips(ranges: List<RangeChipUi>, onSelect: (HistoryRange) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.bg2, shape)
            .border(1.dp, colors.line2, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ranges.forEach { chip ->
            Text(
                text = chip.label,
                color = when {
                    chip.selected -> colors.ink
                    chip.enabled -> colors.muted
                    else -> colors.muted.copy(alpha = DISABLED_ALPHA)
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (chip.selected) colors.surface2 else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = chip.enabled,
                        onClick = { onSelect(chip.range) },
                    )
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** A value on the right of a [SectionRow], right aligned and drawn in [color]. */
@Composable
private fun RowValue(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}

/** One row per calendar year, most recent first. */
@Composable
internal fun YearReturnsCard(rows: List<YearReturnUi>, modifier: Modifier = Modifier) {
    DetailCard(modifier = modifier) {
        rows.forEachIndexed { index, row ->
            SectionRow(
                title = row.label,
                showDivider = index != rows.lastIndex,
                trailing = { RowValue(row.valueText, toneColor(row.tone)) },
            )
        }
    }
}

/** The annualized returns over 1, 3 and 5 years. */
@Composable
internal fun AnnualizedCard(rows: List<FigureUi>, modifier: Modifier = Modifier) {
    DetailCard(modifier = modifier) {
        rows.forEachIndexed { index, row ->
            SectionRow(
                title = row.label,
                showDivider = index != rows.lastIndex,
                trailing = { RowValue(row.valueText, toneColor(row.tone)) },
            )
        }
    }
}

/** Volatility and the deepest fall (with when it happened). */
@Composable
internal fun RiskCard(risk: RiskUi, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    DetailCard(modifier = modifier) {
        SectionRow(
            title = risk.volatilityLabel,
            trailing = { RowValue(risk.volatilityText, colors.ink) },
        )
        SectionRow(
            title = "Máxima caída",
            subtitle = risk.drawdownDates,
            showDivider = false,
            trailing = {
                RowValue(
                    text = risk.drawdownText,
                    color = if (risk.drawdownDates == null) colors.ink else colors.expense,
                )
            },
        )
    }
}

/** What the provider says about the product; only the rows it filled in. */
@Composable
internal fun ProductDataCard(product: ProductDataUi, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val hasRisk = product.riskLevel != null
    val hasCategory = product.category != null
    val hasManager = product.manager != null
    DetailCard(modifier = modifier) {
        product.terText?.let { text ->
            SectionRow(
                title = "Gastos corrientes (TER)",
                subtitle = product.yearlyCostText,
                showDivider = hasRisk || hasCategory || hasManager,
                trailing = { RowValue(text, colors.ink) },
            )
        }
        product.riskLevel?.let { level ->
            SectionRow(
                title = "Riesgo",
                showDivider = hasCategory || hasManager,
                trailing = { RiskScale(level) },
            )
        }
        product.category?.let { text ->
            SectionRow(
                title = "Categoría",
                showDivider = hasManager,
                trailing = { RowValue(text, colors.ink, Modifier.widthIn(max = LONG_VALUE_MAX_WIDTH)) },
            )
        }
        product.manager?.let { text ->
            SectionRow(
                title = "Gestora",
                showDivider = false,
                trailing = { RowValue(text, colors.ink, Modifier.widthIn(max = LONG_VALUE_MAX_WIDTH)) },
            )
        }
    }
}

/** The SRRI scale from 1 to 7 with the product's [level] highlighted. */
@Composable
internal fun RiskScale(level: Int, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Riesgo $level de $MAX_RISK_LEVEL"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (step in MIN_RISK_LEVEL..MAX_RISK_LEVEL) {
            val active = step == level
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(if (active) colors.brand else colors.bg2, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = step.toString(),
                    color = if (active) colors.brandInk else colors.muted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

internal const val SYNCING_TEXT = "Actualizando histórico…"
internal const val SYNC_ERROR_TEXT = "No se pudo actualizar el histórico."
private const val UNAVAILABLE_TEXT = "Aún no hay histórico. Comprueba la conexión y vuelve a intentarlo."
