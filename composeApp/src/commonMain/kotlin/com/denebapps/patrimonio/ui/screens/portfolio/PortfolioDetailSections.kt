package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.components.charts.Heatmap
import com.denebapps.patrimonio.ui.components.charts.HeatmapCell
import com.denebapps.patrimonio.ui.components.charts.HeatmapPosition
import com.denebapps.patrimonio.ui.components.charts.divergingArgb
import com.denebapps.patrimonio.ui.components.charts.readableArgb
import com.denebapps.patrimonio.ui.theme.AppColors
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val BAR_HEIGHT = 6.dp
private val BAR_SHAPE = RoundedCornerShape(3.dp)
private val AXIS_LEGEND_WIDTH = 40.dp
private val SHARE_COLUMN_WIDTH = 52.dp
private const val CAPTION_HINT = "Toca una celda para ver el par."

// ---- header ----------------------------------------------------------------------------------------------

/** The title (the portfolio's name), the total, how many products there are and how many are quoted or manual. */
@Composable
internal fun PortfolioHeader(header: PortfolioHeaderUi) {
    val colors = LocalAppColors.current
    Text(
        text = header.title,
        color = colors.ink,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
    )
    Text(
        text = header.totalText,
        color = colors.ink,
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).sp,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = header.productsText,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 2.dp),
    )
    header.mixText?.let {
        Text(text = it, color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---- evolution --------------------------------------------------------------------------------------------

/** The constant-composition chart, what it is made of, and the returns and risk of that series. */
@Composable
internal fun EvolutionSection(evolution: EvolutionUi, onRangeSelect: (HistoryRange) -> Unit) {
    val colors = LocalAppColors.current
    SectionLabel("Evolución con la composición actual")
    Text(text = evolution.subtitle, color = colors.muted, fontSize = 12.sp, lineHeight = 17.sp)
    evolution.excludedText?.let {
        Text(
            text = it,
            color = colors.muted2,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    ChartCard(
        chart = evolution.chart,
        ranges = evolution.ranges,
        onRangeSelect = onRangeSelect,
        modifier = Modifier.padding(top = 10.dp),
        changeLabel = "VARIACIÓN DEL VALOR",
        scrubLabel = "VALOR",
    )
    if (evolution.yearReturns.isNotEmpty()) {
        SectionLabel("Rentabilidad por año")
        YearReturnsCard(evolution.yearReturns)
    }
    SectionLabel("Riesgo")
    RiskCard(evolution.risk)
}

// ---- correlation ------------------------------------------------------------------------------------------

/** The correlation matrix with its legend, or the reason there is none. */
@Composable
internal fun CorrelationSection(section: CorrelationSectionUi) {
    val colors = LocalAppColors.current
    SectionLabel("Correlación")
    DetailCard(horizontalPadding = 12) {
        val matrix = section.matrix
        if (matrix == null) {
            Text(
                text = section.message.orEmpty(),
                color = colors.muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        } else {
            CorrelationMatrix(matrix)
        }
        section.omittedText?.let {
            Text(
                text = it,
                color = colors.muted2,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun CorrelationMatrix(matrix: CorrelationUi) {
    val colors = LocalAppColors.current
    var selected by remember(matrix) { mutableStateOf<HeatmapPosition?>(null) }
    val cells = remember(matrix, colors) { matrix.cells.map { row -> row.map { heatmapCell(it, colors) } } }

    Box(Modifier.padding(top = 14.dp)) {
        Heatmap(labels = matrix.labels, cells = cells, selected = selected, onSelect = { selected = it })
    }
    // A fixed height keeps the page from jumping when a cell is picked.
    Text(
        text = selected?.let { matrix.cells[it.row][it.column].detailText } ?: CAPTION_HINT,
        color = if (selected == null) colors.muted2 else colors.ink,
        fontSize = 12.sp,
        fontWeight = if (selected == null) FontWeight.Normal else FontWeight.Medium,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 10.dp).defaultMinSize(minHeight = 34.dp),
    )
    matrix.mostSimilarText?.let {
        Text(
            text = it,
            color = colors.ink2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        matrix.labels.zip(matrix.names).forEach { (label, name) -> LegendEntry(label, name) }
    }
    Text(
        text = matrix.explanation,
        color = colors.muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
    )
}

@Composable
private fun LegendEntry(label: String, name: String) {
    val colors = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            color = colors.ink2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(AXIS_LEGEND_WIDTH),
        )
        Text(text = name, color = colors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * The cell as drawn: a diverging scale from the chart pair of the theme (the loss tone at -1, the card paper at 0, the
 * gain tone at +1; unlike income and expense it stays apart for color-blind readers), with the printed value in
 * whichever of ink and paper reads better on it. The diagonal is just the product with itself, so it stays quiet.
 */
private fun heatmapCell(cell: CorrelationCellUi, colors: AppColors): HeatmapCell {
    if (cell.detailText == null) {
        return HeatmapCell(cell.valueText, colors.surface, colors.muted2, selectable = false)
    }
    val background = divergingArgb(
        value = cell.value,
        negative = colors.chartLoss.toArgb(),
        neutral = colors.bg2.toArgb(),
        positive = colors.chartGain.toArgb(),
    )
    val text = readableArgb(background, colors.ink.toArgb(), colors.surface2.toArgb())
    return HeatmapCell(cell.valueText, Color(background), Color(text))
}

// ---- allocation -------------------------------------------------------------------------------------------

/** One card per way of splitting the portfolio, each bucket with its amount, share and a bar. */
@Composable
internal fun AllocationSection(groups: List<AllocationGroupUi>) {
    SectionLabel("Reparto")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { AllocationCard(it) }
    }
}

@Composable
private fun AllocationCard(group: AllocationGroupUi) {
    val colors = LocalAppColors.current
    DetailCard {
        Text(
            text = group.title,
            color = colors.ink2,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
        )
        group.rows.forEachIndexed { index, row ->
            AllocationRow(row, bottomPadding = if (index == group.rows.lastIndex) 14 else 0)
        }
    }
}

@Composable
private fun AllocationRow(row: AllocationRowUi, bottomPadding: Int) {
    val colors = LocalAppColors.current
    Column(Modifier.padding(top = 8.dp, bottom = bottomPadding.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = row.label,
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(text = row.amountText, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = row.shareText,
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.width(SHARE_COLUMN_WIDTH),
                textAlign = TextAlign.End,
            )
        }
        Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(BAR_HEIGHT).background(colors.bg2, BAR_SHAPE)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(row.share.coerceIn(0f, 1f))
                    .background(colors.brand, BAR_SHAPE),
            )
        }
    }
}

// ---- costs and risk, concentration ------------------------------------------------------------------------

/** The average ongoing charge with what it costs a year, and the average risk class. */
@Composable
internal fun CostsRiskSection(costs: CostsRiskUi) {
    val colors = LocalAppColors.current
    SectionLabel("Costes y riesgo")
    DetailCard {
        costs.terText?.let { text ->
            SectionRow(
                title = "Gastos corrientes medios (TER)",
                subtitle = listOfNotNull(costs.yearlyCostText, costs.terCoverage).joinToString("\n"),
                showDivider = costs.riskText != null,
                trailing = { RowValue(text, colors.ink) },
            )
        }
        costs.riskText?.let { text ->
            SectionRow(
                title = "Riesgo medio",
                subtitle = costs.riskCoverage,
                showDivider = false,
                trailing = { RowValue(text, colors.ink) },
            )
        }
    }
}

/** The largest holding and its weight, with a warning banner when it is a concentration risk. */
@Composable
internal fun ConcentrationSection(concentration: ConcentrationUi) {
    val colors = LocalAppColors.current
    SectionLabel("Concentración")
    DetailCard {
        SectionRow(
            title = "Mayor posición",
            subtitle = concentration.name,
            showDivider = false,
            trailing = { RowValue(concentration.weightText, colors.ink) },
        )
    }
    concentration.warning?.let { WarningBanner(it, Modifier.padding(top = 10.dp)) }
}

/** A message in the alert colors of the theme, for a risk the user should look at. */
@Composable
private fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Text(
        text = text,
        color = colors.alert,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.alertSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
