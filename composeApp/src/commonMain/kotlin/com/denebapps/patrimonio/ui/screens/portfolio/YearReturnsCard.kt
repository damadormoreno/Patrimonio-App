package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.components.charts.BarChart
import com.denebapps.patrimonio.ui.theme.LocalAppColors

private val BOTTOM_SPACE = 12.dp

/**
 * The return of each calendar year as bars, the oldest on the left (see [BarChart]); the first year of the history and
 * the running one are lighter and a footnote under the chart says why.
 */
@Composable
internal fun YearReturnsCard(years: List<YearReturnUi>, modifier: Modifier = Modifier) {
    val bars = remember(years) { years.map { it.toBar() } }
    val description = remember(years) { yearReturnsDescription(years) }
    val footnote = remember(years) { yearReturnsFootnote(years) }
    DetailCard(modifier = modifier, horizontalPadding = 12) {
        BarChart(bars = bars, contentDescription = description, modifier = Modifier.padding(top = 14.dp))
        if (footnote == null) {
            Spacer(Modifier.height(BOTTOM_SPACE))
        } else {
            Text(
                text = footnote,
                color = LocalAppColors.current.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = BOTTOM_SPACE),
            )
        }
    }
}
