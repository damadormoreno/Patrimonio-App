package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/**
 * Uppercase muted section label used to group [SettingsCard]s. Ported from
 * `perfil.jsx`'s settings section header pattern.
 */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    Text(
        text = title.uppercase(),
        color = colors.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06.sp,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * Grouped-row container for [SettingsRow] children — [AppColors.surface2]
 * background, `line` border, radius `md`. Ported from `perfil.jsx`'s settings
 * card pattern.
 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val shape = RoundedCornerShape(shapes.md)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color = colors.surface2, shape = shape)
            .border(width = 1.dp, color = colors.line, shape = shape),
        content = content,
    )
}

/**
 * One row inside a [SettingsCard]: leading icon/content, title, optional
 * subtitle, optional trailing content, min height 44dp, separated by a
 * `line` rule (skip on the last row via [showDivider]). [danger] renders the
 * title in `expense` for destructive actions (screens.jsx `danger` rows).
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    danger: Boolean = false,
) {
    val colors = LocalAppColors.current
    val lineColor = colors.line

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (showDivider) {
                    Modifier.drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(16f, size.height),
                            end = Offset(size.width - 16f, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            leading()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (danger) colors.expense else colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}
