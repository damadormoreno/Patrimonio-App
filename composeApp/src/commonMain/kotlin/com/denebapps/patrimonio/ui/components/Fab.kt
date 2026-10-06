package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.AppShadows
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/** Stroke weight used for the plus icon inside [Fab], per `shared.jsx`'s `FAB` sketch. */
private const val FAB_ICON_STROKE_WIDTH = 2.2f

/**
 * Floating action button for adding a new movement. Ported from `shared.jsx`'s
 * `FAB({ onClick })`: 56dp circle, [AppColors.ink] background, `sh-2`/`sh-3`
 * shadow blend approximated with `sh-3` (the heavier of the two).
 */
@Composable
fun Fab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.get("plus", FAB_ICON_STROKE_WIDTH),
) {
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .size(56.dp)
            .then(AppShadows.sh3(CircleShape))
            .background(color = colors.ink, shape = CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Add",
            tint = colors.bg,
            modifier = Modifier.size(26.dp),
        )
    }
}
