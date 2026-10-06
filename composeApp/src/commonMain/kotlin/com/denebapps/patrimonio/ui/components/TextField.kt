package com.denebapps.patrimonio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.icons.AppIcons
import com.denebapps.patrimonio.ui.theme.LocalAppColors
import com.denebapps.patrimonio.ui.theme.LocalAppShapes

/**
 * Floating-label text field. Ported from `shared.jsx`'s `Field`/`Input` pattern:
 * [AppColors.surface] background, radius `md` (14dp), border animates
 * `line` → `ink` on focus, label lifts from 15sp inline placeholder to 11sp
 * uppercase caption. Supports an optional [leadingIcon] and, when [isPassword]
 * is true, a trailing eye/eye-off visibility toggle.
 */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    isPassword: Boolean = false,
) {
    val colors = LocalAppColors.current
    val shapes = LocalAppShapes.current
    val shape = RoundedCornerShape(shapes.md)

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(if (focused) colors.ink else colors.line)
    val labelFloated = focused || value.isNotEmpty()
    val labelFontSize by animateFloatAsState(if (labelFloated) 11f else 15f)

    val visualTransformation = when {
        !isPassword -> VisualTransformation.None
        passwordVisible -> VisualTransformation.None
        else -> PasswordVisualTransformation()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colors.surface, shape = shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(18.dp).padding(end = 10.dp),
                )
            }
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    color = colors.muted,
                    fontSize = labelFontSize.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.04.sp,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    interactionSource = interactionSource,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    cursorBrush = SolidColor(colors.ink),
                    visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            if (isPassword) {
                val toggleIcon = if (passwordVisible) AppIcons.eyeOff else AppIcons.eye
                Icon(
                    imageVector = toggleIcon,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    tint = colors.muted,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 10.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { passwordVisible = !passwordVisible },
                        ),
                )
            }
        }
    }
}
