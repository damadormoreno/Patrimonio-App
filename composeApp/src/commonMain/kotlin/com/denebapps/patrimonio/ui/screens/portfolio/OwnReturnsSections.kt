package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.domain.calc.HistoryRange
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.theme.LocalAppColors

// The sections of the user's own returns, apart from the history of the product: "Mi posición" in the position
// detail and "Tu rentabilidad" in the portfolio detail. Presentational: every text arrives ready to show.

/** What each of the two sections says under its title, in the muted style of the other subtitles. */
@Composable
private fun SectionSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalAppColors.current.muted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = modifier.padding(bottom = 10.dp),
    )
}

/** A small muted note under a card. */
@Composable
internal fun CardNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalAppColors.current.muted2,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = modifier.padding(top = 6.dp),
    )
}

/**
 * "Mi posición": the figures, what is missing to complete them, the open lots (or, with many, the row that opens
 * them all on [onOpenLots]) and the chart of my units' value.
 */
@Composable
internal fun MyPositionSection(position: MyPositionUi, onRangeSelect: (HistoryRange) -> Unit, onOpenLots: () -> Unit) {
    SectionLabel(MY_POSITION_TITLE)
    SectionSubtitle(MY_POSITION_SUBTITLE)
    OwnFiguresCard(position.figures)
    position.hint?.let { CardNote(it) }
    if (position.lots.isNotEmpty()) {
        SectionLabel(LOTS_TITLE)
        LotsCard(position.lots)
        position.lotsNote?.let { CardNote(it) }
    }
    position.lotsSummary?.let {
        DetailCard(Modifier.padding(top = 16.dp)) {
            DrillDownRow(title = it.titleText, subtitle = it.oldestText, onClick = onOpenLots)
        }
    }
    position.chart?.let {
        SectionLabel(MY_POSITION_CHART_TITLE)
        ChartCard(
            chart = it,
            ranges = position.ranges,
            onRangeSelect = onRangeSelect,
            changeLabel = MY_POSITION_CHART_LABEL,
            scrubLabel = "VALOR",
        )
    }
}

/** "Tu rentabilidad": the figures of the scope and how many products they cover. */
@Composable
internal fun YourReturnsSection(block: YourReturnsUi) {
    SectionLabel(YOUR_RETURNS_TITLE)
    SectionSubtitle(YOUR_RETURNS_SUBTITLE)
    OwnFiguresCard(block.figures)
    CardNote(block.coverageText)
}

/** The labelled figures; one that cannot be given is a muted dash, with what qualifies it under the label. */
@Composable
private fun OwnFiguresCard(figures: List<OwnFigureUi>) {
    val colors = LocalAppColors.current
    DetailCard {
        figures.forEachIndexed { index, figure ->
            val color = when {
                figure.valueText == NO_FIGURE_TEXT || figure.valueText == NO_PRICE_TEXT -> colors.muted
                figure.tone == PerformanceTone.NEUTRAL -> colors.ink
                else -> toneColor(figure.tone)
            }
            SectionRow(
                title = figure.label,
                subtitle = figure.detailText,
                showDivider = index != figures.lastIndex,
                trailing = { RowValue(figure.valueText, color, Modifier.padding(start = 12.dp)) },
            )
        }
    }
}

/** The open lots, oldest first: the day each was bought, its units and its cost. */
@Composable
internal fun LotsCard(lots: List<LotUi>) {
    val colors = LocalAppColors.current
    DetailCard {
        lots.forEachIndexed { index, lot ->
            SectionRow(
                title = lot.dateText,
                subtitle = lot.unitsText,
                showDivider = index != lots.lastIndex,
                trailing = {
                    RowValue(lot.costText, if (lot.costText == UNKNOWN_COST_TEXT) colors.muted2 else colors.ink)
                },
            )
        }
    }
}
