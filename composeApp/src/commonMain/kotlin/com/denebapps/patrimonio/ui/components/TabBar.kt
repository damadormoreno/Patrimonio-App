package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import kotlin.math.roundToInt

/** Stroke weight for the active tab's icon; inactive tabs use [AppIcons.DEFAULT_STROKE_WIDTH]. */
private const val TAB_ACTIVE_ICON_STROKE_WIDTH = 2.0f
private const val TAB_BAR_BASE_BOTTOM_PADDING_DP = 8

/**
 * One tab entry in [TabBar]. [iconName] must be the icon's `AppIcons` property
 * name (accessed at least once beforehand) so the active state can rebuild it
 * at [TAB_ACTIVE_ICON_STROKE_WIDTH].
 */
data class TabItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val iconName: String,
)

/**
 * Bottom navigation bar. Ported from `shared.jsx`'s `TabBar({ tab, setTab })`.
 * Active tab renders in [AppColors.ink]; inactive tabs render in
 * [AppColors.muted].
 */
@Composable
fun TabBar(tabs: List<TabItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val navigationBarInsetDp = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp().value.roundToInt()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colors.surface)
            .border(width = 1.dp, color = colors.line)
            .padding(top = 8.dp, bottom = tabBarBottomPaddingDp(navigationBarInsetDp).dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val active = tab.id == selected
            val tint = if (active) colors.ink else colors.muted
            val icon = if (active) AppIcons.get(tab.iconName, TAB_ACTIVE_ICON_STROKE_WIDTH) else tab.icon

            Column(
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(tab.id) },
                    )
                    .padding(top = 8.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = tab.label,
                    color = tint,
                    fontSize = 10.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.01.sp,
                )
            }
        }
    }
}

/** Adds the current system navigation inset once, replacing the prototype's fixed safe-area guess. */
internal fun tabBarBottomPaddingDp(navigationBarInsetDp: Int): Int =
    TAB_BAR_BASE_BOTTOM_PADDING_DP + navigationBarInsetDp.coerceAtLeast(0)
