package com.denebapps.patrimonio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.denebapps.patrimonio.ui.theme.LocalAppColors

/**
 * Standalone underlined link, used for tertiary actions (e.g. "Forgot password?").
 * Ported from `shared.jsx`'s link pattern: 13sp weight 600, [AppColors.ink2],
 * underline offset by 3.
 */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current

    Text(
        text = text,
        color = colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    )
}
