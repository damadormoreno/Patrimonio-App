package com.denebapps.patrimonio.ui.screens.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.components.SectionRow
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * A row of a card that opens a screen with the full list of what the card summarises: its [title], an optional muted
 * [subtitle] and a chevron on the right.
 */
@Composable
internal fun DrillDownRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showDivider: Boolean = false,
) {
    val colors = LocalAppColors.current
    SectionRow(
        title = title,
        subtitle = subtitle,
        showDivider = showDivider,
        modifier = modifier.clickable(onClickLabel = title, onClick = onClick),
        trailing = {
            Icon(
                imageVector = AppIcons.chevronR,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.padding(start = 12.dp).size(16.dp),
            )
        },
    )
}
