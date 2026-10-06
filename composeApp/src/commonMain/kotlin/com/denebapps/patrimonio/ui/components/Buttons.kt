package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.AppShadows
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/**
 * Full-width filled action button. Ported from `shared.jsx`'s `Button`/`PrimaryButton`
 * pattern: [AppColors.ink] background, [AppColors.bg] foreground, radius `md`
 * (14dp), `sh-1` resting shadow, 0.4 alpha when [enabled] is false.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val shape = RoundedCornerShape(shapes.md)
    val alpha = if (enabled) 1f else 0.4f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(AppShadows.sh1(shape))
            .background(color = colors.ink.copy(alpha = alpha), shape = shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.bg.copy(alpha = alpha),
                modifier = Modifier.size(18.dp).padding(end = 8.dp),
            )
        }
        Text(
            text = text,
            color = colors.bg.copy(alpha = alpha),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Full-width outlined action button. Ported from `shared.jsx`'s `GhostButton`:
 * [AppColors.surface] background, `line` border, radius `md`.
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val shape = RoundedCornerShape(shapes.md)
    val alpha = if (enabled) 1f else 0.4f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colors.surface.copy(alpha = alpha), shape = shape)
            .border(width = 1.dp, color = colors.line, shape = shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = colors.ink.copy(alpha = alpha),
                modifier = Modifier.size(17.dp).padding(end = 8.dp),
            )
        }
        Text(
            text = text,
            color = colors.ink.copy(alpha = alpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Inline text-only link. Ported from `shared.jsx`'s `TextLink`: 13sp weight 600,
 * [AppColors.ink2] with an underline offset to approximate the JSX
 * `text-decoration-color`/`text-underline-offset` styling.
 */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    Text(
        text = text,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    )
}
