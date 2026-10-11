package com.denebapps.patrimonio.ui.components.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val AXIS_WIDTH = 40.dp
private val MIN_CELL = 38.dp
private val MAX_CELL = 56.dp
private val CELL_GAP = 3.dp
private val CELL_SHAPE = RoundedCornerShape(6.dp)
private val SELECTION_WIDTH = 2.dp

/** One cell of a [Heatmap]: its printed [text] on [background] in [textColor]; only a [selectable] one reacts to taps. */
data class HeatmapCell(val text: String, val background: Color, val textColor: Color, val selectable: Boolean = true)

/** A cell of a [Heatmap] by position. */
data class HeatmapPosition(val row: Int, val column: Int)

/**
 * A square grid of colored, labelled cells with [labels] on top and on the left (the same ones, as the grid is
 * symmetric). The cells fill the width when they fit at a minimum size and scroll sideways when there are too many.
 * Tapping a selectable cell calls [onSelect]; the [selected] cell is outlined. Colors come with the cells, so the
 * screen decides the scale (see `divergingArgb`).
 */
@Composable
fun Heatmap(
    labels: List<String>,
    cells: List<List<HeatmapCell>>,
    selected: HeatmapPosition?,
    onSelect: (HeatmapPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellSize = ((maxWidth - AXIS_WIDTH) / labels.size).coerceIn(MIN_CELL, MAX_CELL)
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                Spacer(Modifier.width(AXIS_WIDTH))
                labels.forEach { AxisLabel(it, Modifier.width(cellSize), TextAlign.Center) }
            }
            labels.forEachIndexed { row, label ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AxisLabel(label, Modifier.width(AXIS_WIDTH), TextAlign.Start)
                    cells[row].forEachIndexed { column, cell ->
                        val position = HeatmapPosition(row, column)
                        HeatmapCellBox(cell, cellSize, position == selected) { onSelect(position) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text,
        color = LocalAppColors.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

@Composable
private fun HeatmapCellBox(cell: HeatmapCell, size: Dp, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CELL_SHAPE)
            .background(cell.background)
            .then(if (selected) Modifier.border(SELECTION_WIDTH, colors.ink, CELL_SHAPE) else Modifier)
            .clickable(enabled = cell.selectable, onClickLabel = "Ver el par", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cell.text,
            color = cell.textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
