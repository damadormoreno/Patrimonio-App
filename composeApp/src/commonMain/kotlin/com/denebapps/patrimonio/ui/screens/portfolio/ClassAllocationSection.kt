package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.StackedBar
import com.denebapps.patrimonio.ui.components.StackedBarSegment
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val SHARE_COLUMN_WIDTH = 52.dp

/**
 * "Reparto por clase de activo": one stacked bar with a part per class, then a row per class with its color, amount
 * and share, and the hint to classify the holdings that have no class.
 */
@Composable
internal fun ClassAllocationSection(allocation: ClassAllocationUi) {
    val colors = LocalAppColors.current
    SectionLabel(CLASS_ALLOCATION_TITLE)
    DetailCard {
        StackedBar(
            segments = allocation.rows.map { StackedBarSegment(it.share, assetClassColor(it.assetClass)) },
            modifier = Modifier.padding(top = 16.dp),
        )
        allocation.rows.forEachIndexed { index, row ->
            ClassRow(row, bottomPadding = if (index == allocation.rows.lastIndex) 14 else 0)
        }
        allocation.unclassifiedHint?.let {
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
private fun ClassRow(row: ClassRowUi, bottomPadding: Int) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.padding(top = 10.dp, bottom = bottomPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(assetClassColor(row.assetClass), RoundedCornerShape(2.dp)))
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
}
