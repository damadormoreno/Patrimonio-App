package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** A segment must keep a positive weight for the layout; this is far below a pixel. */
private const val MIN_WEIGHT = 0.0001f

/** One part of a [StackedBar]: it takes [weight] out of the sum of all the weights, in [color]. */
data class StackedBarSegment(val weight: Float, val color: Color)

/** A pill-shaped horizontal bar split into [segments], in order, each as wide as its weight says. */
@Composable
fun StackedBar(segments: List<StackedBarSegment>, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.bg2)
            .border(1.dp, colors.line2, RoundedCornerShape(999.dp)),
    ) {
        segments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight(segment.weight.coerceAtLeast(MIN_WEIGHT))
                    .fillMaxHeight()
                    .background(segment.color),
            )
        }
    }
}
